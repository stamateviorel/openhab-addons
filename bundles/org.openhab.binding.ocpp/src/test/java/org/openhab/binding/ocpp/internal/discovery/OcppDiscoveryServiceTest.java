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
package org.openhab.binding.ocpp.internal.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.openhab.binding.ocpp.internal.OcppBindingConstants.THING_TYPE_SERVER;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.binding.ocpp.internal.handler.OcppServerBridgeHandler;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;
import org.openhab.core.config.discovery.DiscoveryListener;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;

/**
 * Tests that a charge point id is reduced to a valid ThingUID segment without letting two distinct ids
 * collide onto one Thing, and that a re-tapped card lands on the Thing UID it was first offered under.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class OcppDiscoveryServiceTest {

    @Test
    void aCleanIdIsUsedAsTheSegmentUnchanged() {
        assertEquals("CP001", OcppDiscoveryService.sanitize("CP001"));
        assertEquals("charger_3-A", OcppDiscoveryService.sanitize("charger_3-A"));
    }

    @Test
    void twoIdsThatDifferOnlyByAnUnsupportedCharacterDoNotCollide() {
        // "a/b" and "a_b" both collapse to "a_b" under plain replacement; the reversible encoding keeps them distinct.
        assertNotEquals(OcppDiscoveryService.sanitize("a/b"), OcppDiscoveryService.sanitize("a_b"));
    }

    @Test
    void anEncodedIdIsAValidSegmentAndDecodesBackToTheOriginal() {
        String id = "CP/x:1 ÿ";
        String segment = OcppDiscoveryService.sanitize(id);
        assertTrue(segment.matches("[A-Za-z0-9_-]+"), "must be a valid ThingUID segment");
        String decoded = new String(Base64.getUrlDecoder().decode(segment.substring("b64-".length())),
                StandardCharsets.UTF_8);
        assertEquals(id, decoded);
    }

    @Test
    void theSameCardSeenTwiceIsOfferedUnderOneThingUid() throws InterruptedException {
        Bridge server = mock(Bridge.class);
        when(server.getUID()).thenReturn(new ThingUID(THING_TYPE_SERVER, "main"));
        OcppServerBridgeHandler handler = mock(OcppServerBridgeHandler.class);
        when(handler.getThing()).thenReturn(server);

        OcppDiscoveryService service = new OcppDiscoveryService();
        service.setThingHandler(handler);
        BlockingQueue<DiscoveryResult> results = new ArrayBlockingQueue<>(4);
        service.addDiscoveryListener(new CapturingListener(results));

        service.tokenDiscovered("CARD-1", TokenType.CARD, "Charger 2");
        service.tokenDiscovered("CARD-1", TokenType.CARD, "Charger 3");

        DiscoveryResult first = Objects.requireNonNull(results.poll(5, TimeUnit.SECONDS));
        DiscoveryResult second = Objects.requireNonNull(results.poll(5, TimeUnit.SECONDS));
        assertEquals(List.of("CARD-1"), first.getProperties().get("cards"));
        assertEquals(first.getThingUID(), second.getThingUID(), "a re-tapped card must not stack up in the inbox");
    }

    private static class CapturingListener implements DiscoveryListener {

        private final BlockingQueue<DiscoveryResult> results;

        CapturingListener(BlockingQueue<DiscoveryResult> results) {
            this.results = results;
        }

        @Override
        public void thingDiscovered(@Nullable DiscoveryService source, @Nullable DiscoveryResult result) {
            results.add(Objects.requireNonNull(result));
        }

        @Override
        public void thingRemoved(@Nullable DiscoveryService source, @Nullable ThingUID thingUID) {
        }

        @Override
        public @Nullable Collection<ThingUID> removeOlderResults(@Nullable DiscoveryService source,
                @Nullable Instant timestamp, @Nullable Collection<ThingTypeUID> thingTypeUIDs,
                @Nullable ThingUID bridgeUID) {
            return List.of();
        }
    }
}
