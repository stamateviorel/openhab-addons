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

/**
 * OCPP 1.6 carries only the token value (always {@link #UNKNOWN}); 2.0.1 also names its kind.
 *
 * OCPP 1.6 carries only the value, so a token from a 1.6 charger is {@link #UNKNOWN} whatever it
 * 
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum TokenType {

    /** An RFID card, of either reader standard. */
    CARD,
    /** A vehicle identified by its MAC address, as AutoCharge does. */
    VEHICLE,
    /** A key code, a local id, or a token the CSMS itself supplied. */
    OTHER,
    UNKNOWN
}
