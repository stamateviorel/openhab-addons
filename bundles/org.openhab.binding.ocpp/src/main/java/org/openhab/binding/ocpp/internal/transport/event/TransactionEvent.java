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
 * The start, progress or end of a charging transaction, protocol-neutral.
 *
 * <p>
 * OCPP 1.6 sends StartTransaction and StopTransaction; 2.0.1 sends one TransactionEvent carrying
 * the same three kinds. {@code connectorId} is null where the protocol does not carry it — a 1.6
 * StopTransaction identifies the transaction only by id.
 *
 * <p>
 * {@code transactionId} is the binding's own id, kept numeric because the usage log is persisted
 * under it. {@code remoteId} is the id as the charger states it, which a 2.0.1 RequestStopTransaction
 * has to quote back verbatim. {@code chargingState} is 2.0.1 only: it carries the detail that 1.6
 * puts in StatusNotification.
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
