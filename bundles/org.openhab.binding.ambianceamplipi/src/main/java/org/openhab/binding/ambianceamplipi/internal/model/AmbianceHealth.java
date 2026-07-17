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
 * DTO for the {@code health} object in the Ambiance AmpliPi {@code /api/status} response.
 * The controller self-heals a dropped stream / a wedged preamp on its own; this reports the
 * outcome so openHAB can surface an alert.
 *
 * @author Stamate Viorel - Initial contribution
 */
public class AmbianceHealth {
    public boolean ok = true;
    public List<String> issues = List.of();
    public String mpd = "ok";
    public String preamp = "ok";
    public int recoveries;
    public int checked;
}
