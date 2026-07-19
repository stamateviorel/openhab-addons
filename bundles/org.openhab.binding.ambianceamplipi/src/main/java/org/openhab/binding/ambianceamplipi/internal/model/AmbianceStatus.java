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
package org.openhab.binding.ambianceamplipi.internal.model;

import java.util.List;

import com.google.gson.annotations.SerializedName;

/**
 * DTO for the Ambiance AmpliPi {@code GET /api/status} response.
 *
 * @author Stamate Viorel - Initial contribution
 */
public class AmbianceStatus {
    public List<AmbianceZone> zones;
    public AmbianceRadio radio;
    @SerializedName("master_vol")
    public int masterVol;
    @SerializedName("master_mute")
    public boolean masterMute;
    public boolean siren;
    public AmbianceHealth health;
    public AmbianceSource source; // null on pre-source firmware
    public AmbianceSpotify spotify;
    public List<AmbianceGroup> groups;
    public AmbianceSleep sleep;
    public AmbianceAnnounce announce; // null on pre-announce-queue firmware
}
