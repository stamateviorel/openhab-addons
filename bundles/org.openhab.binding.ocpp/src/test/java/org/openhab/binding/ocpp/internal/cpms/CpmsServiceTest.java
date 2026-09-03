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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.core.storage.Storage;

/**
 * Tests the CPMS registry, person-based authorization and the transaction log.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class CpmsServiceTest {

    private final MemoryStorage storage = new MemoryStorage();
    private final CpmsService cpms = new CpmsService(storage);

    @Test
    void authorizationDefersToTheWhitelistWhenNoUsersAreDefined() {
        // null tells the binding to fall back to its own whitelist.
        assertNull(cpms.authorize("ANY"));
    }

    @Test
    void aCardOfAnEnabledUserIsAllowedAndAnUnknownOrDisabledOneIsNot() {
        cpms.registerUser(new CpmsUser("u1", "Geert", true, 0, List.of("CARD-A"), List.of()));
        cpms.registerUser(new CpmsUser("u2", "Anna", false, 0, List.of("CARD-B"), List.of()));

        assertEquals(Boolean.TRUE, cpms.authorize("CARD-A"));
        assertEquals(Boolean.FALSE, cpms.authorize("CARD-B"));
        assertEquals(Boolean.FALSE, cpms.authorize("CARD-X"));
    }

    @Test
    void aSessionIsLoggedWithTheResolvedUserAndEnergyAndSurvivesARestart() {
        cpms.registerUser(new CpmsUser("u1", "Geert", true, 0, List.of("CARD-A"), List.of()));
        cpms.onTransactionStart(7, "CARD-A", "charger3", 1, 1000, 100L);
        cpms.onTransactionStop(7, 7200, 200L);

        List<CpmsTransaction> log = new CpmsService(storage).transactions();
        assertEquals(1, log.size());
        CpmsTransaction tx = log.get(0);
        assertEquals("CARD-A", tx.idTag());
        assertEquals("u1", tx.userId());
        assertEquals("charger3", tx.chargePointId());
        assertEquals(6200.0, tx.energyWh());
        assertEquals(100L, tx.startEpoch());
        assertEquals(200L, tx.stopEpoch());
    }

    @Test
    void aStopWithoutAMatchingStartIsIgnored() {
        cpms.onTransactionStop(99, 5000, 200L);

        assertTrue(cpms.transactions().isEmpty());
    }

    @Test
    void aMeterlessSessionLogsZeroEnergy() {
        cpms.onTransactionStart(8, "CARD-A", "charx", 1, null, 100L);
        cpms.onTransactionStop(8, null, 200L);

        assertEquals(0.0, cpms.transactions().get(0).energyWh());
    }

    @Test
    void removingAUserDropsTheirAuthorization() {
        cpms.registerUser(new CpmsUser("u1", "Geert", true, 0, List.of("CARD-A"), List.of()));
        cpms.unregisterUser("u1");

        assertNull(cpms.authorize("CARD-A"));
    }

    @Test
    void usageSumsPerUserForTheMonthAndTheYear() {
        cpms.registerUser(new CpmsUser("u1", "Geert", true, 0, List.of("CARD-A"), List.of()));
        cpms.registerUser(new CpmsUser("u2", "Anna", true, 0, List.of("CARD-B"), List.of()));
        session(1, "CARD-A", 1_500L, 4000);
        session(2, "CARD-A", 6_000L, 2000);
        session(3, "CARD-B", 6_000L, 10000);

        List<CpmsService.Usage> usage = cpms.usage(5_000L, 1_000L, 10_000L);
        CpmsService.Usage geert = usage.stream().filter(u -> "u1".equals(u.user().id())).findFirst().orElseThrow();
        CpmsService.Usage anna = usage.stream().filter(u -> "u2".equals(u.user().id())).findFirst().orElseThrow();

        assertEquals(2.0, geert.monthKwh());
        assertEquals(6.0, geert.yearKwh());
        assertEquals(10.0, anna.monthKwh());
        assertEquals(10.0, anna.yearKwh());
    }

    @Test
    void recentTransactionsReturnsTheNewestFirst() {
        session(1, "CARD-A", 100L, 1000);
        session(2, "CARD-A", 200L, 2000);
        session(3, "CARD-A", 300L, 3000);

        List<CpmsTransaction> recent = cpms.recentTransactions(2);

        assertEquals(2, recent.size());
        assertEquals(300L, recent.get(0).stopEpoch());
        assertEquals(200L, recent.get(1).stopEpoch());
    }

    @Test
    void aCardIsBlockedOnceThisMonthReachesTheCap() {
        CpmsService capped = new CpmsService(storage,
                Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC));
        capped.registerUser(new CpmsUser("u1", "Geert", true, 10, List.of("CARD-A"), List.of()));

        cappedSession(capped, 1, "CARD-A", "2026-06-10T00:00:00Z", 6000);
        assertEquals(Boolean.TRUE, capped.authorize("CARD-A"));

        cappedSession(capped, 2, "CARD-A", "2026-06-12T00:00:00Z", 5000);
        assertEquals(Boolean.FALSE, capped.authorize("CARD-A"));
    }

    @Test
    void lastMonthUsageDoesNotCountAgainstThisMonthsCap() {
        CpmsService capped = new CpmsService(storage,
                Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC));
        capped.registerUser(new CpmsUser("u1", "Geert", true, 10, List.of("CARD-A"), List.of()));

        cappedSession(capped, 1, "CARD-A", "2026-05-20T00:00:00Z", 50000);

        assertEquals(Boolean.TRUE, capped.authorize("CARD-A"));
    }

    @Test
    void everySessionIsRetainedAndPastYearsStayComputable() {
        cpms.registerUser(new CpmsUser("u1", "Geert", true, 0, List.of("CARD-A"), List.of()));
        cappedSession(cpms, 1, "CARD-A", "2025-01-10T00:00:00Z", 3000);
        cappedSession(cpms, 2, "CARD-A", "2026-06-10T00:00:00Z", 4000);

        assertEquals(2, cpms.transactions().size());
        assertEquals(3.0, cpms.energyKwh("u1", ms("2025-01-01T00:00:00Z"), ms("2026-01-01T00:00:00Z")));
        assertEquals(4.0, cpms.energyKwh("u1", ms("2026-01-01T00:00:00Z"), ms("2027-01-01T00:00:00Z")));
    }

    @Test
    void anUnreadableLogIsNotOverwrittenByANewSession() {
        storage.put("transactions", "{not a valid transaction array");
        cpms.onTransactionStart(5, "CARD-A", "charger1", 1, 0, 100L);
        cpms.onTransactionStop(5, 5000, 200L);

        assertEquals("{not a valid transaction array", storage.get("transactions"));
    }

    private static long ms(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private void cappedSession(CpmsService svc, int transactionId, String card, String stopIso, int energyWh) {
        long stopEpoch = ms(stopIso);
        svc.onTransactionStart(transactionId, card, "charger1", 1, 0, stopEpoch - 1000);
        svc.onTransactionStop(transactionId, energyWh, stopEpoch);
    }

    private void session(int transactionId, String card, long stopEpoch, int energyWh) {
        cpms.onTransactionStart(transactionId, card, "charger1", 1, 0, stopEpoch - 1);
        cpms.onTransactionStop(transactionId, energyWh, stopEpoch);
    }

    private static class MemoryStorage implements Storage<String> {
        private final Map<String, String> map = new HashMap<>();

        @Override
        public @Nullable String put(String key, @Nullable String value) {
            return value == null ? map.remove(key) : map.put(key, value);
        }

        @Override
        public @Nullable String remove(String key) {
            return map.remove(key);
        }

        @Override
        public boolean containsKey(String key) {
            return map.containsKey(key);
        }

        @Override
        public @Nullable String get(String key) {
            return map.get(key);
        }

        @Override
        public Collection<String> getKeys() {
            return new HashSet<>(map.keySet());
        }

        @Override
        public Collection<@Nullable String> getValues() {
            return new ArrayList<>(map.values());
        }
    }

    @Test
    void aVehicleAuthorizesJustAsACardDoes() {
        // AutoCharge and plug-and-charge tokens are managed the same way as cards.
        cpms.registerUser(new CpmsUser("u1", "Stijn", true, 0, List.of("CARD1"), List.of("001122334455")));

        assertEquals(Boolean.TRUE, cpms.authorize("CARD1"));
        assertEquals(Boolean.TRUE, cpms.authorize("001122334455"));
        assertEquals(Boolean.FALSE, cpms.authorize("SOMEONE-ELSE"));
    }
}
