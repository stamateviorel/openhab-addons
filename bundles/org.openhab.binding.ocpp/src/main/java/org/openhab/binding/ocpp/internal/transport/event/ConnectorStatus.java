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
 * Connector status, protocol-neutral. The labels are the OCPP 1.6 wire names and are published
 * verbatim on the status channel, so they must not be renamed.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum ConnectorStatus {

    AVAILABLE("Available"),
    PREPARING("Preparing"),
    CHARGING("Charging"),
    SUSPENDED_EV("SuspendedEV"),
    SUSPENDED_EVSE("SuspendedEVSE"),
    FINISHING("Finishing"),
    RESERVED("Reserved"),
    UNAVAILABLE("Unavailable"),
    FAULTED("Faulted");

    private final String label;

    ConnectorStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
