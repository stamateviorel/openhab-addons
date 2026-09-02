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
import org.openhab.binding.ocpp.internal.transport.event.TransactionEvent;

/**
 * Callbacks raised by the {@link OcppTransport} for inbound OCPP traffic, keyed by session id.
 *
 * <p>
 * The events are protocol-neutral: each wire protocol translates its own messages into them, so
 * everything above this interface is shared by every OCPP version the binding speaks.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface OcppServerListener {

    void onSessionOpened(UUID session, @Nullable String chargePointId, @Nullable InetSocketAddress remote,
            OcppVersion version);

    void onSessionClosed(UUID session);

    void onBootNotification(UUID session, BootInfo boot);

    void onStatusNotification(UUID session, StatusInfo status);

    void onMeterValues(UUID session, MeterSample sample);

    void onHeartbeat(UUID session);

    /**
     * Capabilities a charger reported out of band. 1.6 answers GetConfiguration directly, but 2.0.1
     * sends its device model as a separate stream of NotifyReport messages.
     */
    void onCapabilities(UUID session, java.util.Map<String, String> configurationKeys);

    void onTransactionEvent(UUID session, TransactionEvent event);

    void onAuthorize(UUID session, @Nullable String idToken);

    boolean isTagAuthorized(@Nullable String idToken);

    int heartbeatFor(UUID session);

    int nextTransactionId();
}
