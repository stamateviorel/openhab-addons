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
package org.openhab.binding.ocpp.internal.transport;

import java.net.InetSocketAddress;
import java.util.UUID;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ocpp.internal.transport.event.BootInfo;
import org.openhab.binding.ocpp.internal.transport.event.MeterSample;
import org.openhab.binding.ocpp.internal.transport.event.OcppVersion;
import org.openhab.binding.ocpp.internal.transport.event.StatusInfo;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;
import org.openhab.binding.ocpp.internal.transport.event.TransactionEvent;

/**
 * Callbacks raised by the {@link OcppTransport} for inbound OCPP traffic, keyed by session id.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface OcppServerListener {

    void onSessionOpened(UUID session, @Nullable String chargePointId, @Nullable InetSocketAddress remote,
            OcppVersion version);

    void onSessionClosed(UUID session);

    void onBootNotification(UUID session, BootInfo boot);

    /** Raised once the BootNotification confirmation has been handed to the transport. */
    void onBootConfirmationSent(UUID session);

    void onStatusNotification(UUID session, StatusInfo status);

    void onMeterValues(UUID session, MeterSample sample);

    void onHeartbeat(UUID session);

    /** 2.0.1 reports capabilities asynchronously via NotifyReport, not in a confirmation. */
    void onCapabilities(UUID session, java.util.Map<String, String> configurationKeys);

    void onTransactionEvent(UUID session, TransactionEvent event);

    void onAuthorize(UUID session, @Nullable String idToken, TokenType type);

    boolean isTagAuthorized(@Nullable String idToken);

    int heartbeatFor(UUID session);

    /** A fresh transaction id, or 0 when none can be handed out and the transaction must be refused. */
    int nextTransactionId();

    /** The persisted id of a transaction that began before a restart, if any. */
    @Nullable
    Integer knownTransactionId(UUID session, String remoteId);

    /** The connector a known transaction runs on, for an event that does not say. */
    @Nullable
    Integer knownConnector(UUID session, int transactionId);
}
