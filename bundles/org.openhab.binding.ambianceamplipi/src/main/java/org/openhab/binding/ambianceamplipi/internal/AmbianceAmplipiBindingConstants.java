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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Constants used across the Ambiance AmpliPi binding.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class AmbianceAmplipiBindingConstants {

    public static final String BINDING_ID = "ambianceamplipi";

    public static final ThingTypeUID THING_TYPE_CONTROLLER = new ThingTypeUID(BINDING_ID, "controller");
    public static final ThingTypeUID THING_TYPE_ZONE = new ThingTypeUID(BINDING_ID, "zone");
    public static final ThingTypeUID THING_TYPE_GROUP = new ThingTypeUID(BINDING_ID, "group");

    // controller channels
    public static final String CHANNEL_STATION = "station";
    public static final String CHANNEL_CONTROL = "control";
    public static final String CHANNEL_MASTER_VOLUME = "masterVolume";
    public static final String CHANNEL_MASTER_MUTE = "masterMute";
    public static final String CHANNEL_TITLE = "title";
    public static final String CHANNEL_ARTIST = "artist";
    public static final String CHANNEL_TRACK = "track";
    public static final String CHANNEL_COVER = "cover";
    public static final String CHANNEL_SIREN = "siren";
    public static final String CHANNEL_ANNOUNCE = "announce";
    public static final String CHANNEL_ANNOUNCE_QUEUE = "announceQueue";
    public static final String CHANNEL_ANNOUNCE_VOLUME = "announceVolume";
    public static final String CHANNEL_CLEAR_ANNOUNCEMENTS = "clearAnnouncements";
    public static final String CHANNEL_HEALTH = "health";
    public static final String CHANNEL_HEALTH_OK = "healthOk";
    public static final String CHANNEL_SOURCE = "source";
    public static final String CHANNEL_SLEEP = "sleepTimer";
    public static final String CHANNEL_CPU = "cpuPercent";
    public static final String CHANNEL_MEMORY = "memoryPercent";
    public static final String CHANNEL_DISK = "diskPercent";
    public static final String CHANNEL_TEMPERATURE = "temperature";

    // zone + group channels
    public static final String CHANNEL_POWER = "power";
    public static final String CHANNEL_VOLUME = "volume";
    public static final String CHANNEL_MUTE = "mute";
    public static final String CHANNEL_NAME = "name";

    // config parameters
    public static final String CFG_HOSTNAME = "hostname";
    public static final String CFG_PORT = "port";
    public static final String CFG_ID = "id";
    public static final String CFG_NAME = "name";
}
