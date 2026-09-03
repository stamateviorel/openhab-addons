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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.openhab.binding.ocpp.internal.OcppBindingConstants.*;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.ocpp.internal.OcppBindingConfig;
import org.openhab.binding.ocpp.internal.discovery.OcppDiscoveryService;
import org.openhab.binding.ocpp.internal.transport.Ocpp16Events;
import org.openhab.binding.ocpp.internal.transport.OcppTransport;
import org.openhab.binding.ocpp.internal.transport.event.OcppVersion;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;
import org.openhab.binding.ocpp.internal.transport.event.TransactionEvent;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.storage.Storage;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.types.UnDefType;
import org.osgi.service.cm.ConfigurationAdmin;

import eu.chargetime.ocpp.model.core.StartTransactionRequest;
import eu.chargetime.ocpp.model.core.StopTransactionRequest;

/**
 * Tests session routing in {@link OcppServerBridgeHandler}, in particular that a charger reconnecting
 * under a fresh session id does not leave its previous socket open.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings({ "null", "unchecked" })
class OcppServerBridgeHandlerTest {

    private static final ThingUID SERVER_UID = new ThingUID(THING_TYPE_SERVER, "server");

    private @NonNullByDefault({}) OcppTransport transport;
    private @NonNullByDefault({}) ThingHandlerCallback callback;
    private @NonNullByDefault({}) TestableBridgeHandler handler;

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

    private static final class TestableBridgeHandler extends OcppServerBridgeHandler {
        private final OcppTransport injected;

        TestableBridgeHandler(Bridge bridge, StorageService storageService, OcppTransport injected) {
            this(bridge, storageService, injected, null);
        }

        TestableBridgeHandler(Bridge bridge, StorageService storageService, OcppTransport injected,
                @Nullable Map<String, Object> bindingProperties) {
            super(bridge, storageService, new OcppBindingConfig(mock(ConfigurationAdmin.class), bindingProperties),
                    mock(ItemRegistry.class));
            this.injected = injected;
        }

        @Override
        protected OcppTransport createTransport(
                org.openhab.binding.ocpp.internal.config.OcppServerConfiguration serverConfig) {
            return injected;
        }
    }

    private @NonNullByDefault({}) Bridge thing;
    private @NonNullByDefault({}) StorageService storageService;

    @BeforeEach
    void setUp() {
        transport = mock(OcppTransport.class);

        storageService = mock(StorageService.class);
        when(storageService.<String> getStorage(anyString())).thenReturn(new MemoryStorage());

        thing = mock(Bridge.class);
        when(thing.getUID()).thenReturn(SERVER_UID);
        when(thing.getConfiguration()).thenReturn(new Configuration());

        callback = mock(ThingHandlerCallback.class);

        handler = new TestableBridgeHandler(thing, storageService, transport);
        handler.setCallback(callback);
    }

    @Test
    void externalEnergyReadingsConvertToWattHours() {
        assertEquals(Integer.valueOf(5000), OcppServerBridgeHandler.energyReadingWh(new QuantityType<>("5 kWh"), true));
        assertEquals(Integer.valueOf(5000),
                OcppServerBridgeHandler.energyReadingWh(new QuantityType<>("5000 Wh"), false));
        assertEquals(Integer.valueOf(5000), OcppServerBridgeHandler.energyReadingWh(new DecimalType(5), true));
        assertEquals(Integer.valueOf(5000), OcppServerBridgeHandler.energyReadingWh(new DecimalType(5000), false));
        assertNull(OcppServerBridgeHandler.energyReadingWh(UnDefType.UNDEF, true));
        assertNull(OcppServerBridgeHandler.energyReadingWh(new QuantityType<>("5 W"), true));
    }

    @Test
    void externalPowerReadingsConvertToWatts() {
        assertEquals(Double.valueOf(7000.0), OcppServerBridgeHandler.powerReadingW(new QuantityType<>("7 kW"), true));
        assertEquals(Double.valueOf(7000.0),
                OcppServerBridgeHandler.powerReadingW(new QuantityType<>("7000 W"), false));
        assertEquals(Double.valueOf(7000.0), OcppServerBridgeHandler.powerReadingW(new DecimalType(7), true));
        assertEquals(Double.valueOf(7000.0), OcppServerBridgeHandler.powerReadingW(new DecimalType(7000), false));
        assertNull(OcppServerBridgeHandler.powerReadingW(UnDefType.UNDEF, false));
        assertNull(OcppServerBridgeHandler.powerReadingW(new QuantityType<>("7 kWh"), true));
    }

    @Test
    void aPasswordTheLibraryWouldRejectFailsInitializationInstead() {
        // The library only accepts 16-20 byte Basic-auth passwords; out-of-range must fail config, not lock them out.
        when(thing.getConfiguration()).thenReturn(new Configuration(java.util.Map.of("authPassword", "tooshort")));

        handler.initialize();

        verify(callback).statusUpdated(any(), argThat(status -> status.getStatus() == ThingStatus.OFFLINE
                && status.getStatusDetail() == org.openhab.core.thing.ThingStatusDetail.CONFIGURATION_ERROR));
        verify(transport, org.mockito.Mockito.after(500).never()).start(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aDiscoveredTokenSaysWhichChargerAndConnectorItCameFrom() {
        handler = new TestableBridgeHandler(thing, storageService, transport, Map.of("discoverCards", true));
        handler.setCallback(callback);
        handler.initialize();
        verify(callback, timeout(2000)).statusUpdated(any(),
                argThat(status -> status.getStatus() == ThingStatus.ONLINE));
        OcppDiscoveryService discovery = mock(OcppDiscoveryService.class);
        handler.setDiscoveryService(discovery);
        OcppChargePointHandler chargePoint = mock(OcppChargePointHandler.class);
        Bridge chargePointThing = mock(Bridge.class);
        when(chargePointThing.getLabel()).thenReturn("Charger 2");
        when(chargePoint.getThing()).thenReturn(chargePointThing);
        handler.registerChargePoint("charx", chargePoint);
        UUID session = UUID.randomUUID();
        handler.onSessionOpened(session, "charx", null, OcppVersion.V2_0_1);

        // An Authorize says nothing about the connector; a transaction does.
        handler.onAuthorize(session, "CARD-NEW", TokenType.CARD);
        verify(discovery).tokenDiscovered("CARD-NEW", TokenType.CARD, "Charger 2");

        handler.onTransactionEvent(session, new TransactionEvent(TransactionEvent.Kind.STARTED, 2, 5, "t1", "CARD-NEW",
                TokenType.CARD, null, null, null, null));
        verify(discovery).tokenDiscovered("CARD-NEW", TokenType.CARD, "Charger 2 connector 2");
    }

    @Test
    void aTransactionKeepsItsIdAcrossARestartByTheNameTheChargerGaveIt() {
        handler.initialize();
        verify(callback, timeout(2000)).statusUpdated(any(),
                argThat(status -> status.getStatus() == ThingStatus.ONLINE));
        UUID session = UUID.randomUUID();
        handler.onSessionOpened(session, "charx", null, OcppVersion.V2_0_1);
        handler.onTransactionEvent(session, new TransactionEvent(TransactionEvent.Kind.STARTED, 2, 77, "t1", "CARD",
                TokenType.CARD, null, null, null, null));

        OcppServerBridgeHandler restarted = new TestableBridgeHandler(thing, storageService, transport);
        restarted.setCallback(callback);
        restarted.initialize();
        UUID newSession = UUID.randomUUID();
        restarted.onSessionOpened(newSession, "charx", null, OcppVersion.V2_0_1);

        assertEquals(Integer.valueOf(77), restarted.knownTransactionId(newSession, "t1"));
        assertEquals(Integer.valueOf(2), restarted.knownConnector(newSession, 77));
    }

    @Test
    void aTransactionAcceptedBeforeItsHandlerExistsIsStillPersisted() {
        handler.initialize();
        verify(callback, timeout(2000)).statusUpdated(any(),
                argThat(status -> status.getStatus() == ThingStatus.ONLINE));

        UUID session = UUID.randomUUID();
        handler.onSessionOpened(session, "charx", null, OcppVersion.V1_6);
        handler.onTransactionEvent(session,
                Ocpp16Events.toStarted(new StartTransactionRequest(2, "tag", 0, ZonedDateTime.now()), 77));

        assertEquals(Integer.valueOf(77), handler.openTransactionFor("charx", 2),
                "the transaction must be recoverable even though no handler existed at accept time");
    }

    @Test
    void aTransactionStoppedBeforeItsHandlerExistsIsClearedFromTheStore() {
        handler.initialize();
        verify(callback, timeout(2000)).statusUpdated(any(),
                argThat(status -> status.getStatus() == ThingStatus.ONLINE));

        UUID session = UUID.randomUUID();
        handler.onSessionOpened(session, "charx", null, OcppVersion.V1_6);
        handler.onTransactionEvent(session,
                Ocpp16Events.toStarted(new StartTransactionRequest(2, "tag", 0, ZonedDateTime.now()), 77));
        assertEquals(Integer.valueOf(77), handler.openTransactionFor("charx", 2));

        handler.onTransactionEvent(session,
                Ocpp16Events.toEnded(new StopTransactionRequest(0, ZonedDateTime.now(), 77), 77));

        org.junit.jupiter.api.Assertions.assertNull(handler.openTransactionFor("charx", 2),
                "a stop before the handler exists must clear the persisted transaction");
    }

    @Test
    void aReconnectUnderANewSessionClosesTheOldSocket() {
        handler.initialize();
        verify(callback, timeout(2000)).statusUpdated(any(),
                argThat(status -> status.getStatus() == ThingStatus.ONLINE));

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        handler.onSessionOpened(first, "charx", null, OcppVersion.V1_6);
        handler.onSessionOpened(second, "charx", null, OcppVersion.V1_6);

        verify(transport).closeSession(first);
        verify(transport, never()).closeSession(second);
    }

    @Test
    void aSessionWithoutAChargePointIdIsIgnored() {
        // A bare-root connection (empty path) has no charge point id, so map/persist nothing (V2C Trydan).
        handler.initialize();
        verify(callback, timeout(2000)).statusUpdated(any(),
                argThat(status -> status.getStatus() == ThingStatus.ONLINE));

        UUID session = UUID.randomUUID();
        handler.onSessionOpened(session, "", null, OcppVersion.V1_6);
        handler.onTransactionEvent(session,
                Ocpp16Events.toStarted(new StartTransactionRequest(1, "tag", 0, ZonedDateTime.now()), 55));

        org.junit.jupiter.api.Assertions.assertNull(handler.openTransactionFor("", 1),
                "a session with no charge point id must be ignored, mapping nothing");
    }

    @Test
    void aBareRootConnectionWithoutAChargePointIdIsClosed() {
        handler.initialize();
        verify(callback, timeout(2000)).statusUpdated(any(),
                argThat(status -> status.getStatus() == ThingStatus.ONLINE));

        UUID session = UUID.randomUUID();
        handler.onSessionOpened(session, "", null, OcppVersion.V1_6);

        verify(transport).closeSession(session);
    }
}
