/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.ocpp.internal.cpms;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;
import org.openhab.core.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * The CPMS: a user/card registry, person-based authorization, and a persisted log of completed
 * charging sessions. State lives in a {@link Storage} so it survives a restart, the same as the
 * core transaction store.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class CpmsService {

    private static final String KEY_TRANSACTIONS = "transactions";
    private static final String OPEN_PREFIX = "open:";

    private final Logger logger = LoggerFactory.getLogger(CpmsService.class);
    private final Storage<String> storage;
    private final Gson gson = new Gson();
    private final Map<String, CpmsUser> userRegistry = new ConcurrentHashMap<>();
    private final Clock clock;
    private @Nullable List<CpmsTransaction> cache;
    private boolean corruptLogReported;

    public CpmsService(Storage<String> storage) {
        this(storage, Clock.systemDefaultZone());
    }

    CpmsService(Storage<String> storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    public void registerUser(CpmsUser user) {
        userRegistry.put(user.id(), user);
    }

    public void unregisterUser(String id) {
        userRegistry.remove(id);
    }

    public List<CpmsUser> users() {
        return new ArrayList<>(userRegistry.values());
    }

    public TokenType tokenTypeOf(String token) {
        for (CpmsUser user : users()) {
            if (user.vehicles().contains(token)) {
                return TokenType.VEHICLE;
            }
            if (user.cards().contains(token)) {
                return TokenType.CARD;
            }
        }
        return TokenType.UNKNOWN;
    }

    public @Nullable CpmsUser userForCard(String idTag) {
        for (CpmsUser u : userRegistry.values()) {
            if (u.owns(idTag)) {
                return u;
            }
        }
        return null;
    }

    /**
     * Authorization decision for a card, or {@code null} when no user is registered — which hands the
     * decision back to the caller rather than granting anything. An enabled cpms-user thing whose handler
     * has not attached yet also reads as no user here, so the caller must not treat {@code null} as a pass.
     */
    public @Nullable Boolean authorize(@Nullable String idTag) {
        if (userRegistry.isEmpty()) {
            return null;
        }
        if (idTag == null) {
            return false;
        }
        CpmsUser user = userForCard(idTag);
        if (user == null || !user.enabled()) {
            return false;
        }
        double cap = user.monthlyCapKwh();
        if (cap > 0) {
            Double used = energyKwhOrNull(user.id(), monthStartEpoch(), Long.MAX_VALUE);
            // An unreadable log would otherwise read as 0 kWh and lift every cap.
            if (used == null) {
                logger.warn("Rejecting {}: the monthly cap of {} kWh cannot be checked against an unreadable log",
                        user.name(), cap);
                return false;
            }
            if (used >= cap) {
                logger.debug("User {} reached the monthly cap of {} kWh; authorization rejected until next month",
                        user.name(), cap);
                return false;
            }
        }
        return true;
    }

    private long monthStartEpoch() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        return now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.getZone()).toInstant().toEpochMilli();
    }

    public synchronized void onTransactionStart(int transactionId, @Nullable String idTag, String chargePointId,
            int connectorId, @Nullable Integer meterStart, long startEpoch) {
        OpenTx open = new OpenTx(idTag, chargePointId, connectorId, meterStart == null ? 0 : meterStart, startEpoch);
        storage.put(OPEN_PREFIX + transactionId, gson.toJson(open));
    }

    /** Adopts a later token into an ownerless session; on 1.6 the stopping tag need not be the starting one. */
    public synchronized boolean onTransactionAuthorized(int transactionId, String idTag) {
        String key = OPEN_PREFIX + transactionId;
        OpenTx open = readOpenTx(key);
        if (open == null || open.idTag() != null) {
            return false;
        }
        storage.put(key, gson.toJson(
                new OpenTx(idTag, open.chargePointId(), open.connectorId(), open.meterStart(), open.startEpoch())));
        return true;
    }

    /** The charger's own StopTransaction: both ends are its register, whoever opened the session. */
    public synchronized void onTransactionStop(int transactionId, @Nullable Integer meterStop, long stopEpoch) {
        String key = OPEN_PREFIX + transactionId;
        OpenTx open = readOpenTx(key);
        if (open == null) {
            return;
        }
        String idTag = open.idTag();
        if (idTag == null) {
            logger.debug("Session {} ended without a token; not recorded", transactionId);
            storage.remove(key);
            return;
        }
        List<CpmsTransaction> log = readLog();
        if (log == null) {
            logger.error("CPMS transaction log is unreadable; session {} not recorded to preserve past usage",
                    transactionId);
            return;
        }
        // A meter-less charger sends no meterStop, so the session is logged with 0 energy.
        double energy = meterStop == null ? 0 : Math.max(0, meterStop - open.meterStart());
        CpmsUser user = userForCard(idTag);
        log.add(new CpmsTransaction(idTag, user == null ? null : user.id(), open.chargePointId(), open.connectorId(),
                open.startEpoch(), stopEpoch, energy));
        storage.put(KEY_TRANSACTIONS, gson.toJson(log));
        storage.remove(key);
    }

    public synchronized void forgetTransaction(int transactionId) {
        String key = OPEN_PREFIX + transactionId;
        OpenTx open = readOpenTx(key);
        if (open != null) {
            logger.debug("Session {} on {} connector {} is dropped unlogged; its StopTransaction never arrived",
                    transactionId, open.chargePointId(), open.connectorId());
            storage.remove(key);
        }
    }

    /** Every session ever recorded — the durable log is append-only and never trimmed. */
    public synchronized List<CpmsTransaction> transactions() {
        List<CpmsTransaction> log = readLog();
        return log == null ? new ArrayList<>() : new ArrayList<>(log);
    }

    /** {@code null} only when the stored JSON is corrupt; a corrupt log is never cached. */
    private @Nullable List<CpmsTransaction> readLog() {
        List<CpmsTransaction> cached = cache;
        if (cached != null) {
            return cached;
        }
        String json = storage.get(KEY_TRANSACTIONS);
        if (json == null) {
            cache = new ArrayList<>();
            return cache;
        }
        try {
            CpmsTransaction @Nullable [] arr = gson.fromJson(json, CpmsTransaction[].class);
            List<CpmsTransaction> log = new ArrayList<>();
            if (arr != null) {
                for (CpmsTransaction tx : arr) {
                    // Gson maps a JSON null - which only a hand-edited file holds - to a null element.
                    if (tx != null) {
                        log.add(tx);
                    }
                }
            }
            corruptLogReported = false;
            cache = log;
            return log;
        } catch (JsonParseException e) {
            if (!corruptLogReported) {
                corruptLogReported = true;
                logger.error("Storage key '{}' does not hold a readable session log; sessions are not recorded and"
                        + " capped users are refused until it is repaired or removed", KEY_TRANSACTIONS, e);
            }
            return null;
        }
    }

    /** {@code null} when nothing is stored under the key, or what was stored is corrupt and has been dropped. */
    private @Nullable OpenTx readOpenTx(String key) {
        String json = storage.get(key);
        if (json == null) {
            return null;
        }
        try {
            OpenTx open = gson.fromJson(json, OpenTx.class);
            if (open != null) {
                return open;
            }
        } catch (JsonParseException e) {
            logger.debug("Dropping the unreadable open session stored under '{}'", key, e);
        }
        storage.remove(key);
        return null;
    }

    /** Session count without copying the log; 0 when the log is unreadable. */
    public synchronized int transactionCount() {
        List<CpmsTransaction> log = readLog();
        return log == null ? 0 : log.size();
    }

    /** End of the newest logged session, or 0 when there is none. */
    public synchronized long lastStopEpoch() {
        List<CpmsTransaction> log = readLog();
        if (log == null) {
            return 0;
        }
        long last = 0;
        for (CpmsTransaction tx : log) {
            last = Math.max(last, tx.stopEpoch());
        }
        return last;
    }

    public synchronized List<CpmsTransaction> recentTransactions(int limit) {
        List<CpmsTransaction> all = transactions();
        List<CpmsTransaction> recent = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && recent.size() < limit; i--) {
            recent.add(all.get(i));
        }
        return recent;
    }

    public synchronized double energyKwh(String userId, long fromEpoch, long toEpoch) {
        Double kwh = energyKwhOrNull(userId, fromEpoch, toEpoch);
        return kwh == null ? 0 : kwh;
    }

    /** {@code null} when the log is unreadable, which must not be mistaken for no usage. */
    private synchronized @Nullable Double energyKwhOrNull(String userId, long fromEpoch, long toEpoch) {
        List<CpmsTransaction> log = readLog();
        if (log == null) {
            return null;
        }
        double wh = 0;
        for (CpmsTransaction tx : log) {
            if (userId.equals(tx.userId()) && tx.stopEpoch() >= fromEpoch && tx.stopEpoch() < toEpoch) {
                wh += tx.energyWh();
            }
        }
        return wh / 1000.0;
    }

    public synchronized List<Usage> usage(long monthStart, long yearStart, long now) {
        Map<String, double[]> totals = new HashMap<>();
        for (CpmsTransaction tx : transactions()) {
            String userId = tx.userId();
            if (userId == null || tx.stopEpoch() >= now || tx.stopEpoch() < yearStart) {
                continue;
            }
            double[] bucket = Objects.requireNonNull(totals.computeIfAbsent(userId, k -> new double[2]));
            bucket[1] += tx.energyWh();
            if (tx.stopEpoch() >= monthStart) {
                bucket[0] += tx.energyWh();
            }
        }
        List<Usage> out = new ArrayList<>();
        for (CpmsUser user : users()) {
            double[] bucket = totals.getOrDefault(user.id(), new double[2]);
            out.add(new Usage(user, bucket[0] / 1000.0, bucket[1] / 1000.0));
        }
        return out;
    }

    public record Usage(CpmsUser user, double monthKwh, double yearKwh) {
    }

    private record OpenTx(@Nullable String idTag, String chargePointId, int connectorId, int meterStart,
            long startEpoch) {
    }
}
