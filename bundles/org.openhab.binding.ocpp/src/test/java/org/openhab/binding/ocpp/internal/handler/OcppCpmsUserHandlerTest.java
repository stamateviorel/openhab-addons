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
package org.openhab.binding.ocpp.internal.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.openhab.binding.ocpp.internal.OcppBindingConstants.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.ocpp.internal.cpms.CpmsService;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.storage.Storage;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.types.RefreshType;

/**
 * Tests how the CPMS user Thing follows its bridge and publishes the person's energy.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings("null")
class OcppCpmsUserHandlerTest {

    private static final ThingUID THING_UID = new ThingUID(THING_TYPE_CPMS_USER, "server", "geert");
    private static final ThingUID BRIDGE_UID = new ThingUID(THING_TYPE_SERVER, "server");

    private @NonNullByDefault({}) CpmsService cpms;
    private @NonNullByDefault({}) ThingHandlerCallback callback;
    private @NonNullByDefault({}) OcppCpmsUserHandler handler;

    @BeforeEach
    void setUp() {
        cpms = new CpmsService(new MemoryStorage());
        Thing thing = mock(Thing.class);
        when(thing.getUID()).thenReturn(THING_UID);
        when(thing.getLabel()).thenReturn("Geert");
        when(thing.getBridgeUID()).thenReturn(BRIDGE_UID);
        when(thing.getConfiguration()).thenReturn(new Configuration(Map.of("cards", List.of("CARD-A"))));
        OcppServerBridgeHandler server = mock(OcppServerBridgeHandler.class);
        when(server.getCpms()).thenReturn(cpms);
        Bridge bridge = mock(Bridge.class);
        when(bridge.getHandler()).thenReturn(server);
        callback = mock(ThingHandlerCallback.class);
        when(callback.getBridge(BRIDGE_UID)).thenReturn(bridge);
        handler = new OcppCpmsUserHandler(thing);
        handler.setCallback(callback);
    }

    @AfterEach
    void tearDown() {
        handler.dispose();
    }

    @Test
    void theUsersEnergyIsPublishedOnRequest() {
        handler.initialize();
        chargedKwh(5);

        handler.handleCommand(new ChannelUID(THING_UID, CHANNEL_MONTH_ENERGY), RefreshType.REFRESH);

        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, CHANNEL_MONTH_ENERGY)),
                eq(new QuantityType<>(5.0, Units.KILOWATT_HOUR)));
        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, CHANNEL_YEAR_ENERGY)),
                eq(new QuantityType<>(5.0, Units.KILOWATT_HOUR)));
    }

    @Test
    void aBridgeGoingOfflineStopsPublishingWithoutDroppingTheAuthorization() {
        handler.initialize();
        clearInvocations(callback);

        handler.bridgeStatusChanged(
                new ThingStatusInfo(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, null));
        handler.handleCommand(new ChannelUID(THING_UID, CHANNEL_MONTH_ENERGY), RefreshType.REFRESH);

        verify(callback).statusUpdated(any(), argThat(status -> status.getStatus() == ThingStatus.OFFLINE
                && status.getStatusDetail() == ThingStatusDetail.BRIDGE_OFFLINE));
        verify(callback, never()).stateUpdated(any(), any());
        assertEquals(Boolean.TRUE, cpms.authorize("CARD-A"));
    }

    @Test
    void theBridgeComingBackOnlineResumesPublishing() {
        handler.initialize();
        handler.bridgeStatusChanged(new ThingStatusInfo(ThingStatus.OFFLINE, ThingStatusDetail.NONE, null));
        clearInvocations(callback);

        handler.bridgeStatusChanged(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));

        verify(callback).statusUpdated(any(), argThat(status -> status.getStatus() == ThingStatus.ONLINE));
        verify(callback).stateUpdated(eq(new ChannelUID(THING_UID, CHANNEL_MONTH_ENERGY)), any());
    }

    @Test
    void aUserRemovedWhileTheBridgeIsOfflineStopsBeingAuthorized() {
        handler.initialize();
        handler.bridgeStatusChanged(new ThingStatusInfo(ThingStatus.OFFLINE, ThingStatusDetail.NONE, null));

        handler.dispose();

        assertNull(cpms.authorize("CARD-A"));
    }

    /** A session that just ended, clamped into this month so both the month and the year window count it. */
    private void chargedKwh(int kwh) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        long monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.getZone()).toInstant().toEpochMilli();
        long stop = Math.max(now.toInstant().toEpochMilli() - 1000, monthStart + 1);
        cpms.onTransactionStart(1, "CARD-A", "charger", 1, 0, stop - 3_600_000L);
        cpms.onTransactionStop(1, kwh * 1000, stop);
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
