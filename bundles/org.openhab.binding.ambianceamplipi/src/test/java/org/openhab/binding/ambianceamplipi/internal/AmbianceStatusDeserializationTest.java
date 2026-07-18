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

import java.util.List;

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
        assertNull(st.source); // pre-source firmware -> handler stays radio-only
        assertNull(st.spotify);
    }

    @Test
    void deserializesGroupsAndSleep() {
        String json = "{\"zones\":[],\"radio\":{\"playing\":false},\"master_vol\":0,\"master_mute\":false,"
                + "\"siren\":false,\"groups\":[{\"name\":\"Boven\",\"zones\":[0,1],\"vol\":64,\"mute\":false,"
                + "\"power\":true}],\"sleep\":{\"active\":true,\"remaining_s\":90}}";

        AmbianceStatus st = gson.fromJson(json, AmbianceStatus.class);

        assertNotNull(st);
        assertEquals(1, st.groups.size());
        assertEquals("Boven", st.groups.get(0).name);
        assertEquals(List.of(0, 1), st.groups.get(0).zones);
        assertEquals(64, st.groups.get(0).vol);
        assertTrue(st.groups.get(0).power);
        assertTrue(st.sleep.active);
        assertEquals(90, st.sleep.remainingS); // @SerializedName("remaining_s")
    }

    @Test
    void deserializesSystemStats() {
        String json = "{\"hostname\":\"amplipi\",\"cpu_pct\":7,\"temp_c\":52.1,"
                + "\"mem\":{\"total_mb\":966,\"used_mb\":171,\"pct\":18},\"disk\":{\"pct\":74}}";

        var stats = gson.fromJson(json, org.openhab.binding.ambianceamplipi.internal.model.AmbianceSystemStats.class);

        assertNotNull(stats);
        assertEquals(7, stats.cpuPct);
        assertEquals(52.1, stats.tempC);
        assertEquals(18, stats.mem.pct);
        assertEquals(74, stats.disk.pct);
    }

    @Test
    void deserializesSourceAndSpotify() {
        String json = "{\"zones\":[],\"radio\":{\"playing\":false},\"master_vol\":0,\"master_mute\":false,"
                + "\"siren\":false,\"source\":{\"active\":\"spotify\",\"available\":[\"radio\",\"spotify\"]},"
                + "\"spotify\":{\"running\":true,\"playing\":true,\"track\":\"Yellow\",\"artist\":\"Coldplay\","
                + "\"album\":\"Parachutes\",\"cover\":\"http://art/x.jpg\"}}";

        AmbianceStatus st = gson.fromJson(json, AmbianceStatus.class);

        assertNotNull(st);
        assertEquals("spotify", st.source.active);
        assertEquals(2, st.source.available.size());
        assertTrue(st.spotify.running);
        assertTrue(st.spotify.playing);
        assertEquals("Yellow", st.spotify.track);
        assertEquals("Coldplay", st.spotify.artist);
        assertEquals("Parachutes", st.spotify.album);
    }
}
