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

/**
 * The Spotify Connect block of {@code /api/status} (backed by go-librespot on the controller).
 *
 * @author Stamate Viorel - Initial contribution
 */
public class AmbianceSpotify {
    public boolean running; // daemon reachable
    public boolean playing;
    public String track;
    public String artist;
    public String album;
    public String cover; // album art URL (served normalised via /api/cover)
}
