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

import static org.openhab.binding.ocpp.internal.OcppBindingConstants.*;

import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ocpp.internal.config.OcppConnectorConfiguration;
import org.openhab.binding.ocpp.internal.transport.ChargerCapabilities;
import org.openhab.binding.ocpp.internal.transport.MeterValueMapper;
import org.openhab.binding.ocpp.internal.transport.Ocpp16Commands;
import org.openhab.binding.ocpp.internal.transport.OcppCommands;
import org.openhab.binding.ocpp.internal.transport.event.ConnectorStatus;
import org.openhab.binding.ocpp.internal.transport.event.MeterSample;
import org.openhab.binding.ocpp.internal.transport.event.StatusInfo;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;
import org.openhab.binding.ocpp.internal.transport.event.TransactionEvent;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.chargetime.ocpp.model.core.ChangeConfigurationConfirmation;
import eu.chargetime.ocpp.model.core.ChangeConfigurationRequest;
import eu.chargetime.ocpp.model.core.ChargingRateUnitType;
import eu.chargetime.ocpp.model.core.ConfigurationStatus;

/**
 * Handles one connector (outlet) of a charger: status, metering and transaction channels plus the
 * control channels.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class OcppConnectorHandler extends BaseThingHandler {

    private static final EnumSet<ConnectorStatus> CABLE_PRESENT = EnumSet.of(ConnectorStatus.PREPARING,
            ConnectorStatus.CHARGING, ConnectorStatus.SUSPENDED_EV, ConnectorStatus.SUSPENDED_EVSE,
            ConnectorStatus.FINISHING);
    private static final EnumSet<ConnectorStatus> CHARGING_ACTIVE = EnumSet.of(ConnectorStatus.CHARGING,
            ConnectorStatus.SUSPENDED_EV, ConnectorStatus.SUSPENDED_EVSE);
    private static final EnumSet<ConnectorStatus> TRANSIENT = EnumSet.of(ConnectorStatus.PREPARING,
            ConnectorStatus.FINISHING);
    private static final long STUCK_STATE_SECONDS = 120;
    private static final long REMOTE_START_RETRY_DELAY_SECONDS = 5;

    private record DynamicChannel(String channelTypeId, String itemType, String label) {
    }

    private static final String ITEM_CURRENT = "Number:ElectricCurrent";
    private static final String ITEM_POWER = "Number:Power";
    private static final String ITEM_ENERGY = "Number:Energy";
    private static final String TYPE_ENERGY = "energy";
    private static final String TYPE_POWER_REACTIVE = "power-reactive";

    private static final Map<String, DynamicChannel> DYNAMIC_CHANNELS = Map.ofEntries(
            Map.entry(CHANNEL_CURRENT_IMPORT, new DynamicChannel("current-measure", ITEM_CURRENT, "Current Imported")),
            Map.entry(CHANNEL_CURRENT_EXPORT, new DynamicChannel("current-measure", ITEM_CURRENT, "Current Exported")),
            Map.entry(CHANNEL_VOLTAGE, new DynamicChannel("voltage-measure", "Number:ElectricPotential", "Voltage")),
            Map.entry(CHANNEL_FREQUENCY, new DynamicChannel("frequency", "Number:Frequency", "Frequency")),
            Map.entry(CHANNEL_POWER_ACTIVE_EXPORT,
                    new DynamicChannel("power-active", ITEM_POWER, "Active Power Exported")),
            Map.entry(CHANNEL_POWER_REACTIVE_IMPORT,
                    new DynamicChannel(TYPE_POWER_REACTIVE, ITEM_POWER, "Reactive Power Imported")),
            Map.entry(CHANNEL_POWER_REACTIVE_EXPORT,
                    new DynamicChannel(TYPE_POWER_REACTIVE, ITEM_POWER, "Reactive Power Exported")),
            Map.entry(CHANNEL_POWER_FACTOR, new DynamicChannel("power-factor", "Number", "Power Factor")),
            Map.entry(CHANNEL_ENERGY_ACTIVE_EXPORT,
                    new DynamicChannel(TYPE_ENERGY, ITEM_ENERGY, "Active Energy Exported")),
            Map.entry(CHANNEL_ENERGY_ACTIVE_IMPORT_INTERVAL,
                    new DynamicChannel(TYPE_ENERGY, ITEM_ENERGY, "Energy Import Interval")),
            Map.entry(CHANNEL_ENERGY_ACTIVE_EXPORT_INTERVAL,
                    new DynamicChannel(TYPE_ENERGY, ITEM_ENERGY, "Energy Export Interval")),
            Map.entry(CHANNEL_ENERGY_REACTIVE_IMPORT,
                    new DynamicChannel(TYPE_ENERGY, ITEM_ENERGY, "Reactive Energy Imported")),
            Map.entry(CHANNEL_ENERGY_REACTIVE_EXPORT,
                    new DynamicChannel(TYPE_ENERGY, ITEM_ENERGY, "Reactive Energy Exported")),
            Map.entry(CHANNEL_ENERGY_REACTIVE_IMPORT_INTERVAL,
                    new DynamicChannel(TYPE_ENERGY, ITEM_ENERGY, "Reactive Import Interval")),
            Map.entry(CHANNEL_ENERGY_REACTIVE_EXPORT_INTERVAL,
                    new DynamicChannel(TYPE_ENERGY, ITEM_ENERGY, "Reactive Export Interval")),
            Map.entry(CHANNEL_SOC, new DynamicChannel("soc", "Number:Dimensionless", "State of Charge")),
            Map.entry(CHANNEL_RPM, new DynamicChannel("rpm", "Number", "RPM")),
            Map.entry(CHANNEL_TEMPERATURE, new DynamicChannel("temperature", "Number:Temperature", "Temperature")));

    private final Logger logger = LoggerFactory.getLogger(OcppConnectorHandler.class);

    private volatile int connectorId = 1;
    private volatile boolean forceTxDefaultProfile;
    private volatile int profileMinIntervalMs;
    private volatile String hardwareMaxCurrentKey = "";
    private volatile String remoteStartTag = "openhab";
    private volatile int refreshInterval;
    private volatile boolean stuckStateRecovery;
    private volatile double nominalVoltage = 230.0;
    private volatile int phases = 1;
    private volatile int remoteStartRetries;
    private volatile String externalEnergyItem = "";
    private volatile String externalMeterType = "energy-kwh";

    private volatile @Nullable OcppChargePointHandler chargePoint;
    private volatile @Nullable Integer transactionId;
    // 2.0.1 stops a transaction by the id the charger chose, so it is kept alongside the numeric one.
    private volatile @Nullable String remoteTransactionId;
    private volatile @Nullable Integer meterStart;
    private volatile double currentLimitAmps;
    private volatile double powerLimitWatts;
    private volatile int numberPhasesRequested;
    private volatile boolean paused;
    private volatile boolean limitDeferred;
    private volatile boolean smartChargingUnsupportedLogged;
    private volatile boolean phaseSwitchWarningLogged;

    // Dedicated lock: the base class synchronizes on the handler monitor.
    private final Object lock = new Object();
    private static final OcppCommands FALLBACK_COMMANDS = new Ocpp16Commands();

    private double pendingLimitAmps;
    private long lastProfileSentAt;
    private long profileGeneration;
    private long lastPublishedGeneration;
    private @Nullable ScheduledFuture<?> pendingFlush;
    private @Nullable ScheduledFuture<?> pollTask;
    private @Nullable ScheduledFuture<?> stuckTask;
    private volatile @Nullable ScheduledFuture<?> remoteStartRetryTask;
    private @Nullable CompletableFuture<eu.chargetime.ocpp.model.Confirmation> pendingPoll;

    private record ProfileClaim(long generation, ChargingRateUnitType wireUnit, double wireValue, double limitAmps,
            double limitWatts, boolean powerSourced, @Nullable Integer numberPhases, boolean paused) {
    }

    public OcppConnectorHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        OcppConnectorConfiguration config = getConfigAs(OcppConnectorConfiguration.class);
        connectorId = config.connectorId;
        forceTxDefaultProfile = config.forceTxDefaultProfile;
        profileMinIntervalMs = config.profileMinIntervalMs;
        hardwareMaxCurrentKey = config.hardwareMaxCurrentKey;
        remoteStartTag = config.remoteStartTag;
        refreshInterval = config.refreshInterval;
        stuckStateRecovery = config.stuckStateRecovery;
        nominalVoltage = config.nominalVoltage;
        phases = config.phases;
        remoteStartRetries = config.remoteStartRetries;
        externalEnergyItem = config.externalEnergyItem;
        externalMeterType = config.externalMeterType;
        OcppChargePointHandler parent = chargePointHandler();
        if (parent == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
            return;
        }
        this.chargePoint = parent;
        updateProperty(PROPERTY_UNIQUE_ID, uniqueConnectorId(parent.getChargePointId(), connectorId));
        updateStatus(ThingStatus.UNKNOWN);
        parent.registerConnector(connectorId, this);
        recoverTransaction(parent);
        startPolling();
        if (parent.isReady()) {
            requestStatus();
        }
    }

    /** The item feeding session energy when the charger has no OCPP meter, or empty. */
    public String getExternalEnergyItem() {
        return externalEnergyItem;
    }

    /** How to read {@link #getExternalEnergyItem}: energy-kwh, energy-wh, power-kw or power-w. */
    public String getExternalMeterType() {
        return externalMeterType;
    }

    private void recoverTransaction(OcppChargePointHandler parent) {
        Integer open = parent.recoverTransactionId(connectorId);
        if (open != null) {
            transactionId = open;
            updateState(CHANNEL_TRANSACTION_ID, new DecimalType(open));
            logger.debug("Recovered open transaction {} on connector {} after restart", open, connectorId);
        }
    }

    private void startPolling() {
        cancel(pollTask);
        pollTask = null;
        if (refreshInterval > 0) {
            pollTask = scheduler.scheduleWithFixedDelay(this::pollMeterValues, refreshInterval, refreshInterval,
                    TimeUnit.SECONDS);
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        // Not super: re-register with the charge point; stop polling when offline.
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            OcppChargePointHandler parent = chargePointHandler();
            if (parent != null) {
                this.chargePoint = parent;
                if (getThing().getStatus() != ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.UNKNOWN);
                }
                parent.registerConnector(connectorId, this);
                startPolling();
            }
        } else {
            cancel(pollTask);
            pollTask = null;
            synchronized (lock) {
                cancel(stuckTask);
                if (pendingFlush != null) {
                    limitDeferred = true;
                }
                cancel(pendingFlush);
                stuckTask = null;
                pendingFlush = null;
            }
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    @Override
    public void dispose() {
        cancel(pollTask);
        cancel(remoteStartRetryTask);
        pollTask = null;
        remoteStartRetryTask = null;
        synchronized (lock) {
            cancel(stuckTask);
            cancel(pendingFlush);
            stuckTask = null;
            pendingFlush = null;
        }
        OcppChargePointHandler parent = chargePoint;
        if (parent != null) {
            parent.unregisterConnector(connectorId);
        }
        chargePoint = null;
    }

    private @Nullable OcppChargePointHandler chargePointHandler() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            return null;
        }
        BridgeHandler handler = bridge.getHandler();
        return handler instanceof OcppChargePointHandler chargePointHandler ? chargePointHandler : null;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            return;
        }
        switch (channelUID.getId()) {
            case CHANNEL_CHARGE_LIMIT:
                Double limit = toAmps(command);
                if (limit != null) {
                    currentLimitAmps = limit;
                    // A charge-limit clears any explicit power-limit, so the last command wins rather than a
                    // once-set power-limit shadowing every later amps command.
                    powerLimitWatts = 0;
                    if (!paused) {
                        applyLimit();
                    }
                }
                break;
            case CHANNEL_POWER_LIMIT:
                Double powerWatts = toWatts(command);
                if (powerWatts != null) {
                    powerLimitWatts = powerWatts;
                    if (!paused) {
                        applyLimit();
                    }
                }
                break;
            case CHANNEL_NUMBER_PHASES:
                Integer phaseCount = toPhaseCount(command);
                if (phaseCount != null) {
                    numberPhasesRequested = phaseCount;
                    if (!paused) {
                        applyLimit();
                    }
                }
                break;
            case CHANNEL_PAUSE:
                if (command instanceof OnOffType onOff) {
                    paused = onOff == OnOffType.ON;
                    applyLimit();
                }
                break;
            case CHANNEL_ID_TAG:
                // Chooses the token the next remote start presents, so a session can be started as a
                // given card or vehicle rather than the configured default.
                if (command instanceof StringType tag && !tag.toString().isBlank()) {
                    remoteStartTag = tag.toString().trim();
                }
                break;
            case CHANNEL_CHARGING:
                if (command instanceof OnOffType onOff) {
                    if (onOff == OnOffType.ON) {
                        remoteStart();
                    } else {
                        remoteStop();
                    }
                }
                break;
            case CHANNEL_AVAILABILITY:
                if (command instanceof OnOffType onOff) {
                    changeAvailability(onOff == OnOffType.ON);
                }
                break;
            case CHANNEL_UNLOCK:
                if (command == OnOffType.ON) {
                    dispatchIfReady(commands().unlock(connectorId), "UnlockConnector");
                    updateState(CHANNEL_UNLOCK, OnOffType.OFF);
                }
                break;
            case CHANNEL_HARDWARE_MAX_CURRENT:
                changeHardwareMaxCurrent(command);
                break;
            default:
                break;
        }
    }

    private void applyLimit() {
        if (smartChargingUnsupported()) {
            return;
        }
        if (isReadyToSend()) {
            coalesceProfile(paused ? 0.0 : currentLimitAmps);
        } else {
            limitDeferred = true;
        }
    }

    private boolean smartChargingUnsupported() {
        OcppChargePointHandler cp = chargePoint;
        if (cp == null || !Boolean.FALSE.equals(cp.getCapabilities().supportsSmartCharging().orElse(null))) {
            return false;
        }
        if (!smartChargingUnsupportedLogged) {
            smartChargingUnsupportedLogged = true;
            logger.warn(
                    "Charger {} connector {} does not support OCPP SmartCharging; charge-limit and pause have "
                            + "no effect and are not sent (some chargers stop charging on a profile)",
                    cp.getChargePointId(), connectorId);
        }
        return true;
    }

    public void onChargePointReady() {
        if (limitDeferred) {
            limitDeferred = false;
            applyLimit();
        }
    }

    private OcppCommands commands() {
        OcppChargePointHandler cp = chargePoint;
        return cp == null ? FALLBACK_COMMANDS : cp.commands();
    }

    private boolean isReadyToSend() {
        OcppChargePointHandler cp = chargePoint;
        return cp != null && cp.isReady();
    }

    private void coalesceProfile(double amps) {
        ProfileClaim claim = null;
        synchronized (lock) {
            pendingLimitAmps = amps;
            long elapsed = System.currentTimeMillis() - lastProfileSentAt;
            if (profileMinIntervalMs <= 0 || elapsed >= profileMinIntervalMs) {
                claim = claimSend();
            } else if (pendingFlush == null) {
                pendingFlush = scheduler.schedule(this::flushProfile, profileMinIntervalMs - elapsed,
                        TimeUnit.MILLISECONDS);
            }
        }
        if (claim != null) {
            sendProfile(claim);
        }
    }

    private void flushProfile() {
        ProfileClaim claim;
        synchronized (lock) {
            claim = claimSend();
        }
        sendProfile(claim);
    }

    private ProfileClaim claimSend() {
        cancel(pendingFlush);
        pendingFlush = null;
        lastProfileSentAt = System.currentTimeMillis();
        profileGeneration++;
        double amps = pendingLimitAmps;
        double watts = powerLimitWatts;
        OcppChargePointHandler cp = chargePoint;
        ChargerCapabilities caps = cp != null ? cp.getCapabilities() : ChargerCapabilities.unknown();
        boolean allowsPower = caps.allowsPowerUnit().orElse(false);
        boolean allowsCurrent = caps.allowsCurrentUnit().orElse(true);
        Integer numberPhases = numberPhasesRequested > 0 ? numberPhasesRequested : null;
        int conversionPhases = numberPhasesRequested > 0 ? numberPhasesRequested : phases;
        if (numberPhases != null && Boolean.FALSE.equals(caps.phaseSwitchSupported().orElse(null))
                && !phaseSwitchWarningLogged) {
            phaseSwitchWarningLogged = true;
            logger.warn(
                    "Charger {} connector {} does not advertise phase switching (ConnectorSwitch3to1PhaseSupported); "
                            + "the requested {}-phase setting may be ignored",
                    cp != null ? cp.getChargePointId() : "?", connectorId, numberPhases);
        }
        boolean powerSourced = watts > 0.0 && !paused && allowsPower;
        ChargingRateUnitType wireUnit;
        double wireValue;
        if (powerSourced) {
            wireUnit = ChargingRateUnitType.W;
            wireValue = watts;
        } else if (!allowsCurrent && allowsPower) {
            wireUnit = ChargingRateUnitType.W;
            wireValue = amps * nominalVoltage * conversionPhases;
        } else {
            wireUnit = ChargingRateUnitType.A;
            wireValue = amps;
        }
        return new ProfileClaim(profileGeneration, wireUnit, wireValue, currentLimitAmps, watts, powerSourced,
                numberPhases, paused);
    }

    private boolean claimPublication(ProfileClaim claim) {
        synchronized (lock) {
            if (claim.generation() <= lastPublishedGeneration) {
                return false;
            }
            lastPublishedGeneration = claim.generation();
            return true;
        }
    }

    private void sendProfile(ProfileClaim claim) {
        // 0 A is a pause; to resume with no cap, clear the profile.
        if (!claim.paused() && claim.wireValue() <= 0.0) {
            clearProfile(claim);
        } else {
            setProfile(claim);
        }
    }

    private void setProfile(ProfileClaim claim) {
        OcppCommands commands = commands();
        Integer phases = claim.numberPhases();
        dispatch(
                commands.setChargingProfile(connectorId, claim.wireValue(), claim.wireUnit() == ChargingRateUnitType.W,
                        phases == null ? 0 : phases, forceTxDefaultProfile, transactionId, remoteTransactionId),
                "SetChargingProfile").whenComplete((confirmation, ex) -> {
                    if (ex == null && commands.isAccepted(confirmation)) {
                        if (claimPublication(claim)) {
                            publishAcceptedLimit(claim);
                        } else {
                            logger.debug("Stale SetChargingProfile confirmation on connector {} ignored", connectorId);
                        }
                    } else if (ex == null) {
                        logger.debug("SetChargingProfile on connector {} not accepted: {}", connectorId, confirmation);
                    } else {
                        limitDeferred = true;
                    }
                });
    }

    private void publishAcceptedLimit(ProfileClaim claim) {
        if (claim.powerSourced()) {
            updateState(CHANNEL_POWER_LIMIT, new QuantityType<>(claim.limitWatts(), Units.WATT));
        } else {
            updateState(CHANNEL_CHARGE_LIMIT, new QuantityType<>(claim.limitAmps(), Units.AMPERE));
        }
        Integer phaseCount = claim.numberPhases();
        if (phaseCount != null) {
            updateState(CHANNEL_NUMBER_PHASES, new DecimalType(phaseCount));
        }
        updateState(CHANNEL_PAUSE, OnOffType.from(claim.paused()));
    }

    private void clearProfile(ProfileClaim claim) {
        OcppCommands commands = commands();
        dispatch(commands.clearChargingProfile(connectorId), "ClearChargingProfile")
                .whenComplete((confirmation, ex) -> {
                    if (ex == null && commands.isAccepted(confirmation)) {
                        if (claimPublication(claim)) {
                            updateState(CHANNEL_CHARGE_LIMIT, UnDefType.UNDEF);
                            updateState(CHANNEL_POWER_LIMIT, UnDefType.UNDEF);
                            updateState(CHANNEL_PAUSE, OnOffType.OFF);
                        } else {
                            logger.debug("Stale ClearChargingProfile confirmation on connector {} ignored",
                                    connectorId);
                        }
                    } else if (ex == null) {
                        logger.debug("ClearChargingProfile on connector {} not accepted: {}", connectorId,
                                confirmation);
                    } else {
                        limitDeferred = true;
                    }
                });
    }

    private void remoteStart() {
        if (!isReadyToSend()) {
            logger.debug("RemoteStart on connector {} skipped — charge point not ready", connectorId);
            return;
        }
        cancel(remoteStartRetryTask);
        remoteStartRetryTask = null;
        attemptRemoteStart(remoteStartRetries);
    }

    private void attemptRemoteStart(int remaining) {
        String tag = remoteStartTag;
        OcppChargePointHandler parent = chargePointHandler();
        TokenType type = parent == null ? TokenType.UNKNOWN : parent.tokenTypeOf(tag);
        dispatch(commands().remoteStart(connectorId, tag, type), "RemoteStart").whenComplete((confirmation, ex) -> {
            if (ex == null || remaining <= 0 || transactionId != null || !isReadyToSend()) {
                return;
            }
            logger.info("RemoteStart on connector {} did not answer; retrying ({} attempt(s) left)", connectorId,
                    remaining);
            remoteStartRetryTask = scheduler.schedule(() -> {
                if (transactionId == null && isReadyToSend()) {
                    attemptRemoteStart(remaining - 1);
                }
            }, REMOTE_START_RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
        });
    }

    private void remoteStop() {
        cancel(remoteStartRetryTask);
        remoteStartRetryTask = null;
        Integer transaction = transactionId;
        if (transaction == null) {
            logger.debug("No active transaction to stop on connector {}", connectorId);
            return;
        }
        dispatchIfReady(commands().remoteStop(transaction, remoteTransactionId), "RemoteStop");
    }

    private void changeAvailability(boolean operative) {
        if (!isReadyToSend()) {
            logger.debug("ChangeAvailability on connector {} skipped — charge point not ready", connectorId);
            return;
        }
        OcppCommands commands = commands();
        dispatch(commands.changeAvailability(connectorId, operative), "ChangeAvailability")
                .whenComplete((confirmation, ex) -> {
                    if (ex == null && commands.isAccepted(confirmation)) {
                        updateState(CHANNEL_AVAILABILITY, OnOffType.from(operative));
                    }
                });
    }

    private void changeHardwareMaxCurrent(Command command) {
        if (hardwareMaxCurrentKey.isBlank()) {
            logger.debug("Connector {} has no hardwareMaxCurrentKey configured", connectorId);
            return;
        }
        if (!isReadyToSend()) {
            logger.debug("ChangeConfiguration[hardwareMax] on connector {} skipped — charge point not ready",
                    connectorId);
            return;
        }
        Double amps = toAmps(command);
        if (amps == null) {
            return;
        }
        int rounded = (int) Math.round(amps);
        dispatch(new ChangeConfigurationRequest(hardwareMaxCurrentKey, Integer.toString(rounded)),
                "ChangeConfiguration[hardwareMax]").whenComplete((confirmation, ex) -> {
                    if (ex == null && confirmation instanceof ChangeConfigurationConfirmation change
                            && change.getStatus() == ConfigurationStatus.Accepted) {
                        updateState(CHANNEL_HARDWARE_MAX_CURRENT, new QuantityType<>(rounded, Units.AMPERE));
                    }
                });
    }

    private void pollMeterValues() {
        if (!isReadyToSend()) {
            return;
        }
        CompletableFuture<eu.chargetime.ocpp.model.Confirmation> previous = pendingPoll;
        if (previous != null && !previous.isDone()) {
            logger.debug("MeterValues poll on connector {} skipped — the previous poll is still outstanding",
                    connectorId);
            return;
        }
        pendingPoll = dispatch(commands().triggerMeterValues(connectorId), "TriggerMessage[MeterValues]")
                .toCompletableFuture();
    }

    public void requestStatus() {
        sendStatusRequest(false);
    }

    public void requestStatusNow() {
        sendStatusRequest(true);
    }

    private void sendStatusRequest(boolean bypassReadiness) {
        OcppChargePointHandler cp = chargePoint;
        if (cp == null) {
            return;
        }
        eu.chargetime.ocpp.model.Request request = cp.commands().triggerStatusNotification(connectorId);
        CompletionStage<eu.chargetime.ocpp.model.Confirmation> result = bypassReadiness ? cp.sendNow(request)
                : cp.send(request);
        result.whenComplete((confirmation, ex) -> {
            if (ex != null) {
                logger.debug("TriggerMessage[StatusNotification] on connector {} failed: {}", connectorId,
                        ex.toString());
            }
        });
    }

    private void dispatchIfReady(eu.chargetime.ocpp.model.Request request, String name) {
        if (isReadyToSend()) {
            dispatch(request, name);
        } else {
            logger.debug("{} on connector {} skipped — charge point not ready", name, connectorId);
        }
    }

    private CompletionStage<eu.chargetime.ocpp.model.Confirmation> dispatch(eu.chargetime.ocpp.model.Request request,
            String name) {
        OcppChargePointHandler cp = chargePoint;
        if (cp == null) {
            logger.debug("Cannot send {} — connector {} has no charge point", name, connectorId);
            return CompletableFuture.failedFuture(new IllegalStateException("no charge point"));
        }
        return cp.send(request).whenComplete((confirmation, ex) -> {
            if (ex != null) {
                Throwable unwrapped = ex.getCause();
                Throwable cause = ex instanceof CompletionException && unwrapped != null ? unwrapped : ex;
                logger.warn("{} on connector {} failed: {}", name, connectorId, cause.toString());
            } else {
                logger.debug("{} on connector {} -> {}", name, connectorId, confirmation);
            }
        });
    }

    private @Nullable Double toAmps(Command command) {
        if (command instanceof QuantityType<?> quantity) {
            QuantityType<?> inAmperes = quantity.toUnit(Units.AMPERE);
            return inAmperes != null ? inAmperes.doubleValue() : null;
        }
        if (command instanceof DecimalType decimal) {
            return decimal.doubleValue();
        }
        return null;
    }

    private @Nullable Double toWatts(Command command) {
        if (command instanceof QuantityType<?> quantity) {
            QuantityType<?> inWatts = quantity.toUnit(Units.WATT);
            return inWatts != null ? inWatts.doubleValue() : null;
        }
        if (command instanceof DecimalType decimal) {
            return decimal.doubleValue();
        }
        return null;
    }

    private @Nullable Integer toPhaseCount(Command command) {
        int value;
        if (command instanceof QuantityType<?> quantity) {
            value = quantity.intValue();
        } else if (command instanceof DecimalType decimal) {
            value = decimal.intValue();
        } else {
            return null;
        }
        return value >= 0 && value <= 3 ? value : null;
    }

    public void onStatusNotification(StatusInfo info) {
        ConnectorStatus status = info.status();
        if (status != null) {
            updateState(CHANNEL_STATUS, new StringType(status.label()));
            updateState(CHANNEL_CABLE_CONNECTED, OnOffType.from(CABLE_PRESENT.contains(status)));
            // Faulted is a fault, not an availability/charging state; leave those channels.
            if (status == ConnectorStatus.UNAVAILABLE) {
                updateState(CHANNEL_AVAILABILITY, OnOffType.OFF);
            } else if (status != ConnectorStatus.FAULTED) {
                updateState(CHANNEL_AVAILABILITY, OnOffType.ON);
            }
            if (status != ConnectorStatus.FAULTED) {
                updateState(CHANNEL_CHARGING, OnOffType.from(CHARGING_ACTIVE.contains(status)));
            }
            if (status == ConnectorStatus.AVAILABLE) {
                // Available means no active transaction; clear any stale one.
                Integer stale = transactionId;
                if (stale != null) {
                    transactionId = null;
                    updateState(CHANNEL_TRANSACTION_ID, UnDefType.UNDEF);
                    OcppChargePointHandler cp = chargePoint;
                    if (cp != null) {
                        cp.transactionCompleted(stale);
                    }
                }
            }
            armStuckWatchdog(status);
        }
        updateStatus(ThingStatus.ONLINE);
    }

    public void onMeterValues(MeterSample sample) {
        Map<String, State> states = MeterValueMapper.toStates(sample);
        ensureDynamicChannels(states.keySet());
        states.forEach(this::updateState);
        List<MeterSample.Block> blocks = sample.blocks();
        ZonedDateTime timestamp = null;
        for (int i = blocks.size() - 1; i >= 0 && timestamp == null; i--) {
            timestamp = blocks.get(i).timestamp();
        }
        if (timestamp != null) {
            updateState(CHANNEL_TIMESTAMP, new DateTimeType(timestamp));
        }
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.ONLINE);
        }
        logger.trace("Connector {} applied {} metering states", connectorId, states.size());
    }

    private void ensureDynamicChannels(Set<String> reportedChannelIds) {
        ThingBuilder builder = null;
        for (String channelId : reportedChannelIds) {
            DynamicChannel spec = DYNAMIC_CHANNELS.get(channelId);
            if (spec == null) {
                continue;
            }
            ChannelUID channelUID = new ChannelUID(getThing().getUID(), channelId);
            if (getThing().getChannel(channelUID) != null) {
                continue;
            }
            if (builder == null) {
                builder = editThing();
            }
            Channel channel = ChannelBuilder.create(channelUID, spec.itemType())
                    .withType(new ChannelTypeUID(BINDING_ID, spec.channelTypeId())).withLabel(spec.label()).build();
            builder.withChannel(channel);
            logger.debug("Connector {} adding dynamic telemetry channel {}", connectorId, channelId);
        }
        if (builder != null) {
            updateThing(builder.build());
        }
    }

    public void onTransactionStarted(TransactionEvent event) {
        cancel(remoteStartRetryTask);
        remoteStartRetryTask = null;
        int transactionId = event.transactionId();
        this.transactionId = transactionId;
        this.remoteTransactionId = event.remoteId();
        updateState(CHANNEL_TRANSACTION_ID, new DecimalType(transactionId));
        String idTag = event.idToken();
        if (idTag != null) {
            updateState(CHANNEL_ID_TAG, new StringType(idTag));
        }
        Integer meterStart = event.meterWh();
        if (meterStart != null) {
            this.meterStart = meterStart;
            updateState(CHANNEL_METER_START, new QuantityType<>(meterStart, Units.WATT_HOUR));
        }
        ZonedDateTime timestamp = event.timestamp();
        if (timestamp != null) {
            updateState(CHANNEL_TIMESTAMP_START, new DateTimeType(timestamp));
        }
    }

    public void onTransactionUpdated(TransactionEvent event) {
        String idTag = event.idToken();
        if (idTag != null) {
            updateState(CHANNEL_ID_TAG, new StringType(idTag));
        }
    }

    public void onTransactionEnded(TransactionEvent event) {
        this.transactionId = null;
        this.remoteTransactionId = null;
        updateState(CHANNEL_TRANSACTION_ID, UnDefType.UNDEF);
        Integer meterStop = event.meterWh();
        if (meterStop != null) {
            updateState(CHANNEL_METER_STOP, new QuantityType<>(meterStop, Units.WATT_HOUR));
            Integer start = meterStart;
            if (start != null) {
                updateState(CHANNEL_SESSION_ENERGY, new QuantityType<>(meterStop - start, Units.WATT_HOUR));
            }
        }
        meterStart = null;
        ZonedDateTime timestamp = event.timestamp();
        if (timestamp != null) {
            updateState(CHANNEL_TIMESTAMP_STOP, new DateTimeType(timestamp));
        }
    }

    private void armStuckWatchdog(ConnectorStatus status) {
        // Opt-in: auto-unlocking a normal Preparing/Finishing is a physical side effect.
        if (!stuckStateRecovery) {
            return;
        }
        synchronized (lock) {
            cancel(stuckTask);
            stuckTask = null;
            if (TRANSIENT.contains(status)) {
                stuckTask = scheduler.schedule(() -> onStuck(status), STUCK_STATE_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    private void onStuck(ConnectorStatus status) {
        logger.warn("Connector {} stuck in {} for over {}s; sending UnlockConnector", connectorId, status,
                STUCK_STATE_SECONDS);
        dispatch(commands().unlock(connectorId), "UnlockConnector[stuck-recovery]");
    }

    private static void cancel(@Nullable ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }
}
