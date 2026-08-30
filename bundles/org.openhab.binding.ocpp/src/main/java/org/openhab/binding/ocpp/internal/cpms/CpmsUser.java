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
package org.openhab.binding.ocpp.internal.cpms;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * A CPMS user: a person, whether they may charge, an optional monthly kWh cap (0 = none), and the RFID
 * cards (idTags) that belong to them.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record CpmsUser(String id, String name, boolean enabled, double monthlyCapKwh, List<String> cards) {
}
