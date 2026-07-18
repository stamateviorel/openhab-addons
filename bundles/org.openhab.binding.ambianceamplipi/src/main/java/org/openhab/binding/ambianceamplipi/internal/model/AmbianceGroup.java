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

/**
 * One zone group from {@code /api/status}: derived state over its member zones
 * (average volume, all-muted, all-powered).
 *
 * @author Stamate Viorel - Initial contribution
 */
public class AmbianceGroup {
    public String name;
    public List<Integer> zones = List.of();
    public int vol; // average of the member zone volumes
    public boolean mute; // all members muted
    public boolean power; // all members powered
}
