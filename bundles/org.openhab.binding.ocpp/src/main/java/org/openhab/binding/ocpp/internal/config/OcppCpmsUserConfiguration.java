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
package org.openhab.binding.ocpp.internal.config;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration for a {@code cpms-user} — a person, their RFID cards, and an optional monthly kWh cap.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class OcppCpmsUserConfiguration {

    public boolean enabled = true;
    public double monthlyCapKwh = 0;
    public List<String> cards = List.of();
}
