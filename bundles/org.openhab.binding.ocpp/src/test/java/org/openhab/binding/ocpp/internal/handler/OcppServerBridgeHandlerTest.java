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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.openhab.binding.ocpp.internal.OcppBindingConstants.*;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.ocpp.internal.OcppBindingConfig;
import org.openhab.binding.ocpp.internal.cpms.CpmsService;
import org.openhab.binding.ocpp.internal.discovery.OcppDiscoveryService;
import org.openhab.binding.ocpp.internal.transport.Ocpp16Events;
import org.openhab.binding.ocpp.internal.transport.OcppTransport;
import org.openhab.binding.ocpp.internal.transport.event.OcppVersion;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;
import org.openhab.binding.ocpp.internal.transport.event.TransactionEvent;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.storage.Storage;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
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
@SuppressWarnings("null")
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
            this(bridge, storageService, injected, mock(ConfigurationAdmin.class), bindingProperties);
        }

        TestableBridgeHandler(Bridge bridge, StorageService storageService, OcppTransport injected,
                ConfigurationAdmin configAdmin, @Nullable Map<String, Object> bindingProperties) {
            this(bridge, storageService, injected, configAdmin, bindingProperties, mock(ItemRegistry.class));
        }

        TestableBridgeHandler(Bridge bridge, StorageService storageService, OcppTransport injected,
                ConfigurationAdmin configAdmin, @Nullable Map<String, Object> bindingProperties,
                ItemRegistry itemRegistry) {
            super(bridge, storageService, new OcppBindingConfig(configAdmin, bindingProperties), itemRegistry);
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
    private @NonNullByDefault({}) MemoryStorage storage;

    @BeforeEach
    void setUp() {
        transport = mock(OcppTransport.class);

        storageService = mock(StorageService.class);
        storage = new MemoryStorage();
        when(storageService.<String> getStorage(anyString())).thenReturn(storage);

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
    void theLocalAuthListVersionOnlyMovesWhenTheListContentDoes() {
        handler.initialize();

        assertEquals(1, handler.localAuthListVersion("charx", List.of("A", "B")));
        assertEquals(1, handler.localAuthListVersion("charx", List.of("B", "A")));
        assertEquals(2, handler.localAuthListVersion("charx", List.of("A", "C")));
        assertEquals(1, handler.localAuthListVersion("wallbox", List.of("A", "C")));

        TestableBridgeHandler afterRestart = new TestableBridgeHandler(thing, storageService, transport);
        afterRestart.setCallback(callback);
        afterRestart.initialize();
        assertEquals(2, afterRestart.localAuthListVersion("charx", List.of("A", "C")));
        assertEquals(3, afterRestart.localAuthListVersion("charx", List.of("A", "B")));
    }

    @Test
    void aPasswordOutsideTheProfile1WindowFailsInitialization() {
        // A configured password outside the 16-40 profile-1 window must fail config, not lock chargers out.
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
    void anUpdateWithoutATokenStillReachesTheChargePoint() {
        handler.initialize();
        verify(callback, timeout(2000)).statusUpdated(any(),
                argThat(status -> status.getStatus() == ThingStatus.ONLINE));
        OcppChargePointHandler chargePoint = mock(OcppChargePointHandler.class);
        handler.registerChargePoint("charx", chargePoint);
        UUID session = UUID.randomUUID();
        handler.onSessionOpened(session, "charx", null, OcppVersion.V2_0_1);
        TransactionEvent update = new TransactionEvent(TransactionEvent.Kind.UPDATED, 2, 5, "t1", null,
                TokenType.UNKNOWN, null, null, null, null);

        handler.onTransactionEvent(session, update);

        verify(chargePoint).onTransactionUpdated(update);
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
        handler.onTransactionEvent(session, Ocpp16Events
                .toStarted(new StartTransactionRequest(2, "tag", 0, ZonedDateTime.now(java.time.ZoneOffset.UTC)), 77));

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
        handler.onTransactionEvent(session, Ocpp16Events
                .toStarted(new StartTransactionRequest(2, "tag", 0, ZonedDateTime.now(java.time.ZoneOffset.UTC)), 77));
        assertEquals(Integer.valueOf(77), handler.openTransactionFor("charx", 2));

        handler.onTransactionEvent(session, Ocpp16Events
                .toEnded(new StopTransactionRequest(0, ZonedDateTime.now(java.time.ZoneOffset.UTC), 77), 77));

        org.junit.jupiter.api.Assertions.assertNull(handler.openTransactionFor("charx", 2),
                "a stop before the handler exists must clear the persisted transaction");
    }

    @Test
    void aStopTagIsNotLearnedButALaterTokenOnAnOwnerlessSessionIs() throws Exception {
        // On 1.6 every StopTransaction carries a tag, possibly one the binding refused at the start.
        ConfigurationAdmin configAdmin = mock(ConfigurationAdmin.class);
        org.osgi.service.cm.Configuration configuration = mock(org.osgi.service.cm.Configuration.class);
        java.util.Hashtable<String, Object> props = new java.util.Hashtable<>();
        props.put("whitelistTagIds", java.util.List.of("KNOWN"));
        when(configAdmin.getConfiguration(any(), any())).thenReturn(configuration);
        when(configuration.getProperties()).thenReturn(props);
        handler = new TestableBridgeHandler(thing, storageService, transport, configAdmin,
                Map.of("autoLearn", true, "whitelistTagIds", java.util.List.of("KNOWN")));
        handler.setCallback(callback);
        handler.initialize();
        verify(callback, timeout(2000)).statusUpdated(any(),
                argThat(status -> status.getStatus() == ThingStatus.ONLINE));
        UUID session = UUID.randomUUID();
        handler.onSessionOpened(session, "charx", null, OcppVersion.V1_6);

        handler.onTransactionEvent(session, Ocpp16Events.toStarted(
                new StartTransactionRequest(2, "KNOWN", 0, ZonedDateTime.now(java.time.ZoneOffset.UTC)), 77));
        StopTransactionRequest stop = new StopTransactionRequest(0, ZonedDateTime.now(java.time.ZoneOffset.UTC), 77);
        stop.setIdTag("STRANGER");
        handler.onTransactionEvent(session, Ocpp16Events.toEnded(stop, 77));
        verify(configuration, never()).update(any());

        handler.onTransactionEvent(session,
                new org.openhab.binding.ocpp.internal.transport.event.TransactionEvent(
                        org.openhab.binding.ocpp.internal.transport.event.TransactionEvent.Kind.UPDATED, 2, 78, null,
                        "PLUGFIRST", org.openhab.binding.ocpp.internal.transport.event.TokenType.UNKNOWN, null, null,
                        null, null));
        verify(configuration)
                .update(argThat(updated -> String.valueOf(updated.get("whitelistTagIds")).contains("PLUGFIRST")));
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
        handler.onTransactionEvent(session, Ocpp16Events
                .toStarted(new StartTransactionRequest(1, "tag", 0, ZonedDateTime.now(java.time.ZoneOffset.UTC)), 55));

        org.junit.jupiter.api.Assertions.assertNull(handler.openTransactionFor("", 1),
                "a session with no charge point id must be ignored, mapping nothing");
    }

    @Test
    void aCpmsUserWhoseHandlerHasNotAttachedYetClosesTheGate() {
        handler.initialize();
        assertTrue(handler.isTagAuthorized("ANY"), "with no CPMS user thing the binding whitelist decides");

        List<Thing> users = List.of(cpmsUserThing(true));
        when(thing.getThings()).thenReturn(users);

        assertFalse(handler.isTagAuthorized("ANY"),
                "an enabled user thing registers nobody until its handler attaches, and an empty registry must "
                        + "not fall back to a whitelist that accepts every tag");
    }

    @Test
    void aDisabledCpmsUserLeavesTheWhitelistInCharge() {
        handler.initialize();

        List<Thing> users = List.of(cpmsUserThing(false));
        when(thing.getThings()).thenReturn(users);

        assertTrue(handler.isTagAuthorized("ANY"),
                "disabling every user thing hands authorization back to the whitelist instead of refusing "
                        + "every tag binding-wide");
    }

    @Test
    void aConfiguredWhitelistStillDecidesWhileACpmsUserIsAttaching() {
        handler = new TestableBridgeHandler(thing, storageService, transport,
                Map.of("whitelistTagIds", List.of("KNOWN")));
        handler.setCallback(callback);
        handler.initialize();
        List<Thing> users = List.of(cpmsUserThing(true));
        when(thing.getThings()).thenReturn(users);

        assertTrue(handler.isTagAuthorized("KNOWN"));
        assertFalse(handler.isTagAuthorized("STRANGER"));
    }

    @Test
    void aKeystoreThatCannotBeLoadedIsAConfigurationError() throws Exception {
        Path keystore = Path.of(Objects.requireNonNull(getClass().getResource("/tls-test-keystore.p12")).toURI());
        when(thing.getConfiguration()).thenReturn(new Configuration(
                Map.of("tlsKeystorePath", keystore.toString(), "tlsKeystorePassword", "notthepassword")));
        // The real transport, so the keystore is actually read.
        OcppServerBridgeHandler real = new OcppServerBridgeHandler(thing, storageService,
                new OcppBindingConfig(mock(ConfigurationAdmin.class), null), mock(ItemRegistry.class));
        real.setCallback(callback);

        real.initialize();

        verify(callback).statusUpdated(any(), argThat(status -> status.getStatus() == ThingStatus.OFFLINE
                && status.getStatusDetail() == ThingStatusDetail.CONFIGURATION_ERROR));
    }

    @Test
    void aTransactionDroppedWithoutAStopLeavesNoPowerTally() throws Exception {
        Item meterItem = mock(Item.class);
        when(meterItem.getState()).thenReturn(new QuantityType<>("7 kW"));
        ItemRegistry itemRegistry = mock(ItemRegistry.class);
        when(itemRegistry.getItem("Car_Power")).thenReturn(meterItem);
        OcppChargePointHandler chargePoint = mock(OcppChargePointHandler.class);
        when(chargePoint.externalMeter(2))
                .thenReturn(new OcppChargePointHandler.ExternalMeter("Car_Power", true, false));
        handler = new TestableBridgeHandler(thing, storageService, transport, mock(ConfigurationAdmin.class), null,
                itemRegistry);
        handler.setCallback(callback);
        handler.initialize();
        handler.registerChargePoint("charx", chargePoint);
        UUID session = UUID.randomUUID();
        handler.onSessionOpened(session, "charx", null, OcppVersion.V1_6);
        handler.onTransactionEvent(session, new TransactionEvent(TransactionEvent.Kind.STARTED, 2, 77, "t1", "CARD",
                TokenType.CARD, 0, null, null, null));
        // One store stands in for all of them here, so the bare transaction id is the tally's key.
        assertTrue(storage.containsKey("77"));

        handler.forgetTransaction(77);

        assertFalse(storage.containsKey("77"), "a transaction dropped without a stop must not leave a tally that "
                + "keeps integrating the external meter");
    }

    @Test
    void aStopWhoseMeterNoLongerResolvesStillClosesThePowerTally() throws Exception {
        Item meterItem = mock(Item.class);
        when(meterItem.getState()).thenReturn(new QuantityType<>("7 kW"));
        ItemRegistry itemRegistry = mock(ItemRegistry.class);
        when(itemRegistry.getItem("Car_Power")).thenReturn(meterItem);
        OcppChargePointHandler chargePoint = mock(OcppChargePointHandler.class);
        // The connector handler goes away mid-session: the meter resolves at the start and not at the stop.
        when(chargePoint.externalMeter(2)).thenReturn(
                new OcppChargePointHandler.ExternalMeter("Car_Power", true, false),
                (OcppChargePointHandler.ExternalMeter) null);
        handler = new TestableBridgeHandler(thing, storageService, transport, mock(ConfigurationAdmin.class), null,
                itemRegistry);
        handler.setCallback(callback);
        handler.initialize();
        handler.registerChargePoint("charx", chargePoint);
        UUID session = UUID.randomUUID();
        handler.onSessionOpened(session, "charx", null, OcppVersion.V1_6);
        handler.onTransactionEvent(session, new TransactionEvent(TransactionEvent.Kind.STARTED, 2, 77, "t1", "CARD",
                TokenType.CARD, 0, null, null, null));

        handler.onTransactionEvent(session, new TransactionEvent(TransactionEvent.Kind.ENDED, 2, 77, "t1", "CARD",
                TokenType.CARD, 1_240_000, null, null, null));

        assertFalse(storage.containsKey("77"));
        CpmsService cpms = Objects.requireNonNull(handler.getCpms());
        assertEquals(1, cpms.transactions().size());
        assertEquals(0.0, cpms.transactions().get(0).energyWh(), 1.0,
                "the charger's own register must never be subtracted from an external-meter start");
    }

    @Test
    void aTallyWhoseTransactionIsOverIsNotRestored() {
        handler.initialize();
        storage.put("99", "{\"source\":\"POWER_ITEM\",\"itemName\":\"Car_Power\",\"kilo\":false,\"wh\":1234.0}");

        TestableBridgeHandler afterRestart = new TestableBridgeHandler(thing, storageService, transport);
        afterRestart.setCallback(callback);
        afterRestart.initialize();

        assertFalse(storage.containsKey("99"),
                "a tally whose transaction is no longer open must be dropped, not restored on every startup");
    }

    @Test
    void aTransactionDroppedWithoutAStopIsForgottenRatherThanLogged() {
        handler.initialize();
        UUID session = UUID.randomUUID();
        handler.onSessionOpened(session, "charx", null, OcppVersion.V1_6);
        handler.onTransactionEvent(session, new TransactionEvent(TransactionEvent.Kind.STARTED, 2, 77, "t1", "CARD",
                TokenType.CARD, 1000, null, null, null));
        assertTrue(storage.containsKey("open:77"));

        handler.forgetTransaction(77);

        assertFalse(storage.containsKey("open:77"),
                "a connector going available without a StopTransaction must drop the CPMS session, not leave "
                        + "its key behind for good");
        assertTrue(Objects.requireNonNull(handler.getCpms()).transactions().isEmpty(),
                "no stop reading can be attributed to it, so nothing may be logged for it");
    }

    @Test
    void aTransactionSupersededOnItsOwnConnectorIsForgottenRatherThanLeftOpen() {
        handler.initialize();
        UUID session = UUID.randomUUID();
        handler.onSessionOpened(session, "charx", null, OcppVersion.V1_6);
        handler.onTransactionEvent(session, new TransactionEvent(TransactionEvent.Kind.STARTED, 2, 77, "t1", "CARD",
                TokenType.CARD, 1000, null, null, null));

        handler.onTransactionEvent(session, new TransactionEvent(TransactionEvent.Kind.STARTED, 2, 78, "t2", "CARD",
                TokenType.CARD, 5000, null, null, null));

        assertFalse(storage.containsKey("open:77"), "a transaction superseded by a fresh start on its own "
                + "connector must be dropped, not left open in the CPMS for good");
        assertTrue(storage.containsKey("open:78"));
        assertTrue(Objects.requireNonNull(handler.getCpms()).transactions().isEmpty());
    }

    @Test
    void aTransactionDroppedAfterItsStopDoesNotLogTheSessionTwice() {
        handler.initialize();
        UUID session = UUID.randomUUID();
        handler.onSessionOpened(session, "charx", null, OcppVersion.V1_6);
        handler.onTransactionEvent(session, new TransactionEvent(TransactionEvent.Kind.STARTED, 2, 77, "t1", "CARD",
                TokenType.CARD, 0, null, null, null));
        handler.onTransactionEvent(session, new TransactionEvent(TransactionEvent.Kind.ENDED, 2, 77, "t1", "CARD",
                TokenType.CARD, 5000, null, null, null));

        handler.forgetTransaction(77);

        CpmsService cpms = Objects.requireNonNull(handler.getCpms());
        assertEquals(1, cpms.transactions().size(), "the charge point handler dropping a stopped transaction must "
                + "not log a second, empty session for it");
        assertEquals(5000.0, cpms.transactions().get(0).energyWh(), 1.0);
    }

    @Test
    void anEnergyMeterUnreadableAtTheStartIsNotSubtractedFromAReadableOneAtTheStop() throws Exception {
        Item meterItem = mock(Item.class);
        when(meterItem.getState()).thenReturn(UnDefType.UNDEF, new QuantityType<>("1240 kWh"));
        TestableBridgeHandler withMeter = energyMeterHandler(meterItem);

        assertEquals(5000.0, sessionEnergyWh(withMeter, 0, 5000), 1.0,
                "a start that fell back to the charger's register must be closed against that same register");
    }

    @Test
    void anEnergyMeterUnreadableAtTheStopLogsNoEnergyRatherThanTheChargersRegister() throws Exception {
        Item meterItem = mock(Item.class);
        when(meterItem.getState()).thenReturn(new QuantityType<>("1000 kWh"), UnDefType.UNDEF);
        TestableBridgeHandler withMeter = energyMeterHandler(meterItem);

        assertEquals(0.0, sessionEnergyWh(withMeter, 0, 2_000_000), 1.0,
                "the charger's own register must never be subtracted from an external meter's start");
    }

    @Test
    void anEnergyMeterSubtractsWithinItsOwnRegister() throws Exception {
        Item meterItem = mock(Item.class);
        when(meterItem.getState()).thenReturn(new QuantityType<>("1000 kWh"), new QuantityType<>("1005 kWh"));
        TestableBridgeHandler withMeter = energyMeterHandler(meterItem);

        assertEquals(5000.0, sessionEnergyWh(withMeter, 0, 7000), 1.0);
    }

    @Test
    void aConnectorRepointedAtAnotherMeterMidSessionSubtractsNeither() throws Exception {
        Item started = mock(Item.class);
        when(started.getState()).thenReturn(new QuantityType<>("1000 kWh"));
        Item swapped = mock(Item.class);
        when(swapped.getState()).thenReturn(new QuantityType<>("9000 kWh"));
        ItemRegistry itemRegistry = mock(ItemRegistry.class);
        when(itemRegistry.getItem("Car_Energy")).thenReturn(started);
        when(itemRegistry.getItem("Other_Energy")).thenReturn(swapped);
        OcppChargePointHandler chargePoint = mock(OcppChargePointHandler.class);
        when(chargePoint.externalMeter(2)).thenReturn(
                new OcppChargePointHandler.ExternalMeter("Car_Energy", false, true),
                new OcppChargePointHandler.ExternalMeter("Other_Energy", false, true));

        assertEquals(0.0, sessionEnergyWh(meterHandler(itemRegistry, chargePoint), 0, 5000), 1.0,
                "a session started on one meter must not be closed against the register of another");
    }

    @Test
    void theMeterASessionStartedOnSurvivesARestart() throws Exception {
        Item meterItem = mock(Item.class);
        when(meterItem.getState()).thenReturn(new QuantityType<>("1000 kWh"), UnDefType.UNDEF);
        TestableBridgeHandler before = energyMeterHandler(meterItem);
        UUID session = UUID.randomUUID();
        before.onSessionOpened(session, "charx", null, OcppVersion.V1_6);
        before.onTransactionEvent(session, new TransactionEvent(TransactionEvent.Kind.STARTED, 2, 77, "t1", "CARD",
                TokenType.CARD, 0, null, null, null));

        TestableBridgeHandler afterRestart = energyMeterHandler(meterItem);
        UUID resumed = UUID.randomUUID();
        afterRestart.onSessionOpened(resumed, "charx", null, OcppVersion.V1_6);
        afterRestart.onTransactionEvent(resumed, new TransactionEvent(TransactionEvent.Kind.ENDED, 2, 77, "t1", "CARD",
                TokenType.CARD, 2_000_000, null, null, null));

        CpmsService cpms = Objects.requireNonNull(afterRestart.getCpms());
        assertEquals(1, cpms.transactions().size());
        assertEquals(0.0, cpms.transactions().get(0).energyWh(), 1.0,
                "a restart must not turn an external-meter session into a charger-register one");
    }

    @Test
    void theWarnOnceMemoryEvictsTheEldestKeyRatherThanForgettingEveryone() {
        OcppServerBridgeHandler.WarnOnce warned = new OcppServerBridgeHandler.WarnOnce();
        assertTrue(warned.first("misconfigured"));
        assertFalse(warned.first("misconfigured"));

        for (int i = 0; i < 63; i++) {
            assertTrue(warned.first("flood" + i));
        }
        assertFalse(warned.first("misconfigured"), "filling the memory must not make a known peer warn again");

        assertTrue(warned.first("flood63"));

        assertTrue(warned.first("misconfigured"),
                "the key pushed out by the flood is the eldest, so the newest peers stay remembered");
    }

    private Thing cpmsUserThing(boolean enabled) {
        Thing user = mock(Thing.class);
        when(user.getThingTypeUID()).thenReturn(THING_TYPE_CPMS_USER);
        when(user.isEnabled()).thenReturn(enabled);
        return user;
    }

    private TestableBridgeHandler energyMeterHandler(Item meterItem) throws Exception {
        ItemRegistry itemRegistry = mock(ItemRegistry.class);
        when(itemRegistry.getItem("Car_Energy")).thenReturn(meterItem);
        OcppChargePointHandler chargePoint = mock(OcppChargePointHandler.class);
        when(chargePoint.externalMeter(2))
                .thenReturn(new OcppChargePointHandler.ExternalMeter("Car_Energy", false, true));
        return meterHandler(itemRegistry, chargePoint);
    }

    private TestableBridgeHandler meterHandler(ItemRegistry itemRegistry, OcppChargePointHandler chargePoint) {
        TestableBridgeHandler withMeter = new TestableBridgeHandler(thing, storageService, transport,
                mock(ConfigurationAdmin.class), null, itemRegistry);
        withMeter.setCallback(callback);
        withMeter.initialize();
        withMeter.registerChargePoint("charx", chargePoint);
        return withMeter;
    }

    private static double sessionEnergyWh(TestableBridgeHandler withMeter, int startWh, int stopWh) {
        UUID session = UUID.randomUUID();
        withMeter.onSessionOpened(session, "charx", null, OcppVersion.V1_6);
        withMeter.onTransactionEvent(session, new TransactionEvent(TransactionEvent.Kind.STARTED, 2, 77, "t1", "CARD",
                TokenType.CARD, startWh, null, null, null));
        withMeter.onTransactionEvent(session, new TransactionEvent(TransactionEvent.Kind.ENDED, 2, 77, "t1", "CARD",
                TokenType.CARD, stopWh, null, null, null));
        CpmsService cpms = Objects.requireNonNull(withMeter.getCpms());
        assertEquals(1, cpms.transactions().size());
        return cpms.transactions().get(0).energyWh();
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
