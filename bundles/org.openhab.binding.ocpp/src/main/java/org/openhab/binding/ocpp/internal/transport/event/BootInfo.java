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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Charge point identity reported at boot, protocol-neutral.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record BootInfo(@Nullable String vendor, @Nullable String model, @Nullable String firmwareVersion,
        @Nullable String serialNumber) {
}
