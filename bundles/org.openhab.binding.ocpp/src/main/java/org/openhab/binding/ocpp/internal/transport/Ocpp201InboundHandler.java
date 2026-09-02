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

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ocpp.internal.transport.event.ConnectorStatus;
import org.openhab.binding.ocpp.internal.transport.event.MeterSample;
import org.openhab.binding.ocpp.internal.transport.event.StatusInfo;
import org.openhab.binding.ocpp.internal.transport.event.TransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.chargetime.ocpp.v201.feature.function.ServerAuthorizationEventHandler;
import eu.chargetime.ocpp.v201.feature.function.ServerAvailabilityEventHandler;
import eu.chargetime.ocpp.v201.feature.function.ServerMeterValuesEventHandler;
import eu.chargetime.ocpp.v201.feature.function.ServerProvisioningEventHandler;
import eu.chargetime.ocpp.v201.feature.function.ServerTransactionsEventHandler;
import eu.chargetime.ocpp.v201.model.messages.AuthorizeRequest;
import eu.chargetime.ocpp.v201.model.messages.AuthorizeResponse;
import eu.chargetime.ocpp.v201.model.messages.BootNotificationRequest;
import eu.chargetime.ocpp.v201.model.messages.BootNotificationResponse;
import eu.chargetime.ocpp.v201.model.messages.HeartbeatRequest;
import eu.chargetime.ocpp.v201.model.messages.HeartbeatResponse;
import eu.chargetime.ocpp.v201.model.messages.MeterValuesRequest;
import eu.chargetime.ocpp.v201.model.messages.MeterValuesResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyEventRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyEventResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyReportRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyReportResponse;
import eu.chargetime.ocpp.v201.model.messages.StatusNotificationRequest;
import eu.chargetime.ocpp.v201.model.messages.StatusNotificationResponse;
import eu.chargetime.ocpp.v201.model.messages.TransactionEventRequest;
import eu.chargetime.ocpp.v201.model.messages.TransactionEventResponse;
import eu.chargetime.ocpp.v201.model.types.AuthorizationStatusEnum;
import eu.chargetime.ocpp.v201.model.types.EVSE;
import eu.chargetime.ocpp.v201.model.types.IdToken;
import eu.chargetime.ocpp.v201.model.types.IdTokenInfo;
import eu.chargetime.ocpp.v201.model.types.RegistrationStatusEnum;
import eu.chargetime.ocpp.v201.model.types.Transaction;

/**
 * Handles inbound OCPP 2.0.1 requests, answering each with a spec-valid response and forwarding
 * events to the {@link OcppServerListener}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class Ocpp201InboundHandler implements ServerProvisioningEventHandler, ServerTransactionsEventHandler,
        ServerAvailabilityEventHandler, ServerMeterValuesEventHandler, ServerAuthorizationEventHandler {

    private final Logger logger = LoggerFactory.getLogger(Ocpp201InboundHandler.class);
    private final OcppServerListener listener;
    // 2.0.1 names transactions with a string the charger picks; the binding logs usage under a
    // number, so each one is given an id on its first event and it is held until the transaction ends.
    private final Map<String, Integer> transactionIds = new ConcurrentHashMap<>();

    public Ocpp201InboundHandler(OcppServerListener listener) {
        this.listener = listener;
    }

    @Override
    @NonNullByDefault({})
    public BootNotificationResponse handleBootNotificationRequest(UUID sessionIndex, BootNotificationRequest request) {
        logger.debug("BootNotification (2.0.1) from session {}: reason={}", sessionIndex, request.getReason());
        deliver("BootNotification", sessionIndex,
                () -> listener.onBootNotification(sessionIndex, Ocpp201Events.toBootInfo(request)));
        return new BootNotificationResponse(ZonedDateTime.now(ZoneOffset.UTC), listener.heartbeatFor(sessionIndex),
                RegistrationStatusEnum.Accepted);
    }

    @Override
    @NonNullByDefault({})
    public HeartbeatResponse handleHeartbeatRequest(UUID sessionIndex, HeartbeatRequest request) {
        logger.trace("Heartbeat (2.0.1) from session {}", sessionIndex);
        deliver("Heartbeat", sessionIndex, () -> listener.onHeartbeat(sessionIndex));
        return new HeartbeatResponse(ZonedDateTime.now(ZoneOffset.UTC));
    }

    @Override
    @NonNullByDefault({})
    public NotifyReportResponse handleNotifyReportRequest(UUID sessionIndex, NotifyReportRequest request) {
        logger.debug("NotifyReport from session {} seq {}", sessionIndex, request.getSeqNo());
        return new NotifyReportResponse();
    }

    @Override
    @NonNullByDefault({})
    public StatusNotificationResponse handleStatusNotificationRequest(UUID sessionIndex,
            StatusNotificationRequest request) {
        logger.debug("StatusNotification (2.0.1) from session {} evse {}: {}", sessionIndex, request.getEvseId(),
                request.getConnectorStatus());
        deliver("StatusNotification", sessionIndex,
                () -> listener.onStatusNotification(sessionIndex, Ocpp201Events.toStatusInfo(request)));
        return new StatusNotificationResponse();
    }

    @Override
    @NonNullByDefault({})
    public NotifyEventResponse handleNotifyEventRequest(UUID sessionIndex, NotifyEventRequest request) {
        logger.debug("NotifyEvent from session {}", sessionIndex);
        return new NotifyEventResponse();
    }

    @Override
    @NonNullByDefault({})
    public MeterValuesResponse handleMeterValuesRequest(UUID sessionIndex, MeterValuesRequest request) {
        Integer evseId = request.getEvseId();
        logger.debug("MeterValues (2.0.1) from session {} evse {}", sessionIndex, evseId);
        deliver("MeterValues", sessionIndex, () -> listener.onMeterValues(sessionIndex,
                Ocpp201Events.toMeterSample(evseId == null ? 0 : evseId, request.getMeterValue())));
        return new MeterValuesResponse();
    }

    @Override
    @NonNullByDefault({})
    public AuthorizeResponse handleAuthorizeRequest(UUID sessionIndex, AuthorizeRequest request) {
        String idToken = tokenOf(request.getIdToken());
        boolean authorized = listener.isTagAuthorized(idToken);
        logger.debug("Authorize (2.0.1) from session {} idToken {} -> {}", sessionIndex, idToken, authorized);
        listener.onAuthorize(sessionIndex, idToken);
        return new AuthorizeResponse(
                new IdTokenInfo(authorized ? AuthorizationStatusEnum.Accepted : AuthorizationStatusEnum.Invalid));
    }

    @Override
    @NonNullByDefault({})
    public TransactionEventResponse handleTransactionEventRequest(UUID sessionIndex, TransactionEventRequest request) {
        Transaction info = request.getTransactionInfo();
        String remoteId = info == null ? null : info.getTransactionId();
        String idToken = tokenOf(request.getIdToken());
        TransactionEvent.Kind kind = switch (request.getEventType()) {
            case Started -> TransactionEvent.Kind.STARTED;
            case Ended -> TransactionEvent.Kind.ENDED;
            case Updated -> TransactionEvent.Kind.UPDATED;
        };

        boolean authorized = kind != TransactionEvent.Kind.STARTED || listener.isTagAuthorized(idToken);
        int transactionId = idFor(remoteId);
        logger.debug("TransactionEvent {} from session {} tx {} -> id {} ({})", kind, sessionIndex, remoteId,
                transactionId, authorized ? "accepted" : "invalid");

        if (authorized) {
            EVSE evse = request.getEvse();
            Integer connectorId = evse == null ? null : evse.getId();
            ConnectorStatus chargingState = info == null ? null
                    : Ocpp201Events.toConnectorStatus(info.getChargingState());
            Integer meterWh = meterWhOf(request);
            String reason = info == null || info.getStoppedReason() == null ? null : info.getStoppedReason().name();
            TransactionEvent event = new TransactionEvent(kind, connectorId, transactionId, remoteId, idToken, meterWh,
                    request.getTimestamp(), reason, chargingState);
            deliver("TransactionEvent", sessionIndex, () -> listener.onTransactionEvent(sessionIndex, event));
            if (connectorId != null) {
                // A transaction event carries what 1.6 sent as separate MeterValues and
                // StatusNotification messages, on every kind and not just an update.
                MeterSample sample = Ocpp201Events.toMeterSample(connectorId, request.getMeterValue());
                if (!sample.blocks().isEmpty()) {
                    deliver("TransactionEvent[MeterValues]", sessionIndex,
                            () -> listener.onMeterValues(sessionIndex, sample));
                }
                if (chargingState != null) {
                    deliver("TransactionEvent[Status]", sessionIndex, () -> listener.onStatusNotification(sessionIndex,
                            new StatusInfo(connectorId, chargingState, null)));
                }
            }
        }
        if (kind == TransactionEvent.Kind.ENDED && remoteId != null) {
            transactionIds.remove(remoteId);
        }

        TransactionEventResponse response = new TransactionEventResponse();
        if (idToken != null) {
            response.setIdTokenInfo(
                    new IdTokenInfo(authorized ? AuthorizationStatusEnum.Accepted : AuthorizationStatusEnum.Invalid));
        }
        return response;
    }

    private int idFor(@Nullable String remoteId) {
        if (remoteId == null) {
            return listener.nextTransactionId();
        }
        return transactionIds.computeIfAbsent(remoteId, key -> listener.nextTransactionId());
    }

    /** The energy register, which is what the usage log and the session-energy channel are built on. */
    private static @Nullable Integer meterWhOf(TransactionEventRequest request) {
        MeterSample sample = Ocpp201Events.toMeterSample(0, request.getMeterValue());
        for (MeterSample.Block block : sample.blocks()) {
            for (MeterSample.Reading reading : block.readings()) {
                String measurand = reading.measurand();
                String value = reading.value();
                if (value != null && (measurand == null || "Energy.Active.Import.Register".equals(measurand))) {
                    try {
                        return (int) Math.round(Double.parseDouble(value));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable String tokenOf(@Nullable IdToken idToken) {
        return idToken == null ? null : idToken.getIdToken();
    }

    /** Deliver an inbound message to the listener without letting a throw there starve the response. */
    private void deliver(String what, UUID session, Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException e) {
            logger.warn("Failed to process {} from session {}: {}", what, session, e.getMessage());
        }
    }
}
