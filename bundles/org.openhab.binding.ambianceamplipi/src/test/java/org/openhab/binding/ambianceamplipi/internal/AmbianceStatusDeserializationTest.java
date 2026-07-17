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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceStatus;

import com.google.gson.Gson;

/**
 * Verifies the {@code /api/status} response deserializes into the DTOs, including the
 * snake_case fields ({@code master_vol}/{@code master_mute}) and the nested health object,
 * and that a controller which does not report health (older firmware) does not break.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class AmbianceStatusDeserializationTest {

    private final Gson gson = new Gson();

    @Test
    void deserializesFullStatus() {
        String json = "{\"zones\":[{\"id\":0,\"name\":\"Office\",\"vol\":70,\"mute\":false,\"power\":true},"
                + "{\"id\":1,\"name\":\"Kitchen\",\"vol\":50,\"mute\":true,\"power\":false}],"
                + "\"radio\":{\"playing\":true,\"station\":\"VRT\",\"title\":\"Coldplay - Yellow\","
                + "\"artist\":\"Coldplay\",\"track\":\"Yellow\",\"stations\":[\"VRT\",\"Klara\"]},"
                + "\"master_vol\":60,\"master_mute\":false,\"siren\":false,"
                + "\"health\":{\"ok\":true,\"issues\":[],\"mpd\":\"ok\",\"preamp\":\"ok\",\"recoveries\":0,\"checked\":123}}";

        AmbianceStatus st = gson.fromJson(json, AmbianceStatus.class);

        assertNotNull(st);
        assertEquals(2, st.zones.size());
        assertEquals("Office", st.zones.get(0).name);
        assertTrue(st.zones.get(0).power);
        assertTrue(st.zones.get(1).mute);
        assertTrue(st.radio.playing);
        assertEquals("VRT", st.radio.station);
        assertEquals("Coldplay", st.radio.artist);
        assertEquals(2, st.radio.stations.size());
        assertEquals(60, st.masterVol); // @SerializedName("master_vol")
        assertFalse(st.masterMute); // @SerializedName("master_mute")
        assertFalse(st.siren);
        assertNotNull(st.health);
        assertTrue(st.health.ok);
        assertEquals("ok", st.health.mpd);
    }

    @Test
    void healthAbsentIsNull() {
        String json = "{\"zones\":[],\"radio\":{\"playing\":false},\"master_vol\":0,\"master_mute\":false,\"siren\":false}";

        AmbianceStatus st = gson.fromJson(json, AmbianceStatus.class);

        assertNotNull(st);
        assertNull(st.health);
    }
}
