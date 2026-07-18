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

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.core.audio.AudioHTTPServer;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.net.HttpServiceUtil;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Creates handlers for the Ambiance AmpliPi binding.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.ambianceamplipi", service = ThingHandlerFactory.class)
public class AmbianceAmplipiHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(THING_TYPE_CONTROLLER, THING_TYPE_ZONE, THING_TYPE_GROUP);

    private final HttpClient httpClient;
    private final AudioHTTPServer audioHTTPServer;
    private final NetworkAddressService networkAddressService;

    @Activate
    public AmbianceAmplipiHandlerFactory(@Reference HttpClientFactory httpClientFactory,
            @Reference AudioHTTPServer audioHTTPServer, @Reference NetworkAddressService networkAddressService) {
        this.httpClient = httpClientFactory.getCommonHttpClient();
        this.audioHTTPServer = audioHTTPServer;
        this.networkAddressService = networkAddressService;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();
        if (THING_TYPE_CONTROLLER.equals(thingTypeUID)) {
            return new AmbianceAmplipiHandler((Bridge) thing, httpClient, audioHTTPServer, createCallbackUrl());
        }
        if (THING_TYPE_ZONE.equals(thingTypeUID)) {
            return new AmbianceZoneHandler(thing, httpClient);
        }
        if (THING_TYPE_GROUP.equals(thingTypeUID)) {
            return new AmbianceGroupHandler(thing, httpClient);
        }
        return null;
    }

    /** The URL openHAB serves TTS audio on, so the controller can fetch it for announcements. */
    private @Nullable String createCallbackUrl() {
        String ipAddress = networkAddressService.getPrimaryIpv4HostAddress();
        if (ipAddress == null) {
            return null;
        }
        int port = HttpServiceUtil.getHttpServicePort(bundleContext);
        if (port == -1) {
            return null;
        }
        return "http://" + ipAddress + ":" + port;
    }
}
