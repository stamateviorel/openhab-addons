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
import java.util.Objects;
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
import eu.chargetime.ocpp.v201.feature.function.ServerDataTransferEventHandler;
import eu.chargetime.ocpp.v201.feature.function.ServerDiagnosticsEventHandler;
import eu.chargetime.ocpp.v201.feature.function.ServerDisplayMessageEventHandler;
import eu.chargetime.ocpp.v201.feature.function.ServerMeterValuesEventHandler;
import eu.chargetime.ocpp.v201.feature.function.ServerProvisioningEventHandler;
import eu.chargetime.ocpp.v201.feature.function.ServerSecurityEventHandler;
import eu.chargetime.ocpp.v201.feature.function.ServerTransactionsEventHandler;
import eu.chargetime.ocpp.v201.model.messages.AuthorizeRequest;
import eu.chargetime.ocpp.v201.model.messages.AuthorizeResponse;
import eu.chargetime.ocpp.v201.model.messages.BootNotificationRequest;
import eu.chargetime.ocpp.v201.model.messages.BootNotificationResponse;
import eu.chargetime.ocpp.v201.model.messages.DataTransferRequest;
import eu.chargetime.ocpp.v201.model.messages.DataTransferResponse;
import eu.chargetime.ocpp.v201.model.messages.HeartbeatRequest;
import eu.chargetime.ocpp.v201.model.messages.HeartbeatResponse;
import eu.chargetime.ocpp.v201.model.messages.LogStatusNotificationRequest;
import eu.chargetime.ocpp.v201.model.messages.LogStatusNotificationResponse;
import eu.chargetime.ocpp.v201.model.messages.MeterValuesRequest;
import eu.chargetime.ocpp.v201.model.messages.MeterValuesResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyCustomerInformationRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyCustomerInformationResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyDisplayMessagesRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyDisplayMessagesResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyEventRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyEventResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyMonitoringReportRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyMonitoringReportResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyReportRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyReportResponse;
import eu.chargetime.ocpp.v201.model.messages.SecurityEventNotificationRequest;
import eu.chargetime.ocpp.v201.model.messages.SecurityEventNotificationResponse;
import eu.chargetime.ocpp.v201.model.messages.SignCertificateRequest;
import eu.chargetime.ocpp.v201.model.messages.SignCertificateResponse;
import eu.chargetime.ocpp.v201.model.messages.StatusNotificationRequest;
import eu.chargetime.ocpp.v201.model.messages.StatusNotificationResponse;
import eu.chargetime.ocpp.v201.model.messages.TransactionEventRequest;
import eu.chargetime.ocpp.v201.model.messages.TransactionEventResponse;
import eu.chargetime.ocpp.v201.model.types.AuthorizationStatusEnum;
import eu.chargetime.ocpp.v201.model.types.DataTransferStatusEnum;
import eu.chargetime.ocpp.v201.model.types.EVSE;
import eu.chargetime.ocpp.v201.model.types.GenericStatusEnum;
import eu.chargetime.ocpp.v201.model.types.IdToken;
import eu.chargetime.ocpp.v201.model.types.IdTokenEnum;
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
public class Ocpp201InboundHandler
        implements ServerProvisioningEventHandler, ServerTransactionsEventHandler, ServerAvailabilityEventHandler,
        ServerMeterValuesEventHandler, ServerAuthorizationEventHandler, ServerDataTransferEventHandler,
        ServerSecurityEventHandler, ServerDisplayMessageEventHandler, ServerDiagnosticsEventHandler {

    private final Logger logger = LoggerFactory.getLogger(Ocpp201InboundHandler.class);
    private final OcppServerListener listener;
    // 2.0.1 names transactions with a string the charger picks; the binding logs usage under a
    // number, so each one is given an id on its first event and it is held until the transaction ends.
    private final Map<String, Integer> transactionIds = new ConcurrentHashMap<>();
    // A charger need not repeat the EVSE on every event of a transaction; this keeps the one it started on.
    private final Map<String, Integer> transactionConnectors = new ConcurrentHashMap<>();
    private final Map<String, DeviceModelReport> reports = new ConcurrentHashMap<>();
    // The last event seen per transaction, so a replayed or duplicated one is not counted twice.
    private final Map<String, Integer> lastSeqNo = new ConcurrentHashMap<>();

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
        logger.debug("NotifyReport from session {} seq {} tbc {}", sessionIndex, request.getSeqNo(), request.getTbc());
        // A charger can have more than one report in flight; requestId says which this belongs to.
        String key = sessionIndex + "/" + request.getRequestId();
        DeviceModelReport report = Objects
                .requireNonNull(reports.computeIfAbsent(key, ignored -> new DeviceModelReport()));
        boolean complete = report.add(request);
        if (complete) {
            reports.remove(key);
            Map<String, String> keys = report.asConfigurationKeys();
            deliver("NotifyReport", sessionIndex, () -> listener.onCapabilities(sessionIndex, keys));
        }
        return new NotifyReportResponse();
    }

    @Override
    @NonNullByDefault({})
    public DataTransferResponse handleDataTransferRequest(UUID sessionIndex, DataTransferRequest request) {
        // Vendor-specific traffic the binding has no meaning for; answered so the charger is not
        // left waiting, and logged so it can be seen.
        logger.debug("DataTransfer from session {} vendor {} message {}: {}", sessionIndex, request.getVendorId(),
                request.getMessageId(), request.getData());
        return new DataTransferResponse(DataTransferStatusEnum.UnknownVendorId);
    }

    @Override
    @NonNullByDefault({})
    public SecurityEventNotificationResponse handleSecurityEventNotificationRequest(UUID sessionIndex,
            SecurityEventNotificationRequest request) {
        logger.info("Security event from session {}: {} at {} ({})", sessionIndex, request.getType(),
                request.getTimestamp(), request.getTechInfo());
        return new SecurityEventNotificationResponse();
    }

    @Override
    @NonNullByDefault({})
    public SignCertificateResponse handleSignCertificateRequest(UUID sessionIndex, SignCertificateRequest request) {
        // Signing a charger's certificate needs a CA this binding does not have.
        logger.debug("SignCertificate from session {} refused — no certificate authority", sessionIndex);
        return new SignCertificateResponse(GenericStatusEnum.Rejected);
    }

    @Override
    @NonNullByDefault({})
    public NotifyDisplayMessagesResponse handleNotifyDisplayMessagesRequest(UUID sessionIndex,
            NotifyDisplayMessagesRequest request) {
        logger.debug("NotifyDisplayMessages from session {} request {}: {} message(s)", sessionIndex,
                request.getRequestId(), request.getMessageInfo() == null ? 0 : request.getMessageInfo().length);
        return new NotifyDisplayMessagesResponse();
    }

    @Override
    @NonNullByDefault({})
    public LogStatusNotificationResponse handleLogStatusNotificationRequest(UUID sessionIndex,
            LogStatusNotificationRequest request) {
        logger.info("Log upload on session {}: {}", sessionIndex, request.getStatus());
        return new LogStatusNotificationResponse();
    }

    @Override
    @NonNullByDefault({})
    public NotifyCustomerInformationResponse handleNotifyCustomerInformationRequest(UUID sessionIndex,
            NotifyCustomerInformationRequest request) {
        logger.debug("CustomerInformation from session {} request {}", sessionIndex, request.getRequestId());
        return new NotifyCustomerInformationResponse();
    }

    @Override
    @NonNullByDefault({})
    public NotifyMonitoringReportResponse handleNotifyMonitoringReportRequest(UUID sessionIndex,
            NotifyMonitoringReportRequest request) {
        logger.debug("MonitoringReport from session {} request {} seq {}", sessionIndex, request.getRequestId(),
                request.getSeqNo());
        return new NotifyMonitoringReportResponse();
    }

    /** Drops half-received reports and per-transaction state when a session goes away. */
    public void forget(UUID session) {
        String prefix = session + "/";
        reports.keySet().removeIf(key -> key.startsWith(prefix));
        transactionIds.keySet().removeIf(key -> key.startsWith(prefix));
        transactionConnectors.keySet().removeIf(key -> key.startsWith(prefix));
        lastSeqNo.keySet().removeIf(key -> key.startsWith(prefix));
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
        listener.onAuthorize(sessionIndex, idToken, Ocpp201Events.toTokenType(typeOf(request.getIdToken())));
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

        if (remoteId != null && isReplay(sessionIndex, remoteId, request.getSeqNo())) {
            logger.debug("TransactionEvent {} for {} seq {} already seen; not counted again", kind, remoteId,
                    request.getSeqNo());
            return new TransactionEventResponse();
        }
        // A plug-first session starts with no token and presents one in a later update, so whichever
        // event carries a token is the one that is answered; without one there is nothing to refuse.
        boolean authorized = idToken == null || listener.isTagAuthorized(idToken);
        if (!authorized && kind == TransactionEvent.Kind.STARTED) {
            logger.debug("TransactionEvent {} from session {} tx {} refused", kind, sessionIndex, remoteId);
            TransactionEventResponse refused = new TransactionEventResponse();
            refused.setIdTokenInfo(new IdTokenInfo(AuthorizationStatusEnum.Invalid));
            return refused;
        }
        int transactionId = idFor(sessionIndex, remoteId);
        logger.debug("TransactionEvent {} from session {} tx {} -> id {} ({})", kind, sessionIndex, remoteId,
                transactionId, authorized ? "accepted" : "invalid");

        // A refusal on a later event is answered but the event itself still counts, or the transaction
        // would never be seen to end; only a refused start is turned away, above.
        Integer connectorId = connectorOf(sessionIndex, request.getEvse(), remoteId, transactionId);
        ConnectorStatus chargingState = info == null ? null : Ocpp201Events.toConnectorStatus(info.getChargingState());
        Integer meterWh = meterWhOf(request);
        String reason = info == null || info.getStoppedReason() == null ? null : info.getStoppedReason().name();
        TransactionEvent event = new TransactionEvent(kind, connectorId, transactionId, remoteId, idToken,
                Ocpp201Events.toTokenType(typeOf(request.getIdToken())), meterWh, request.getTimestamp(), reason,
                chargingState);
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
        if (kind == TransactionEvent.Kind.ENDED && remoteId != null) {
            String key = key(sessionIndex, remoteId);
            transactionIds.remove(key);
            transactionConnectors.remove(key);
            lastSeqNo.remove(key);
        }

        TransactionEventResponse response = new TransactionEventResponse();
        if (idToken != null) {
            response.setIdTokenInfo(
                    new IdTokenInfo(authorized ? AuthorizationStatusEnum.Accepted : AuthorizationStatusEnum.Invalid));
        }
        return response;
    }

    /**
     * A charger numbers the events of a transaction from zero and replays any it could not deliver
     * while offline, so the same one can arrive twice. Anything not newer than what has already been
     * accounted for is dropped rather than counted again.
     */
    private boolean isReplay(UUID session, String remoteId, @Nullable Integer seqNo) {
        if (seqNo == null) {
            return false;
        }
        String key = key(session, remoteId);
        Integer previous = lastSeqNo.get(key);
        if (previous != null && seqNo <= previous) {
            return true;
        }
        lastSeqNo.put(key, seqNo);
        return false;
    }

    private int idFor(UUID session, @Nullable String remoteId) {
        if (remoteId == null) {
            return listener.nextTransactionId();
        }
        return Objects.requireNonNull(transactionIds.computeIfAbsent(key(session, remoteId), ignored -> {
            // A transaction that began before a restart already has an id on record.
            Integer known = listener.knownTransactionId(session, remoteId);
            return known != null ? known : listener.nextTransactionId();
        }));
    }

    private @Nullable Integer connectorOf(UUID session, @Nullable EVSE evse, @Nullable String remoteId,
            int transactionId) {
        if (evse != null) {
            if (remoteId != null) {
                transactionConnectors.put(key(session, remoteId), evse.getId());
            }
            return evse.getId();
        }
        Integer remembered = remoteId == null ? null : transactionConnectors.get(key(session, remoteId));
        return remembered != null ? remembered : listener.knownConnector(session, transactionId);
    }

    /** A 2.0.1 transaction id is chosen by the station, so two chargers can pick the same one. */
    private static String key(UUID session, String remoteId) {
        return session + "/" + remoteId;
    }

    /** The energy register, which is what the usage log and the session-energy channel are built on. */
    private static @Nullable Integer meterWhOf(TransactionEventRequest request) {
        MeterSample sample = Ocpp201Events.toMeterSample(0, request.getMeterValue());
        for (MeterSample.Block block : sample.blocks()) {
            for (MeterSample.Reading reading : block.readings()) {
                String measurand = reading.measurand();
                String value = reading.value();
                if (value == null || reading.phase() != null
                        || (measurand != null && !"Energy.Active.Import.Register".equals(measurand))) {
                    continue;
                }
                try {
                    double wh = Double.parseDouble(value);
                    // The register is specified in Wh, but a charger is free to report it in kWh.
                    return (int) Math.round("kWh".equals(reading.unit()) ? wh * 1000 : wh);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static @Nullable String tokenOf(@Nullable IdToken idToken) {
        return idToken == null ? null : idToken.getIdToken();
    }

    private static @Nullable IdTokenEnum typeOf(@Nullable IdToken idToken) {
        return idToken == null ? null : idToken.getType();
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
