/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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
package org.openhab.binding.ambianceamplipi.internal;

import static org.openhab.binding.ambianceamplipi.internal.AmbianceAmplipiBindingConstants.*;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceStatus;
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceZone;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * Handler for a single Ambiance AmpliPi zone: reflects the bridge's status pushes and sends
 * per-zone power/volume/mute commands via {@code PATCH /api/zones/{id}}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class AmbianceZoneHandler extends BaseThingHandler implements AmbianceStatusChangeListener {

    private static final int REQUEST_TIMEOUT = 5000;

    private final Logger logger = LoggerFactory.getLogger(AmbianceZoneHandler.class);
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    private int zoneId;
    private String baseUrl = "";

    public AmbianceZoneHandler(Thing thing, HttpClient httpClient) {
        super(thing);
        this.httpClient = httpClient;
    }

    @Override
    public void initialize() {
        zoneId = getConfigAs(AmbianceZoneConfiguration.class).id;
        attachToBridge();
    }

    /**
     * (Re-)attach to the CURRENT bridge handler instance. Called from initialize() and again from
     * bridgeStatusChanged(): a bridge config edit recreates the bridge handler without
     * re-initializing child things, which would otherwise leave this zone registered on the
     * disposed instance (stale baseUrl, no more status pushes).
     */
    private void attachToBridge() {
        Bridge bridge = getBridge();
        ThingHandler bridgeHandler = bridge != null ? bridge.getHandler() : null;
        if (bridgeHandler instanceof AmbianceAmplipiHandler h) {
            baseUrl = h.getBaseUrl();
            h.addStatusChangeListener(this); // idempotent on the bridge side
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED, "Controller bridge not ready");
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            attachToBridge();
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    @Override
    public void receive(AmbianceStatus status) {
        if (status.zones == null) {
            return;
        }
        for (AmbianceZone z : status.zones) {
            if (z != null && z.id == zoneId) {
                updateState(CHANNEL_POWER, OnOffType.from(z.power));
                updateState(CHANNEL_VOLUME, new PercentType(Math.max(0, Math.min(100, z.vol))));
                updateState(CHANNEL_MUTE, OnOffType.from(z.mute));
                return;
            }
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            return;
        }
        @Nullable
        Map<String, Object> body = null;
        switch (channelUID.getId()) {
            case CHANNEL_POWER:
                if (command instanceof OnOffType) {
                    body = Map.of("power", command == OnOffType.ON);
                }
                break;
            case CHANNEL_VOLUME:
                if (command instanceof PercentType percent) {
                    body = Map.of("vol", percent.intValue());
                }
                break;
            case CHANNEL_MUTE:
                if (command instanceof OnOffType) {
                    body = Map.of("mute", command == OnOffType.ON);
                }
                break;
            default:
                return;
        }
        if (body != null) {
            patch("/api/zones/" + zoneId, body);
        }
    }

    private void patch(String path, Map<String, Object> body) {
        try {
            ContentResponse resp = httpClient.newRequest(baseUrl + path).method(HttpMethod.PATCH)
                    .content(new StringContentProvider(gson.toJson(body)), "application/json")
                    .timeout(REQUEST_TIMEOUT, TimeUnit.MILLISECONDS).send();
            if (resp.getStatus() != HttpStatus.OK_200) {
                logger.warn("Ambiance zone PATCH {} -> HTTP {}", path, resp.getStatus());
            }
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            logger.warn("Ambiance zone PATCH failed: {}", e.getMessage());
        }
    }

    @Override
    public void dispose() {
        Bridge bridge = getBridge();
        ThingHandler bridgeHandler = bridge != null ? bridge.getHandler() : null;
        if (bridgeHandler instanceof AmbianceAmplipiHandler h) {
            h.removeStatusChangeListener(this);
        }
    }
}
