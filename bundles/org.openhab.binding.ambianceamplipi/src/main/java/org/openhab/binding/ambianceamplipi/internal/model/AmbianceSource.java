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
 * The playback-source block of {@code /api/status}: which source (radio, spotify, ...) owns
 * the audio path, and which are available (extensible on the controller side).
 *
 * @author Stamate Viorel - Initial contribution
 */
public class AmbianceSource {
    public String active;
    public List<String> available = List.of();
}
