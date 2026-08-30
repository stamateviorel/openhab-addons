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
        cpms.putUser(new CpmsUser("u1", "Geert", true, 0, List.of("CARD-A")));
        cpms.putUser(new CpmsUser("u2", "Anna", false, 0, List.of("CARD-B")));

        assertEquals(Boolean.TRUE, cpms.authorize("CARD-A"));
        assertEquals(Boolean.FALSE, cpms.authorize("CARD-B"));
        assertEquals(Boolean.FALSE, cpms.authorize("CARD-X"));
    }

    @Test
    void aSessionIsLoggedWithTheResolvedUserAndEnergyAndSurvivesARestart() {
        cpms.putUser(new CpmsUser("u1", "Geert", true, 0, List.of("CARD-A")));
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
        cpms.putUser(new CpmsUser("u1", "Geert", true, 0, List.of("CARD-A")));
        cpms.removeUser("u1");

        assertNull(cpms.authorize("CARD-A"));
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
}
