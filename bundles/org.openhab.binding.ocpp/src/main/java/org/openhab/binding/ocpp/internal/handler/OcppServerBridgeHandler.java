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

import static org.openhab.binding.ocpp.internal.OcppBindingConstants.THING_TYPE_CPMS_USER;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ocpp.internal.OcppBindingConfig;
import org.openhab.binding.ocpp.internal.config.OcppServerConfiguration;
import org.openhab.binding.ocpp.internal.cpms.CpmsService;
import org.openhab.binding.ocpp.internal.discovery.OcppDiscoveryService;
import org.openhab.binding.ocpp.internal.transport.ChargeTimeTransport;
import org.openhab.binding.ocpp.internal.transport.OcppServerListener;
import org.openhab.binding.ocpp.internal.transport.OcppTransport;
import org.openhab.binding.ocpp.internal.transport.TransactionStore;
import org.openhab.binding.ocpp.internal.transport.event.BootInfo;
import org.openhab.binding.ocpp.internal.transport.event.MeterSample;
import org.openhab.binding.ocpp.internal.transport.event.OcppVersion;
import org.openhab.binding.ocpp.internal.transport.event.StatusInfo;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;
import org.openhab.binding.ocpp.internal.transport.event.TransactionEvent;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.storage.Storage;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * Owns the OCPP JSON WebSocket endpoint and routes inbound traffic to the matching
 * {@link OcppChargePointHandler}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class OcppServerBridgeHandler extends BaseBridgeHandler implements OcppServerListener {

    private final Logger logger = LoggerFactory.getLogger(OcppServerBridgeHandler.class);
    private final WarnOnce missingItemsWarned = new WarnOnce();
    // A charger the server turns away reconnects on its own timer forever; warn once per peer.
    private final WarnOnce rejectedChargersWarned = new WarnOnce();
    private final WarnOnce anonymousPeersWarned = new WarnOnce();

    private static final long POWER_SAMPLE_SECONDS = 30;
    private static final String AUTH_LIST_VERSION_PREFIX = "authListVersion:";
    private static final String AUTH_LIST_CONTENT_PREFIX = "authList:";

    private final Map<UUID, String> sessionChargePoints = new ConcurrentHashMap<>();
    private final Map<UUID, OcppVersion> sessionVersions = new ConcurrentHashMap<>();
    private final Map<String, OcppChargePointHandler> chargePoints = new ConcurrentHashMap<>();
    private final Map<Integer, PowerTally> powerTallies = new ConcurrentHashMap<>();
    // Only sessions measured by an item are listed; no entry means the charger's own register.
    private final Map<Integer, SessionMeter> meterSources = new ConcurrentHashMap<>();
    private volatile @Nullable ScheduledFuture<?> powerSampler;
    private final Gson gson = new Gson();
    private volatile @Nullable Storage<String> powerStore;

    private final Object lifecycleLock = new Object();
    // Dedicated lock: the base class synchronizes on the handler monitor.
    private final Object authListLock = new Object();
    private volatile boolean disposed;
    private long lifecycleGeneration;
    private volatile @Nullable Future<?> startupTask;

    private final StorageService storageService;
    private final OcppBindingConfig bindingConfig;
    private final ItemRegistry itemRegistry;
    private volatile @Nullable TransactionStore transactionStore;
    private volatile @Nullable Storage<String> bridgeStore;
    private volatile @Nullable CpmsService cpms;

    private volatile @Nullable OcppTransport transport;
    private volatile @Nullable OcppDiscoveryService discoveryService;
    private volatile OcppServerConfiguration config = new OcppServerConfiguration();

    public OcppServerBridgeHandler(Bridge bridge, StorageService storageService, OcppBindingConfig bindingConfig,
            ItemRegistry itemRegistry) {
        super(bridge);
        this.storageService = storageService;
        this.bindingConfig = bindingConfig;
        this.itemRegistry = itemRegistry;
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(OcppDiscoveryService.class);
    }

    public void setDiscoveryService(@Nullable OcppDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    public OcppServerConfiguration getServerConfig() {
        return config;
    }

    @Override
    public void initialize() {
        config = getConfigAs(OcppServerConfiguration.class);
        OcppServerConfiguration localConfig = config;
        if (!localConfig.authPassword.isEmpty() && !localConfig.authPassword.matches("[\\x21-\\x7E]{16,40}")) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.configuration-error.auth-password-length");
            return;
        }
        disposed = false;
        Storage<String> storage = storageService.getStorage(getThing().getUID().getAsString());
        bridgeStore = storage;
        transactionStore = new TransactionStore(storage);
        cpms = new CpmsService(storageService.getStorage(getThing().getUID().getAsString() + ":cpms"));
        powerStore = storageService.getStorage(getThing().getUID().getAsString() + ":power");
        reloadMeterSources();
        updateStatus(ThingStatus.UNKNOWN);

        OcppTransport newTransport;
        try {
            // The TLS keystore is read by the constructor, so a bad path or password throws here.
            newTransport = createTransport(localConfig);
        } catch (RuntimeException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.configuration-error.transport [\"" + e.getMessage() + "\"]");
            return;
        }

        ScheduledFuture<?> previousSampler = powerSampler;
        if (previousSampler != null) {
            previousSampler.cancel(false);
        }
        powerSampler = scheduler.scheduleWithFixedDelay(this::samplePowerTallies, POWER_SAMPLE_SECONDS,
                POWER_SAMPLE_SECONDS, TimeUnit.SECONDS);
        long generation;
        synchronized (lifecycleLock) {
            if (disposed) {
                return;
            }
            generation = ++lifecycleGeneration;
            this.transport = newTransport;
        }

        startupTask = scheduler.submit(() -> {
            synchronized (lifecycleLock) {
                if (disposed || generation != lifecycleGeneration) {
                    return;
                }
            }
            try {
                newTransport.start(localConfig.host, localConfig.port);
            } catch (RuntimeException e) {
                logger.warn("Could not start the OCPP server on {}:{}", localConfig.host, localConfig.port, e);
                boolean current;
                synchronized (lifecycleLock) {
                    current = !disposed && generation == lifecycleGeneration;
                    if (generation == lifecycleGeneration) {
                        transport = null;
                    }
                }
                if (current) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "@text/offline.communication-error.server-start [\"" + e.getMessage() + "\"]");
                }
                return;
            }
            boolean adopted;
            synchronized (lifecycleLock) {
                adopted = !disposed && generation == lifecycleGeneration;
            }
            if (adopted) {
                updateStatus(ThingStatus.ONLINE);
            } else {
                newTransport.stop();
            }
        });
    }

    @Override
    public void dispose() {
        OcppTransport localTransport;
        synchronized (lifecycleLock) {
            disposed = true;
            lifecycleGeneration++;
            localTransport = transport;
            transport = null;
        }
        Future<?> task = startupTask;
        if (task != null) {
            task.cancel(true);
            startupTask = null;
        }
        if (localTransport != null) {
            localTransport.stop();
        }
        ScheduledFuture<?> sampler = powerSampler;
        if (sampler != null) {
            sampler.cancel(false);
            powerSampler = null;
        }
        powerTallies.clear();
        meterSources.clear();
        missingItemsWarned.clear();
        rejectedChargersWarned.clear();
        anonymousPeersWarned.clear();
        sessionChargePoints.clear();
        sessionVersions.clear();
        chargePoints.clear();
    }

    public @Nullable OcppTransport getTransport() {
        return transport;
    }

    protected OcppTransport createTransport(OcppServerConfiguration serverConfig) {
        return new ChargeTimeTransport(this, serverConfig.pingInterval, serverConfig.requestTimeoutSeconds,
                serverConfig.authPassword, serverConfig.tlsKeystorePath, serverConfig.tlsKeystorePassword);
    }

    public void registerChargePoint(String chargePointId, OcppChargePointHandler handler) {
        chargePoints.put(chargePointId, handler);
        for (Map.Entry<UUID, String> entry : sessionChargePoints.entrySet()) {
            if (chargePointId.equals(entry.getValue())) {
                handler.onConnected(entry.getKey(), sessionVersions.getOrDefault(entry.getKey(), OcppVersion.V1_6));
                return;
            }
        }
    }

    public void unregisterChargePoint(String chargePointId) {
        chargePoints.remove(chargePointId);
    }

    public void connectorDiscovered(String chargePointId, int connectorId) {
        OcppDiscoveryService discovery = discoveryService;
        OcppChargePointHandler handler = chargePoints.get(chargePointId);
        if (discovery != null && handler != null) {
            discovery.connectorDiscovered(handler.getThing().getUID(), chargePointId, connectorId);
        }
    }

    @Override
    public void onSessionOpened(UUID session, @Nullable String chargePointId, @Nullable InetSocketAddress remote,
            OcppVersion version) {
        if (chargePointId == null || chargePointId.isBlank()) {
            Object peer = remote != null ? remote : session;
            if (anonymousPeersWarned.first(peerKey(session, remote))) {
                logger.warn(
                        "Charger connected without a charge point id in its URL path and was ignored (connection {}); it must dial ws://<host>:{}/<chargePointId>, not the bare root",
                        peer, config.port);
            } else {
                logger.debug("Ignoring connection {} again: still no charge point id in its URL path", peer);
            }
            OcppTransport localTransport = transport;
            if (localTransport != null) {
                localTransport.closeSession(session);
            }
            return;
        }
        List<String> allowed = config.chargerIds;
        if (!allowed.isEmpty() && !allowed.contains(chargePointId)) {
            if (rejectedChargersWarned.first(chargePointId)) {
                logger.warn("Rejecting charger '{}' — not in the permitted chargers list", chargePointId);
            } else {
                logger.debug("Rejecting charger '{}' again — not in the permitted chargers list", chargePointId);
            }
            OcppTransport localTransport = transport;
            if (localTransport != null) {
                localTransport.closeSession(session);
            }
            return;
        }
        List<UUID> staleSessions = new ArrayList<>();
        sessionChargePoints.entrySet().removeIf(entry -> {
            if (chargePointId.equals(entry.getValue()) && !session.equals(entry.getKey())) {
                staleSessions.add(entry.getKey());
                return true;
            }
            return false;
        });
        sessionChargePoints.put(session, chargePointId);
        sessionVersions.put(session, version);
        OcppTransport localTransport = transport;
        if (localTransport != null) {
            for (UUID stale : staleSessions) {
                localTransport.closeSession(stale);
            }
        }
        logger.debug("Charger connected: id={} session={} from={} ({})", chargePointId, session, remote, version);
        OcppChargePointHandler handler = chargePoints.get(chargePointId);
        if (handler != null) {
            handler.onConnected(session, version);
        } else {
            OcppDiscoveryService discovery = discoveryService;
            if (discovery != null) {
                discovery.chargePointDiscovered(chargePointId);
            }
        }
    }

    @Override
    public void onSessionClosed(UUID session) {
        sessionVersions.remove(session);
        String chargePointId = sessionChargePoints.remove(session);
        if (chargePointId != null) {
            OcppChargePointHandler handler = chargePoints.get(chargePointId);
            if (handler != null) {
                handler.onDisconnected(session);
            }
        }
    }

    @Override
    public void onBootNotification(UUID session, BootInfo boot) {
        OcppChargePointHandler handler = resolve(session);
        if (handler != null) {
            handler.onBootNotification(boot);
        }
    }

    @Override
    public void onBootConfirmationSent(UUID session) {
        OcppChargePointHandler handler = resolve(session);
        if (handler != null) {
            handler.onBootConfirmationSent(session);
        }
    }

    @Override
    public void onStatusNotification(UUID session, StatusInfo status) {
        OcppChargePointHandler handler = resolve(session);
        if (handler != null) {
            handler.onStatusNotification(status);
        }
    }

    @Override
    public void onAuthorize(UUID session, @Nullable String idToken, TokenType type) {
        // An Authorize names no connector in either protocol; only a transaction says where.
        enrollToken(session, idToken, type, null);
    }

    /** A charger may start a transaction without an Authorize (local list, AutoCharge), so this runs on both. */
    private void enrollToken(UUID session, @Nullable String idToken, TokenType type, @Nullable Integer connectorId) {
        if (idToken == null) {
            return;
        }
        if (bindingConfig.isAutoLearn() && !bindingConfig.getWhitelist().contains(idToken)) {
            bindingConfig.addToWhitelist(idToken);
        } else if (bindingConfig.isDiscoverCards()) {
            CpmsService service = cpms;
            OcppDiscoveryService discovery = discoveryService;
            if (service != null && discovery != null && service.userForCard(idToken) == null) {
                discovery.tokenDiscovered(idToken, type, whereOf(session, connectorId));
            }
        }
    }

    @Override
    public void onMeterValues(UUID session, MeterSample sample) {
        OcppChargePointHandler handler = resolve(session);
        if (handler != null) {
            handler.onMeterValues(sample);
        }
    }

    @Override
    public void onCapabilities(UUID session, Map<String, String> configurationKeys) {
        OcppChargePointHandler handler = resolve(session);
        if (handler != null) {
            handler.onCapabilities(configurationKeys);
        }
    }

    @Override
    public void onHeartbeat(UUID session) {
        OcppChargePointHandler handler = resolve(session);
        if (handler != null) {
            handler.onHeartbeat();
        }
    }

    @Override
    public void onTransactionEvent(UUID session, TransactionEvent event) {
        switch (event.kind()) {
            case STARTED -> onTransactionStarted(session, event);
            case ENDED -> onTransactionEnded(session, event);
            case UPDATED -> onTransactionUpdated(session, event);
        }
    }

    private void onTransactionStarted(UUID session, TransactionEvent event) {
        int transactionId = event.transactionId();
        enrollToken(session, event.idToken(), event.tokenType(), event.connectorId());
        String chargePointId = sessionChargePoints.get(session);
        Integer connectorId = event.connectorId();
        if (chargePointId != null && connectorId != null) {
            Integer superseded = openTransactionFor(chargePointId, connectorId);
            if (superseded != null && superseded != transactionId) {
                forgetTransaction(superseded);
            }
            rememberTransaction(transactionId, chargePointId, connectorId, event.remoteId(), event.meterWh());
        }
        CpmsService service = cpms;
        if (service != null && chargePointId != null && connectorId != null) {
            OcppChargePointHandler.ExternalMeter meter = externalMeterFor(chargePointId, connectorId);
            Integer meterStart;
            if (meter == null) {
                meterStart = event.meterWh();
            } else if (meter.power()) {
                startPowerTally(transactionId, meter);
                meterStart = 0;
            } else {
                Integer reading = energyMeterWh(meter);
                if (reading != null) {
                    startEnergyMeter(transactionId, meter);
                    meterStart = reading;
                } else {
                    meterStart = event.meterWh();
                }
            }
            service.onTransactionStart(transactionId, event.idToken(), chargePointId, connectorId, meterStart,
                    epochOf(event.timestamp()));
        }
        OcppChargePointHandler handler = chargePointId != null ? chargePoints.get(chargePointId) : null;
        if (handler != null) {
            handler.onTransactionStarted(event);
        }
    }

    private void onTransactionUpdated(UUID session, TransactionEvent event) {
        adoptToken(session, event);
        OcppChargePointHandler handler = resolve(session);
        if (handler != null) {
            handler.onTransactionUpdated(event);
        }
    }

    private void adoptToken(UUID session, TransactionEvent event) {
        String idToken = event.idToken();
        if (idToken == null) {
            return;
        }
        CpmsService service = cpms;
        boolean adopted = service != null && service.onTransactionAuthorized(event.transactionId(), idToken);
        // A 1.6 StopTransaction.req idTag may be one the binding refused at the start.
        if (event.kind() != TransactionEvent.Kind.ENDED || adopted) {
            enrollToken(session, idToken, event.tokenType(), event.connectorId());
        }
    }

    private void onTransactionEnded(UUID session, TransactionEvent event) {
        int transactionId = event.transactionId();
        CpmsService service = cpms;
        adoptToken(session, event);
        if (service != null) {
            Integer meterStop = finishMeter(transactionId, event.meterWh(), sessionChargePoints.get(session));
            service.onTransactionStop(transactionId, meterStop, epochOf(event.timestamp()));
        }
        OcppChargePointHandler handler = resolve(session);
        if (handler != null) {
            handler.onTransactionEnded(event);
            return;
        }
        String chargePointId = sessionChargePoints.get(session);
        if (chargePointId != null && transactionConnector(transactionId, chargePointId) != null) {
            forgetTransaction(transactionId);
        }
    }

    /** {@code UNKNOWN} without a CPMS; only a CPMS user can mark a token as a vehicle. */
    public TokenType tokenTypeOf(String token) {
        CpmsService service = cpms;
        return service == null ? TokenType.UNKNOWN : service.tokenTypeOf(token);
    }

    private String whereOf(UUID session, @Nullable Integer connectorId) {
        String chargePointId = sessionChargePoints.get(session);
        OcppChargePointHandler handler = chargePointId == null ? null : chargePoints.get(chargePointId);
        String label = handler == null ? null : handler.getThing().getLabel();
        String where = label != null && !label.isBlank() ? label
                : chargePointId != null ? chargePointId : "unknown charger";
        return connectorId == null || connectorId == 0 ? where : where + " connector " + connectorId;
    }

    @Override
    public boolean isTagAuthorized(@Nullable String idTag) {
        CpmsService service = cpms;
        if (service != null) {
            Boolean decision = service.authorize(idTag);
            if (decision != null) {
                return decision;
            }
        }
        List<String> whitelist = bindingConfig.getWhitelist();
        if (!whitelist.isEmpty()) {
            return idTag != null && whitelist.contains(idTag);
        }
        return !hasEnabledCpmsUsers();
    }

    /**
     * Whether the CPMS is meant to be deciding. An enabled user thing registers nobody until its handler
     * attaches, and an empty whitelist accepts every tag, so that window must not read as "no CPMS here".
     */
    private boolean hasEnabledCpmsUsers() {
        return getThing().getThings().stream()
                .anyMatch(child -> THING_TYPE_CPMS_USER.equals(child.getThingTypeUID()) && child.isEnabled());
    }

    public @Nullable CpmsService getCpms() {
        return cpms;
    }

    private OcppChargePointHandler.@Nullable ExternalMeter externalMeterFor(String chargePointId, int connectorId) {
        OcppChargePointHandler handler = chargePoints.get(chargePointId);
        return handler == null ? null : handler.externalMeter(connectorId);
    }

    private @Nullable State meterState(String itemName) {
        try {
            return itemRegistry.getItem(itemName).getState();
        } catch (ItemNotFoundException e) {
            if (missingItemsWarned.first(itemName)) {
                logger.warn("External energy item {} not found; falling back to the OCPP meter", itemName);
            }
            return null;
        }
    }

    private @Nullable Integer energyMeterWh(OcppChargePointHandler.ExternalMeter meter) {
        State state = meterState(meter.itemName());
        return state == null ? null : energyReadingWh(state, meter.kilo());
    }

    static @Nullable Integer energyReadingWh(State state, boolean kilo) {
        if (state instanceof QuantityType<?> quantity) {
            QuantityType<?> wh = quantity.toUnit(Units.WATT_HOUR);
            return wh == null ? null : (int) Math.round(wh.doubleValue());
        }
        if (state instanceof DecimalType number) {
            return (int) Math.round(number.doubleValue() * (kilo ? 1000.0 : 1.0));
        }
        return null;
    }

    static @Nullable Double powerReadingW(State state, boolean kilo) {
        if (state instanceof QuantityType<?> quantity) {
            QuantityType<?> watts = quantity.toUnit(Units.WATT);
            return watts == null ? null : watts.doubleValue();
        }
        if (state instanceof DecimalType number) {
            return number.doubleValue() * (kilo ? 1000.0 : 1.0);
        }
        return null;
    }

    private void startPowerTally(int transactionId, OcppChargePointHandler.ExternalMeter meter) {
        PowerTally tally = new PowerTally(meter.itemName(), meter.kilo(), System.currentTimeMillis(), 0);
        powerTallies.put(transactionId, tally);
        meterSources.put(transactionId, new SessionMeter(MeterSource.POWER_ITEM, meter.itemName()));
        persistMeter(transactionId, tally.persisted());
    }

    private void startEnergyMeter(int transactionId, OcppChargePointHandler.ExternalMeter meter) {
        meterSources.put(transactionId, new SessionMeter(MeterSource.ENERGY_ITEM, meter.itemName()));
        persistMeter(transactionId, new PersistedMeter(MeterSource.ENERGY_ITEM, meter.itemName(), meter.kilo(), 0));
    }

    private void samplePowerTallies() {
        long now = System.currentTimeMillis();
        try {
            for (Map.Entry<Integer, PowerTally> entry : powerTallies.entrySet()) {
                entry.getValue().accumulate(now, this);
                persistMeter(entry.getKey(), entry.getValue().persisted());
            }
        } catch (RuntimeException e) {
            // Letting it out would cancel the repeating task, so metering would stop for good.
            logger.warn("Could not sample the external power meters: {}", e.getMessage());
        }
    }

    private @Nullable PowerTally removeMeter(int transactionId) {
        meterSources.remove(transactionId);
        PowerTally tally = powerTallies.remove(transactionId);
        Storage<String> store = powerStore;
        if (store != null) {
            store.remove(String.valueOf(transactionId));
        }
        return tally;
    }

    /** The stop reading of a session, from the source its start came from; {@code null} if that one cannot be read. */
    private @Nullable Integer finishMeter(int transactionId, @Nullable Integer chargerWh,
            @Nullable String chargePointId) {
        SessionMeter started = meterSources.get(transactionId);
        PowerTally tally = removeMeter(transactionId);
        if (started == null) {
            return chargerWh;
        }
        if (started.source() == MeterSource.POWER_ITEM) {
            return tally == null ? null : (int) Math.round(tally.finish(System.currentTimeMillis(), this));
        }
        if (chargePointId == null) {
            return null;
        }
        Integer connectorId = transactionConnector(transactionId, chargePointId);
        OcppChargePointHandler.ExternalMeter meter = connectorId == null ? null
                : externalMeterFor(chargePointId, connectorId);
        return meter != null && !meter.power() && meter.itemName().equals(started.itemName()) ? energyMeterWh(meter)
                : null;
    }

    private void persistMeter(int transactionId, PersistedMeter meter) {
        Storage<String> store = powerStore;
        if (store != null) {
            store.put(String.valueOf(transactionId), gson.toJson(meter));
        }
    }

    private void reloadMeterSources() {
        Storage<String> store = powerStore;
        TransactionStore transactions = transactionStore;
        if (store == null || transactions == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String key : new ArrayList<>(store.getKeys())) {
            String json = store.get(key);
            if (json == null) {
                continue;
            }
            try {
                Integer transactionId = Integer.valueOf(key);
                if (transactions.locate(transactionId) == null) {
                    store.remove(key);
                    continue;
                }
                PersistedMeter p = gson.fromJson(json, PersistedMeter.class);
                if (p == null) {
                    continue;
                }
                MeterSource source = p.source();
                String itemName = p.itemName();
                if (source == null) {
                    store.remove(key);
                    continue;
                }
                meterSources.put(transactionId, new SessionMeter(source, itemName));
                if (source == MeterSource.POWER_ITEM && itemName != null) {
                    powerTallies.put(transactionId, new PowerTally(itemName, p.kilo(), now, p.wh()));
                }
            } catch (RuntimeException e) {
                logger.warn("Could not restore the meter of session {}: {}", key, e.getMessage());
            }
        }
    }

    private static final class PowerTally {
        private final String itemName;
        private final boolean kilo;
        private double wh;
        private long lastSampleMs;

        PowerTally(String itemName, boolean kilo, long startMs, double initialWh) {
            this.itemName = itemName;
            this.kilo = kilo;
            this.lastSampleMs = startMs;
            this.wh = initialWh;
        }

        // Synchronized: the 30s sampler and the inbound StopTransaction thread both reach a tally.
        synchronized void accumulate(long now, OcppServerBridgeHandler bridge) {
            State state = bridge.meterState(itemName);
            Double watts = state == null ? null : powerReadingW(state, kilo);
            if (watts != null) {
                wh += watts * (now - lastSampleMs) / 3_600_000.0;
            }
            lastSampleMs = now;
        }

        synchronized double finish(long now, OcppServerBridgeHandler bridge) {
            accumulate(now, bridge);
            return wh;
        }

        synchronized PersistedMeter persisted() {
            return new PersistedMeter(MeterSource.POWER_ITEM, itemName, kilo, wh);
        }
    }

    private enum MeterSource {
        ENERGY_ITEM,
        POWER_ITEM
    }

    private record SessionMeter(MeterSource source, @Nullable String itemName) {
    }

    private record PersistedMeter(@Nullable MeterSource source, @Nullable String itemName, boolean kilo, double wh) {
    }

    private static long epochOf(@Nullable ZonedDateTime timestamp) {
        return timestamp != null ? timestamp.toInstant().toEpochMilli() : System.currentTimeMillis();
    }

    @Override
    public int heartbeatFor(UUID session) {
        OcppChargePointHandler handler = resolve(session);
        int override = handler != null ? handler.getHeartbeatOverride() : 0;
        return override > 0 ? override : config.heartbeatInterval;
    }

    @Override
    public int nextTransactionId() {
        TransactionStore store = transactionStore;
        return store != null ? store.nextTransactionId() : 0;
    }

    /** Whether a list has ever been sent to this charge point, which makes an empty one a deliberate clear. */
    public boolean hasProvisionedLocalAuthList(String chargePointId) {
        Storage<String> store = bridgeStore;
        return store != null && store.get(AUTH_LIST_VERSION_PREFIX + chargePointId) != null;
    }

    /** The SendLocalList version for {@code tags}: OCPP requires it to rise whenever the list content does. */
    public int localAuthListVersion(String chargePointId, List<String> tags) {
        Storage<String> store = bridgeStore;
        if (store == null) {
            return 1;
        }
        String content = String.join(",", tags.stream().sorted().toList());
        String versionKey = AUTH_LIST_VERSION_PREFIX + chargePointId;
        String contentKey = AUTH_LIST_CONTENT_PREFIX + chargePointId;
        synchronized (authListLock) {
            String stored = store.get(versionKey);
            int version = 0;
            if (stored != null) {
                try {
                    version = Integer.parseInt(stored);
                } catch (NumberFormatException e) {
                    logger.warn("Persisted local authorization list version '{}' for {} is not a number; numbering "
                            + "restarts at 1", stored, chargePointId);
                }
            }
            if (version > 0 && content.equals(store.get(contentKey))) {
                return version;
            }
            version++;
            store.put(versionKey, Integer.toString(version));
            store.put(contentKey, content);
            return version;
        }
    }

    public void rememberTransaction(int transactionId, String chargePointId, int connectorId, @Nullable String remoteId,
            @Nullable Integer meterStart) {
        TransactionStore store = transactionStore;
        if (store != null) {
            store.begin(transactionId, chargePointId, connectorId, remoteId, meterStart);
        }
    }

    public @Nullable Integer meterStartOf(int transactionId, String chargePointId) {
        TransactionStore store = transactionStore;
        TransactionStore.Location location = store == null ? null : store.locate(transactionId);
        return location != null && chargePointId.equals(location.chargePointId()) ? location.meterStart() : null;
    }

    @Override
    public @Nullable Integer knownTransactionId(UUID session, String remoteId) {
        TransactionStore store = transactionStore;
        String chargePointId = sessionChargePoints.get(session);
        return store != null && chargePointId != null ? store.byRemoteId(chargePointId, remoteId) : null;
    }

    @Override
    public @Nullable Integer knownConnector(UUID session, int transactionId) {
        String chargePointId = sessionChargePoints.get(session);
        return chargePointId == null ? null : transactionConnector(transactionId, chargePointId);
    }

    /**
     * Drops a transaction whose StopTransaction never arrived. Its storage goes, but no session is logged: the
     * binding has no stop reading it can defend, and the log gates the monthly caps.
     */
    public void forgetTransaction(int transactionId) {
        removeMeter(transactionId);
        CpmsService service = cpms;
        if (service != null) {
            service.forgetTransaction(transactionId);
        }
        releaseTransaction(transactionId);
    }

    /**
     * Ends the transaction but keeps what a StopTransaction would still need. A charger may report a connector
     * Available before it sends the stop, so the usage entry and the meter it was started against stay until the
     * stop arrives or the connector starts its next transaction.
     */
    public void releaseTransaction(int transactionId) {
        TransactionStore store = transactionStore;
        if (store != null) {
            store.end(transactionId);
        }
    }

    public @Nullable Integer transactionConnector(int transactionId, String chargePointId) {
        TransactionStore store = transactionStore;
        if (store == null) {
            return null;
        }
        TransactionStore.Location location = store.locate(transactionId);
        return location != null && chargePointId.equals(location.chargePointId()) ? location.connectorId() : null;
    }

    public @Nullable String remoteIdOf(int transactionId, String chargePointId) {
        TransactionStore store = transactionStore;
        TransactionStore.Location location = store == null ? null : store.locate(transactionId);
        return location != null && chargePointId.equals(location.chargePointId()) ? location.remoteId() : null;
    }

    public @Nullable Integer openTransactionFor(String chargePointId, int connectorId) {
        TransactionStore store = transactionStore;
        return store != null ? store.openTransaction(chargePointId, connectorId) : null;
    }

    /**
     * Warn-once memory. Its keys come from the peer — the address it dials from, or the charge point id it
     * picked — so it has to be bounded; the eldest key goes rather than the whole set, or one flood of
     * unknown peers makes every standing misconfiguration warn all over again.
     */
    static final class WarnOnce {
        private static final int CAPACITY = 64;

        private final Set<String> seen = new LinkedHashSet<>();

        synchronized boolean first(String key) {
            if (!seen.add(key)) {
                return false;
            }
            if (seen.size() > CAPACITY) {
                Iterator<String> eldest = seen.iterator();
                eldest.next();
                eldest.remove();
            }
            return true;
        }

        synchronized void clear() {
            seen.clear();
        }
    }

    /** The port changes on every reconnect, so a repeat offender is recognised by its address alone. */
    private static String peerKey(UUID session, @Nullable InetSocketAddress remote) {
        InetAddress address = remote == null ? null : remote.getAddress();
        return address != null ? address.getHostAddress() : session.toString();
    }

    private @Nullable OcppChargePointHandler resolve(UUID session) {
        String chargePointId = sessionChargePoints.get(session);
        return chargePointId != null ? chargePoints.get(chargePointId) : null;
    }
}
