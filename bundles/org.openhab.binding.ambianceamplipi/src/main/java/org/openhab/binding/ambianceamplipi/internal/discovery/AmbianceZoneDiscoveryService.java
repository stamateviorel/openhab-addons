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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ambianceamplipi.internal.AmbianceAmplipiBindingConstants;
import org.openhab.binding.ambianceamplipi.internal.AmbianceAmplipiHandler;
import org.openhab.binding.ambianceamplipi.internal.AmbianceStatusChangeListener;
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceStatus;
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceZone;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * Discovers the controller's zones — labeled with the names configured on the controller
 * (zones.conf / the web UI), so adopters get ready-made zone things in the Inbox instead of
 * hand-writing them. Results are produced from the bridge's status fan-out; already-existing
 * zone things (e.g. file-defined) are auto-ignored by the framework.
 *
 * @author Stamate Viorel - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE, service = AmbianceZoneDiscoveryService.class)
@NonNullByDefault
public class AmbianceZoneDiscoveryService extends AbstractThingHandlerDiscoveryService<AmbianceAmplipiHandler>
        implements AmbianceStatusChangeListener {

    private static final int SEARCH_TIME_S = 5;

    private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AmbianceZoneDiscoveryService.class);

    private @Nullable String lastKey; // zone ids+names of the last publish — republish only on change

    public AmbianceZoneDiscoveryService() {
        super(AmbianceAmplipiHandler.class, Set.of(AmbianceAmplipiBindingConstants.THING_TYPE_ZONE), SEARCH_TIME_S);
    }

    @Override
    public void initialize() {
        logger.debug("zone discovery attached to {}", thingHandler.getThing().getUID());
        thingHandler.addStatusChangeListener(this);
        super.initialize();
    }

    @Override
    public void dispose() {
        super.dispose();
        thingHandler.removeStatusChangeListener(this);
    }

    @Override
    protected void startScan() {
        lastKey = null; // force a republish from the next status fan-out (≤ one refresh interval)
    }

    @Override
    public void receive(AmbianceStatus status) {
        List<AmbianceZone> zones = status.zones;
        if (zones == null || zones.isEmpty()) {
            return;
        }
        String key = zones.stream().filter(z -> z != null).map(z -> z.id + ":" + z.name)
                .collect(Collectors.joining("|"));
        if (key.equals(lastKey)) {
            return; // nothing changed since the last publish (a rename re-publishes with the new label)
        }
        lastKey = key;
        logger.debug("publishing {} zone discovery results ({})", zones.size(), key);
        ThingUID bridgeUID = thingHandler.getThing().getUID();
        for (AmbianceZone zone : zones) {
            if (zone == null) {
                continue;
            }
            String name = zone.name != null && !zone.name.isBlank() ? zone.name : "Zone " + (zone.id + 1);
            thingDiscovered(DiscoveryResultBuilder
                    .create(new ThingUID(AmbianceAmplipiBindingConstants.THING_TYPE_ZONE, bridgeUID, "z" + zone.id))
                    .withBridge(bridgeUID) //
                    .withLabel(name) //
                    .withProperty(AmbianceAmplipiBindingConstants.CFG_ID, zone.id) //
                    .withProperty("zoneKey", bridgeUID.getId() + "-z" + zone.id) //
                    .withRepresentationProperty("zoneKey") // unique even with several controllers
                    .build());
        }
    }
}
