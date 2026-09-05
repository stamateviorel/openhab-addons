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
package org.openhab.binding.ocpp.internal.transport.event;

import java.time.ZonedDateTime;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * A metering report for one connector, protocol-neutral.
 *
 * <p>
 * Readings stay grouped in the blocks the charger sent them in: aggregation of per-phase samples is
 * only meaningful within a block, and each block carries its own timestamp.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record MeterSample(int connectorId, List<Block> blocks) {

    /** One timestamped group of readings. */
    public record Block(@Nullable ZonedDateTime timestamp, List<Reading> readings) {
    }

    /** One sampled value. A blank measurand means the OCPP default, Energy.Active.Import.Register. */
    public record Reading(@Nullable String measurand, @Nullable String phase, @Nullable String unit,
            @Nullable String value) {
    }
}
