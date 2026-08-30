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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.storage.Storage;

import com.google.gson.Gson;

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

    private final Storage<String> storage;
    private final Gson gson = new Gson();
    private final Map<String, CpmsUser> userRegistry = new ConcurrentHashMap<>();

    public CpmsService(Storage<String> storage) {
        this.storage = storage;
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

    public @Nullable CpmsUser userForCard(String idTag) {
        for (CpmsUser u : userRegistry.values()) {
            if (u.cards().contains(idTag)) {
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
        return user != null && user.enabled();
    }

    public synchronized void onTransactionStart(int transactionId, @Nullable String idTag, String chargePointId,
            int connectorId, @Nullable Integer meterStart, long startEpoch) {
        if (idTag == null) {
            return;
        }
        OpenTx open = new OpenTx(idTag, chargePointId, connectorId, meterStart == null ? 0 : meterStart, startEpoch);
        storage.put(OPEN_PREFIX + transactionId, gson.toJson(open));
    }

    public synchronized void onTransactionStop(int transactionId, @Nullable Integer meterStop, long stopEpoch) {
        String key = OPEN_PREFIX + transactionId;
        String json = storage.get(key);
        if (json == null) {
            return;
        }
        storage.remove(key);
        OpenTx open = gson.fromJson(json, OpenTx.class);
        if (open == null) {
            return;
        }
        // A meter-less charger sends no meterStop, so the session is logged with 0 energy.
        double energy = meterStop == null ? 0 : Math.max(0, meterStop - open.meterStart());
        CpmsUser user = userForCard(open.idTag());
        List<CpmsTransaction> log = transactions();
        log.add(new CpmsTransaction(open.idTag(), user == null ? null : user.id(), open.chargePointId(),
                open.connectorId(), open.startEpoch(), stopEpoch, energy));
        storage.put(KEY_TRANSACTIONS, gson.toJson(log));
    }

    public synchronized List<CpmsTransaction> transactions() {
        String json = storage.get(KEY_TRANSACTIONS);
        CpmsTransaction @Nullable [] arr = json == null ? null : gson.fromJson(json, CpmsTransaction[].class);
        return arr == null ? new ArrayList<>() : new ArrayList<>(List.of(arr));
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

    private record OpenTx(String idTag, String chargePointId, int connectorId, int meterStart, long startEpoch) {
    }
}
