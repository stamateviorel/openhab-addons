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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ocpp.internal.config.OcppCpmsUserConfiguration;
import org.openhab.binding.ocpp.internal.cpms.CpmsService;
import org.openhab.binding.ocpp.internal.cpms.CpmsUser;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;

/**
 * A CPMS user: registers the person and their cards with the {@link CpmsService} for authorization and
 * usage attribution, and publishes their month/year kWh.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class OcppCpmsUserHandler extends BaseThingHandler {

    private static final long REFRESH_MINUTES = 5;

    private volatile @Nullable CpmsService cpms;
    private @Nullable ScheduledFuture<?> refreshTask;

    public OcppCpmsUserHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        OcppServerBridgeHandler server = serverHandler();
        CpmsService service = server != null ? server.getCpms() : null;
        if (service == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
            return;
        }
        cpms = service;
        registerUser(service);
        updateStatus(ThingStatus.ONLINE);
        publishUsage();
        cancelRefresh();
        refreshTask = scheduler.scheduleWithFixedDelay(this::publishUsage, REFRESH_MINUTES, REFRESH_MINUTES,
                TimeUnit.MINUTES);
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            initialize();
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            publishUsage();
        }
    }

    @Override
    public void dispose() {
        cancelRefresh();
        CpmsService service = cpms;
        if (service != null) {
            service.unregisterUser(getThing().getUID().getAsString());
        }
        cpms = null;
    }

    private void registerUser(CpmsService service) {
        OcppCpmsUserConfiguration config = getConfigAs(OcppCpmsUserConfiguration.class);
        String label = getThing().getLabel();
        String name = label != null ? label : getThing().getUID().getId();
        service.registerUser(new CpmsUser(getThing().getUID().getAsString(), name, config.enabled, config.monthlyCapKwh,
                config.cards));
    }

    private void publishUsage() {
        CpmsService service = cpms;
        if (service == null) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        long nowMs = now.toInstant().toEpochMilli();
        long monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.getZone()).toInstant().toEpochMilli();
        long yearStart = now.toLocalDate().withDayOfYear(1).atStartOfDay(now.getZone()).toInstant().toEpochMilli();
        String userId = getThing().getUID().getAsString();
        updateState(CHANNEL_MONTH_ENERGY,
                new QuantityType<>(service.energyKwh(userId, monthStart, nowMs), Units.KILOWATT_HOUR));
        updateState(CHANNEL_YEAR_ENERGY,
                new QuantityType<>(service.energyKwh(userId, yearStart, nowMs), Units.KILOWATT_HOUR));
    }

    private @Nullable OcppServerBridgeHandler serverHandler() {
        Bridge bridge = getBridge();
        return bridge != null && bridge.getHandler() instanceof OcppServerBridgeHandler handler ? handler : null;
    }

    private void cancelRefresh() {
        ScheduledFuture<?> task = refreshTask;
        if (task != null) {
            task.cancel(true);
            refreshTask = null;
        }
    }
}
