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
package org.openhab.binding.ambianceamplipi.internal.discovery;

import java.net.InetAddress;
import java.util.Set;

import javax.jmdns.ServiceInfo;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ambianceamplipi.internal.AmbianceAmplipiBindingConstants;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.mdns.MDNSDiscoveryParticipant;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;

/**
 * Discovers Ambiance AmpliPi controllers on the local network through their mDNS
 * announcement ({@code _ambianceamplipi._tcp}, published by the controller host's avahi
 * service — see {@code packaging/avahi/ambiance-amplipi.service} in the ambiance-amplipi repo).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component
public class AmbianceAmplipiMDNSDiscoveryParticipant implements MDNSDiscoveryParticipant {

    private static final String SERVICE_TYPE = "_ambianceamplipi._tcp.local.";

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return Set.of(AmbianceAmplipiBindingConstants.THING_TYPE_CONTROLLER);
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public @Nullable DiscoveryResult createResult(ServiceInfo service) {
        ThingUID uid = getThingUID(service);
        InetAddress ip = getIpAddress(service);
        if (uid == null || ip == null) {
            return null;
        }
        int port = service.getPort() > 0 ? service.getPort() : 8080;
        return DiscoveryResultBuilder.create(uid) //
                .withThingType(AmbianceAmplipiBindingConstants.THING_TYPE_CONTROLLER) //
                .withLabel("Ambiance AmpliPi (" + ip.getHostAddress() + ")") //
                .withProperty(AmbianceAmplipiBindingConstants.CFG_HOSTNAME, ip.getHostAddress()) //
                .withProperty(AmbianceAmplipiBindingConstants.CFG_PORT, port) //
                .withRepresentationProperty(AmbianceAmplipiBindingConstants.CFG_HOSTNAME) //
                .build();
    }

    @Override
    public @Nullable ThingUID getThingUID(ServiceInfo service) {
        InetAddress ip = getIpAddress(service);
        if (ip != null) {
            String id = ip.getHostAddress().replace(".", "");
            return new ThingUID(AmbianceAmplipiBindingConstants.THING_TYPE_CONTROLLER, id);
        }
        return null;
    }

    private @Nullable InetAddress getIpAddress(ServiceInfo service) {
        if (service.getInet4Addresses().length > 0) {
            return service.getInet4Addresses()[0];
        }
        return null;
    }
}
