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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * One completed charging session in the CPMS log: the card, the resolved user (null if the card is not
 * assigned to anyone), where and when it ran, and the energy delivered.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record CpmsTransaction(String idTag, @Nullable String userId, String chargePointId, int connectorId,
        long startEpoch, long stopEpoch, double energyWh) {
}
