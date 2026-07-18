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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceGroup;
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceStatus;
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
 * Handler for a zone group: reflects the bridge's status pushes (average volume, all-mute,
 * all-power over the member zones) and fans commands out via {@code PATCH /api/groups/{name}}.
 * Groups are keyed by NAME — deleting or renaming the group on the controller marks the
 * thing GONE (a renamed group is re-discovered under its new name).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class AmbianceGroupHandler extends BaseThingHandler implements AmbianceStatusChangeListener {

    private static final int REQUEST_TIMEOUT = 5000;

    private final Logger logger = LoggerFactory.getLogger(AmbianceGroupHandler.class);
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    private String groupName = "";
    private String baseUrl = "";

    public AmbianceGroupHandler(Thing thing, HttpClient httpClient) {
        super(thing);
        this.httpClient = httpClient;
    }

    @Override
    public void initialize() {
        Object name = getConfig().get(CFG_NAME);
        groupName = name != null ? name.toString() : "";
        if (groupName.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Group name not set");
            return;
        }
        attachToBridge();
    }

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
            attachToBridge(); // re-attach: a bridge config edit recreates the bridge handler
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    @Override
    public void receive(AmbianceStatus status) {
        if (status.groups == null) {
            return; // pre-groups firmware
        }
        for (AmbianceGroup g : status.groups) {
            if (g != null && groupName.equals(g.name)) {
                if (getThing().getStatus() != ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.ONLINE);
                }
                updateState(CHANNEL_POWER, OnOffType.from(g.power));
                updateState(CHANNEL_VOLUME, new PercentType(Math.max(0, Math.min(100, g.vol))));
                updateState(CHANNEL_MUTE, OnOffType.from(g.mute));
                return;
            }
        }
        // the group was deleted or renamed on the controller (a rename is re-discovered)
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.GONE, "Group not present on the controller");
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
            String path = "/api/groups/" + URLEncoder.encode(groupName, StandardCharsets.UTF_8).replace("+", "%20");
            patch(path, body);
        }
    }

    private void patch(String path, Map<String, Object> body) {
        try {
            ContentResponse resp = httpClient.newRequest(baseUrl + path).method(HttpMethod.PATCH)
                    .content(new StringContentProvider(gson.toJson(body)), "application/json")
                    .timeout(REQUEST_TIMEOUT, TimeUnit.MILLISECONDS).send();
            if (resp.getStatus() != HttpStatus.OK_200) {
                logger.warn("Ambiance group PATCH {} -> HTTP {}", path, resp.getStatus());
            }
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            logger.warn("Ambiance group PATCH failed: {}", e.getMessage());
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
