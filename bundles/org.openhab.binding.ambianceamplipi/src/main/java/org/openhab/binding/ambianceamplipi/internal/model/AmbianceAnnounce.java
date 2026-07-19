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

import org.eclipse.jdt.annotation.Nullable;

/**
 * DTO for the {@code announce} object in {@code GET /api/status}: the announcement queue's
 * depth, whether one is on air, and the default announcement volume (null = untouched).
 *
 * @author Stamate Viorel - Initial contribution
 */
public class AmbianceAnnounce {
    public int queued; // announcements waiting in the FIFO (not yet playing)
    public boolean playing; // an announcement is on air right now
    public @Nullable Integer vol; // default boost level for announcements (null = untouched)
}
