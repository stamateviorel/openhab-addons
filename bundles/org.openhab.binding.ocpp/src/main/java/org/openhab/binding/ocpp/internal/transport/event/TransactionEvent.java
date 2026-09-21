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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * A transaction start, update or end, protocol-neutral. A 1.6 StopTransaction carries no connectorId;
 * {@code remoteId} is the charger's own id for the transaction, which a 2.0.1 RequestStopTransaction has to quote
 * back verbatim; {@code chargingState} is 2.0.1-only.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record TransactionEvent(Kind kind, @Nullable Integer connectorId, int transactionId, @Nullable String remoteId,
        @Nullable String idToken, TokenType tokenType, @Nullable Integer meterWh, @Nullable ZonedDateTime timestamp,
        @Nullable String reason, @Nullable ConnectorStatus chargingState) {

    public enum Kind {
        STARTED,
        UPDATED,
        ENDED
    }
}
