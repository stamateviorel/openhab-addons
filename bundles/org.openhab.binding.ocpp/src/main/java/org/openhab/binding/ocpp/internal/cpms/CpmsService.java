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
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;
import org.openhab.core.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

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

    public CpmsService(Storage<String> storage) {
        this(storage, Clock.systemDefaultZone());
    }

    CpmsService(Storage<String> storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    /** Registered by the CPMS user Things, which are the source of truth for people and their cards. */
    public void registerUser(CpmsUser user) {
        userRegistry.put(user.id(), user);
    }

    public void unregisterUser(String id) {
        userRegistry.remove(id);
    }

    public List<CpmsUser> users() {
        return new ArrayList<>(userRegistry.values());
    }

    /** What kind of token this is, as far as the enrolled users say. */
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
     * Authorization decision for a card, or {@code null} when the CPMS is not managing authorization
     * (no users defined) so the caller falls back to its own whitelist.
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
        if (cap > 0 && energyKwh(user.id(), monthStartEpoch(), Long.MAX_VALUE) >= cap) {
            logger.info("User {} reached the monthly cap of {} kWh; card {} rejected until next month", user.name(),
                    cap, idTag);
            return false;
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

    /** The token presented after a plug-first start; the session is logged under it. */
    /**
     * Gives an ownerless (plug-first) session the token presented later. A session that already has an
     * owner keeps it: on 1.6 the tag that stops a session may not be the one that started it.
     */
    public synchronized void onTransactionAuthorized(int transactionId, String idTag) {
        String key = OPEN_PREFIX + transactionId;
        String json = storage.get(key);
        OpenTx open = json == null ? null : gson.fromJson(json, OpenTx.class);
        if (open != null && open.idTag() == null) {
            storage.put(key, gson.toJson(
                    new OpenTx(idTag, open.chargePointId(), open.connectorId(), open.meterStart(), open.startEpoch())));
        }
    }

    public synchronized void onTransactionStop(int transactionId, @Nullable Integer meterStop, long stopEpoch) {
        String key = OPEN_PREFIX + transactionId;
        String json = storage.get(key);
        if (json == null) {
            return;
        }
        OpenTx open = gson.fromJson(json, OpenTx.class);
        if (open == null) {
            storage.remove(key);
            return;
        }
        String idTag = open.idTag();
        if (idTag == null) {
            // Nobody ever presented a token, so there is no one to log the session under.
            logger.debug("Session {} ended without a token; not recorded", transactionId);
            storage.remove(key);
            return;
        }
        List<CpmsTransaction> log = readLog();
        if (log == null) {
            // Never overwrite an unreadable log — that would wipe every past month's history at once. Keep the
            // open key so the session is not also lost from the open store; it can be recovered once the log is fixed.
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

    /** Every session ever recorded — the durable log is append-only and never trimmed. */
    public synchronized List<CpmsTransaction> transactions() {
        List<CpmsTransaction> log = readLog();
        return log == null ? new ArrayList<>() : new ArrayList<>(log);
    }

    /**
     * The live log, parsed from storage once and cached in memory so authorize does not re-parse the JSON on
     * every tap. Returns {@code null} only when the stored JSON is corrupt (never cached, never clobbered).
     */
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
            cache = arr == null ? new ArrayList<>() : new ArrayList<>(List.of(arr));
            return cache;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /** The most recent sessions first, up to {@code limit}. */
    public synchronized List<CpmsTransaction> recentTransactions(int limit) {
        List<CpmsTransaction> all = transactions();
        List<CpmsTransaction> recent = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && recent.size() < limit; i--) {
            recent.add(all.get(i));
        }
        return recent;
    }

    /** kWh charged to a user's cards for sessions that ended within {@code [fromEpoch, toEpoch)}. */
    public synchronized double energyKwh(String userId, long fromEpoch, long toEpoch) {
        double wh = 0;
        for (CpmsTransaction tx : transactions()) {
            if (userId.equals(tx.userId()) && tx.stopEpoch() >= fromEpoch && tx.stopEpoch() < toEpoch) {
                wh += tx.energyWh();
            }
        }
        return wh / 1000.0;
    }

    /** Per-user kWh for the month and the year, given the two window starts and now. */
    public synchronized List<Usage> usage(long monthStart, long yearStart, long now) {
        Map<String, double[]> totals = new HashMap<>();
        for (CpmsTransaction tx : transactions()) {
            String userId = tx.userId();
            if (userId == null || tx.stopEpoch() >= now || tx.stopEpoch() < yearStart) {
                continue;
            }
            double[] bucket = totals.computeIfAbsent(userId, k -> new double[2]);
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
