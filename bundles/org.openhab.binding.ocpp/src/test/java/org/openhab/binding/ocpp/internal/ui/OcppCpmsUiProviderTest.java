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
package org.openhab.binding.ocpp.internal.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.openhab.binding.ocpp.internal.OcppBindingConstants.THING_TYPE_CHARGEPOINT;
import static org.openhab.binding.ocpp.internal.OcppBindingConstants.THING_TYPE_SERVER;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.binding.ocpp.internal.cpms.CpmsService;
import org.openhab.binding.ocpp.internal.cpms.CpmsUser;
import org.openhab.binding.ocpp.internal.handler.OcppServerBridgeHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.storage.Storage;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.ui.components.RootUIComponent;
import org.openhab.core.ui.components.UIComponent;

/**
 * Tests that the CPMS page provider serves a stable sidebar page.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings("null")
class OcppCpmsUiProviderTest {

    @Test
    void servesAnOverviewAndOnePagePerPersonBuiltFromTheLog() {
        CpmsService cpms = new CpmsService(new MemoryStorage());
        cpms.registerUser(new CpmsUser("ocpp:cpms-user:main:ann", "Ann", true, 100, List.of("CARD-A"), List.of()));
        cpms.registerUser(new CpmsUser("ocpp:cpms-user:main:bob", "Bob", false, 0, List.of(), List.of("AA:BB")));
        long now = System.currentTimeMillis();
        cpms.onTransactionStart(1, "CARD-A", "ACE1", 2, 0, now - 7_200_000L);
        cpms.onTransactionStop(1, 12_500, now - 3_600_000L);
        cpms.onTransactionStart(2, "AA:BB", "ACE2", 1, 0, now - 5_400_000L);
        cpms.onTransactionStop(2, 3_000, now - 1_800_000L);
        OcppServerBridgeHandler handler = mock(OcppServerBridgeHandler.class);
        when(handler.getCpms()).thenReturn(cpms);
        Thing server = mock(Thing.class);
        when(server.getThingTypeUID()).thenReturn(THING_TYPE_SERVER);
        when(server.getHandler()).thenReturn(handler);
        Thing charger = mock(Thing.class);
        when(charger.getThingTypeUID()).thenReturn(THING_TYPE_CHARGEPOINT);
        when(charger.getLabel()).thenReturn("Charger 1");
        when(charger.getConfiguration()).thenReturn(new Configuration(Map.of("chargePointId", "ACE1")));
        ThingRegistry registry = mock(ThingRegistry.class);
        when(registry.getAll()).thenReturn(List.of(server, charger));

        OcppCpmsUiProvider provider = new OcppCpmsUiProvider(registry);
        try {
            Map<String, RootUIComponent> pages = new HashMap<>();
            provider.getAll().forEach(page -> pages.put(page.getUID(), page));
            assertEquals(Set.of("ocpp_cpms", "ocpp_user_ocpp_cpms_user_main_ann", "ocpp_user_ocpp_cpms_user_main_bob"),
                    pages.keySet());
            RootUIComponent overview = pages.get("ocpp_cpms");
            assertEquals(Boolean.TRUE, overview.getConfig().get("sidebar"));
            assertEquals(Boolean.FALSE, pages.get("ocpp_user_ocpp_cpms_user_main_ann").getConfig().get("sidebar"));

            // The monthly chart stacks one bar series per person over the last twelve months.
            List<UIComponent> series = find(overview, "oh-data-series");
            UIComponent ann = series.stream().filter(c -> "Ann".equals(c.getConfig().get("name"))).findFirst()
                    .orElseThrow();
            @SuppressWarnings("unchecked")
            List<List<Object>> data = (List<List<Object>>) ann.getConfig().get("data");
            assertEquals(12, data.size());
            assertEquals(12.5, data.get(11).get(1));
            assertTrue(data.get(11).get(0) instanceof String);

            // Each person in the list opens their own page; a charger is named by its Thing.
            List<UIComponent> items = find(overview, "oh-list-item");
            assertTrue(items.stream()
                    .anyMatch(i -> "page:ocpp_user_ocpp_cpms_user_main_ann".equals(i.getConfig().get("actionPage"))));
            assertTrue(items.stream().anyMatch(i -> "disabled".equals(i.getConfig().get("badge"))));
            assertTrue(items.stream().anyMatch(i -> "Charger 1".equals(i.getConfig().get("title"))));
            assertTrue(items.stream().anyMatch(i -> "ACE2".equals(i.getConfig().get("title"))));
        } finally {
            provider.deactivate();
        }
    }

    private static List<UIComponent> find(UIComponent root, String type) {
        List<UIComponent> found = new ArrayList<>();
        if (type.equals(root.getType())) {
            found.add(root);
        }
        Map<String, List<UIComponent>> slots = root.getSlots();
        if (slots != null) {
            slots.values().forEach(slot -> slot.forEach(child -> found.addAll(find(child, type))));
        }
        return found;
    }

    @Test
    void servesNoPageUntilThereAreUsers() {
        ThingRegistry registry = mock(ThingRegistry.class);
        when(registry.getAll()).thenReturn(List.of());

        OcppCpmsUiProvider provider = new OcppCpmsUiProvider(registry);
        try {
            assertEquals("ui:page", provider.getNamespace());
            assertTrue(provider.getAll().isEmpty());
        } finally {
            provider.deactivate();
        }
    }

    private static final class MemoryStorage implements Storage<String> {
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
