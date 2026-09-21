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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
import eu.chargetime.ocpp.v201.feature.function.ServerSmartChargingEventHandler;
import eu.chargetime.ocpp.v201.feature.function.ServerTransactionsEventHandler;
import eu.chargetime.ocpp.v201.model.messages.AuthorizeRequest;
import eu.chargetime.ocpp.v201.model.messages.AuthorizeResponse;
import eu.chargetime.ocpp.v201.model.messages.BootNotificationRequest;
import eu.chargetime.ocpp.v201.model.messages.BootNotificationResponse;
import eu.chargetime.ocpp.v201.model.messages.ClearedChargingLimitRequest;
import eu.chargetime.ocpp.v201.model.messages.ClearedChargingLimitResponse;
import eu.chargetime.ocpp.v201.model.messages.DataTransferRequest;
import eu.chargetime.ocpp.v201.model.messages.DataTransferResponse;
import eu.chargetime.ocpp.v201.model.messages.HeartbeatRequest;
import eu.chargetime.ocpp.v201.model.messages.HeartbeatResponse;
import eu.chargetime.ocpp.v201.model.messages.LogStatusNotificationRequest;
import eu.chargetime.ocpp.v201.model.messages.LogStatusNotificationResponse;
import eu.chargetime.ocpp.v201.model.messages.MeterValuesRequest;
import eu.chargetime.ocpp.v201.model.messages.MeterValuesResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyChargingLimitRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyChargingLimitResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyCustomerInformationRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyCustomerInformationResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyDisplayMessagesRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyDisplayMessagesResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyEVChargingNeedsRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyEVChargingNeedsResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyEVChargingScheduleRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyEVChargingScheduleResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyEventRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyEventResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyMonitoringReportRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyMonitoringReportResponse;
import eu.chargetime.ocpp.v201.model.messages.NotifyReportRequest;
import eu.chargetime.ocpp.v201.model.messages.NotifyReportResponse;
import eu.chargetime.ocpp.v201.model.messages.ReportChargingProfilesRequest;
import eu.chargetime.ocpp.v201.model.messages.ReportChargingProfilesResponse;
import eu.chargetime.ocpp.v201.model.messages.SecurityEventNotificationRequest;
import eu.chargetime.ocpp.v201.model.messages.SecurityEventNotificationResponse;
import eu.chargetime.ocpp.v201.model.messages.SignCertificateRequest;
import eu.chargetime.ocpp.v201.model.messages.SignCertificateResponse;
import eu.chargetime.ocpp.v201.model.messages.StatusNotificationRequest;
import eu.chargetime.ocpp.v201.model.messages.StatusNotificationResponse;
import eu.chargetime.ocpp.v201.model.messages.TransactionEventRequest;
import eu.chargetime.ocpp.v201.model.messages.TransactionEventResponse;
import eu.chargetime.ocpp.v201.model.types.AuthorizationStatusEnum;
import eu.chargetime.ocpp.v201.model.types.ConnectorStatusEnum;
import eu.chargetime.ocpp.v201.model.types.DataTransferStatusEnum;
import eu.chargetime.ocpp.v201.model.types.EVSE;
import eu.chargetime.ocpp.v201.model.types.GenericStatusEnum;
import eu.chargetime.ocpp.v201.model.types.IdToken;
import eu.chargetime.ocpp.v201.model.types.IdTokenEnum;
import eu.chargetime.ocpp.v201.model.types.IdTokenInfo;
import eu.chargetime.ocpp.v201.model.types.NotifyEVChargingNeedsStatusEnum;
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
        ServerAvailabilityEventHandler, ServerMeterValuesEventHandler, ServerAuthorizationEventHandler,
        ServerDataTransferEventHandler, ServerSecurityEventHandler, ServerDisplayMessageEventHandler,
        ServerDiagnosticsEventHandler, ServerSmartChargingEventHandler {

    // A station's replay window is minutes, so a few hundred remembered transactions is generous.
    private static final int REMEMBERED_TRANSACTIONS = 256;
    private static final int TRANSACTION_ENDED = Integer.MAX_VALUE;

    private final Logger logger = LoggerFactory.getLogger(Ocpp201InboundHandler.class);
    private final OcppServerListener listener;
    // 2.0.1 transaction ids are station-chosen strings; the binding's numeric ids are mapped here.
    private final Map<String, Integer> transactionIds = new ConcurrentHashMap<>();
    // A TransactionEvent need not repeat the EVSE after Started.
    private final Map<String, Integer> transactionConnectors = new ConcurrentHashMap<>();
    private final Map<String, DeviceModelReport> reports = new ConcurrentHashMap<>();
    // Socket to charger id, so transaction state can be keyed per charger and survive a reconnect.
    private final Map<UUID, String> sessionIdentity = new ConcurrentHashMap<>();
    // Bounded because an entry outlives the transaction it guards; see isReplay.
    private final Map<String, Integer> lastSeqNo = Collections
            .synchronizedMap(new BoundedMap<>(REMEMBERED_TRANSACTIONS));

    public Ocpp201InboundHandler(OcppServerListener listener) {
        this.listener = listener;
    }

    @Override
    @NonNullByDefault({})
    public BootNotificationResponse handleBootNotificationRequest(UUID sessionIndex, BootNotificationRequest request) {
        logger.debug("BootNotification (2.0.1) from session {}: reason={}", sessionIndex, request.getReason());
        // A reboot ends every transaction the charger had open.
        forgetTransactions(sessionIndex);
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
        // The chunks of one report can arrive on different threads, so absorbing a chunk, reading the
        // finished report and dropping it have to happen under one lock.
        AtomicReference<Map<String, String>> complete = new AtomicReference<>();
        reports.compute(key, (ignored, pending) -> {
            DeviceModelReport report = pending != null ? pending : new DeviceModelReport();
            if (!report.add(request)) {
                return report;
            }
            complete.set(report.asConfigurationKeys());
            return null;
        });
        Map<String, String> keys = complete.get();
        if (keys != null) {
            deliver("NotifyReport", sessionIndex, () -> listener.onCapabilities(sessionIndex, keys));
        }
        return new NotifyReportResponse();
    }

    @Override
    @NonNullByDefault({})
    public DataTransferResponse handleDataTransferRequest(UUID sessionIndex, DataTransferRequest request) {
        logger.debug("DataTransfer from session {} vendor {} message {}: {}", sessionIndex, request.getVendorId(),
                request.getMessageId(), request.getData());
        return new DataTransferResponse(DataTransferStatusEnum.UnknownVendorId);
    }

    @Override
    @NonNullByDefault({})
    public SecurityEventNotificationResponse handleSecurityEventNotificationRequest(UUID sessionIndex,
            SecurityEventNotificationRequest request) {
        logger.debug("Security event from session {}: {} at {} ({})", sessionIndex, request.getType(),
                request.getTimestamp(), request.getTechInfo());
        return new SecurityEventNotificationResponse();
    }

    @Override
    @NonNullByDefault({})
    public SignCertificateResponse handleSignCertificateRequest(UUID sessionIndex, SignCertificateRequest request) {
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
        logger.debug("Log upload on session {}: {}", sessionIndex, request.getStatus());
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

    // The binding registers the smart-charging function for its own SetChargingProfile, which makes the
    // five below its inbound half; a registered function that cannot answer one replies CALLERROR.
    @Override
    @NonNullByDefault({})
    public NotifyChargingLimitResponse handleNotifyChargingLimitRequest(UUID sessionIndex,
            NotifyChargingLimitRequest request) {
        logger.debug("NotifyChargingLimit from session {} evse {}: {}", sessionIndex, request.getEvseId(),
                request.getChargingLimit() == null ? null : request.getChargingLimit().getChargingLimitSource());
        return new NotifyChargingLimitResponse();
    }

    @Override
    @NonNullByDefault({})
    public ClearedChargingLimitResponse handleClearedChargingLimitRequest(UUID sessionIndex,
            ClearedChargingLimitRequest request) {
        logger.debug("ClearedChargingLimit from session {} evse {}: {}", sessionIndex, request.getEvseId(),
                request.getChargingLimitSource());
        return new ClearedChargingLimitResponse();
    }

    @Override
    @NonNullByDefault({})
    public ReportChargingProfilesResponse handleReportChargingProfilesRequest(UUID sessionIndex,
            ReportChargingProfilesRequest request) {
        logger.debug("ReportChargingProfiles from session {} evse {} request {}: {} profile(s)", sessionIndex,
                request.getEvseId(), request.getRequestId(),
                request.getChargingProfile() == null ? 0 : request.getChargingProfile().length);
        return new ReportChargingProfilesResponse();
    }

    @Override
    @NonNullByDefault({})
    public NotifyEVChargingNeedsResponse handleNotifyEVChargingNeedsRequest(UUID sessionIndex,
            NotifyEVChargingNeedsRequest request) {
        logger.debug("NotifyEVChargingNeeds from session {} evse {}", sessionIndex, request.getEvseId());
        // Accepted would promise the ISO 15118 schedule the car asked for; the binding negotiates none.
        return new NotifyEVChargingNeedsResponse(NotifyEVChargingNeedsStatusEnum.Rejected);
    }

    @Override
    @NonNullByDefault({})
    public NotifyEVChargingScheduleResponse handleNotifyEVChargingScheduleRequest(UUID sessionIndex,
            NotifyEVChargingScheduleRequest request) {
        logger.debug("NotifyEVChargingSchedule from session {} evse {}", sessionIndex, request.getEvseId());
        return new NotifyEVChargingScheduleResponse(GenericStatusEnum.Accepted);
    }

    /** Ties a session to its charger id; without one the socket id stands in. */
    public void bindSession(UUID session, @Nullable String chargePointId) {
        if (chargePointId != null) {
            sessionIdentity.put(session, chargePointId);
        }
    }

    /** Drops half-received reports and the session binding when a socket goes away; transaction state outlives it. */
    public void forget(UUID session) {
        String prefix = session + "/";
        reports.keySet().removeIf(key -> key.startsWith(prefix));
        sessionIdentity.remove(session);
    }

    private void forgetTransactions(UUID session) {
        String prefix = identity(session) + "/";
        transactionIds.keySet().removeIf(key -> key.startsWith(prefix));
        transactionConnectors.keySet().removeIf(key -> key.startsWith(prefix));
        synchronized (lastSeqNo) {
            lastSeqNo.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    private String identity(UUID session) {
        String bound = sessionIdentity.get(session);
        return bound != null ? bound : session.toString();
    }

    @Override
    @NonNullByDefault({})
    public StatusNotificationResponse handleStatusNotificationRequest(UUID sessionIndex,
            StatusNotificationRequest request) {
        logger.debug("StatusNotification (2.0.1) from session {} evse {}: {}", sessionIndex, request.getEvseId(),
                request.getConnectorStatus());
        // Occupied maps to PREPARING and would regress the state the open transaction already reported.
        if (request.getConnectorStatus() == ConnectorStatusEnum.Occupied
                && hasOpenTransaction(sessionIndex, request.getEvseId())) {
            return new StatusNotificationResponse();
        }
        deliver("StatusNotification", sessionIndex,
                () -> listener.onStatusNotification(sessionIndex, Ocpp201Events.toStatusInfo(request)));
        return new StatusNotificationResponse();
    }

    private boolean hasOpenTransaction(UUID session, @Nullable Integer evseId) {
        if (evseId == null) {
            return false;
        }
        String prefix = identity(session) + "/";
        for (Map.Entry<String, Integer> entry : transactionConnectors.entrySet()) {
            if (entry.getKey().startsWith(prefix) && evseId.equals(entry.getValue())) {
                return true;
            }
        }
        return false;
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

        // A plug-first transaction starts without a token and presents it in a later Updated event.
        boolean authorized = idToken == null || listener.isTagAuthorized(idToken);
        // Refused before the replay guard records it, so a retransmitted refused start is refused again.
        if (!authorized && kind == TransactionEvent.Kind.STARTED) {
            logger.debug("TransactionEvent {} from session {} tx {} refused", kind, sessionIndex, remoteId);
            TransactionEventResponse refused = new TransactionEventResponse();
            refused.setIdTokenInfo(new IdTokenInfo(AuthorizationStatusEnum.Invalid));
            return refused;
        }
        if (remoteId != null && isReplay(sessionIndex, remoteId, request.getSeqNo())) {
            logger.debug("TransactionEvent {} for {} seq {} already seen (offline={}); not counted again", kind,
                    remoteId, request.getSeqNo(), request.getOffline());
            return authorizationOf(idToken, authorized);
        }
        int transactionId = idFor(sessionIndex, remoteId);
        if (transactionId <= 0) {
            logger.warn("TransactionEvent {} from session {} tx {} refused: no transaction id available", kind,
                    sessionIndex, remoteId);
            TransactionEventResponse refused = new TransactionEventResponse();
            refused.setIdTokenInfo(new IdTokenInfo(AuthorizationStatusEnum.Invalid));
            return refused;
        }
        logger.debug("TransactionEvent {} from session {} tx {} -> id {} ({})", kind, sessionIndex, remoteId,
                transactionId, authorized ? "accepted" : "invalid");

        Integer connectorId = connectorOf(sessionIndex, request.getEvse(), remoteId, transactionId);
        ConnectorStatus chargingState = info == null ? null : Ocpp201Events.toConnectorStatus(info.getChargingState());
        Integer meterWh = meterWhOf(request);
        String reason = info == null || info.getStoppedReason() == null ? null : info.getStoppedReason().name();
        // A token refused on a later event must not become the session's owner downstream.
        TransactionEvent event = new TransactionEvent(kind, connectorId, transactionId, remoteId,
                authorized ? idToken : null, Ocpp201Events.toTokenType(typeOf(request.getIdToken())), meterWh,
                request.getTimestamp(), reason, chargingState);
        deliver("TransactionEvent", sessionIndex, () -> listener.onTransactionEvent(sessionIndex, event));
        if (connectorId != null) {
            // A 2.0.1 TransactionEvent carries meter values and charging state on every kind, not only
            // Updated.
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
            lastSeqNo.put(key, TRANSACTION_ENDED);
        }

        return authorizationOf(idToken, authorized);
    }

    private static TransactionEventResponse authorizationOf(@Nullable String idToken, boolean authorized) {
        TransactionEventResponse response = new TransactionEventResponse();
        if (idToken != null) {
            response.setIdTokenInfo(
                    new IdTokenInfo(authorized ? AuthorizationStatusEnum.Accepted : AuthorizationStatusEnum.Invalid));
        }
        return response;
    }

    /**
     * 2.0.1 numbers a transaction's events from 0 and a station replays whatever it could not deliver,
     * so the watermark has to outlive the transaction that it guards. Only a reboot clears it: a station
     * that restarted may legitimately hand out a transaction id it has used before.
     */
    private boolean isReplay(UUID session, String remoteId, @Nullable Integer seqNo) {
        if (seqNo == null) {
            return false;
        }
        String key = key(session, remoteId);
        // Handler bodies run concurrently, so the read and the write must share the map's lock.
        synchronized (lastSeqNo) {
            Integer previous = lastSeqNo.get(key);
            if (previous != null && seqNo <= previous) {
                return true;
            }
            lastSeqNo.put(key, seqNo);
            return false;
        }
    }

    private int idFor(UUID session, @Nullable String remoteId) {
        if (remoteId == null) {
            return listener.nextTransactionId();
        }
        String key = key(session, remoteId);
        Integer cached = transactionIds.get(key);
        if (cached != null) {
            return cached;
        }
        Integer known = listener.knownTransactionId(session, remoteId);
        int id = known != null ? known : listener.nextTransactionId();
        if (id <= 0) {
            return id;
        }
        Integer raced = transactionIds.putIfAbsent(key, id);
        return raced != null ? raced : id;
    }

    private @Nullable Integer connectorOf(UUID session, @Nullable EVSE evse, @Nullable String remoteId,
            int transactionId) {
        if (evse != null) {
            if (remoteId != null) {
                Integer evseId = evse.getId();
                if (evseId != null) {
                    transactionConnectors.put(key(session, remoteId), evseId);
                }
            }
            return evse.getId();
        }
        Integer remembered = remoteId == null ? null : transactionConnectors.get(key(session, remoteId));
        return remembered != null ? remembered : listener.knownConnector(session, transactionId);
    }

    /** A 2.0.1 transaction id is chosen by the station, so two chargers can pick the same one. */
    private String key(UUID session, String remoteId) {
        return identity(session) + "/" + remoteId;
    }

    private static @Nullable Integer meterWhOf(TransactionEventRequest request) {
        List<MeterSample.Block> blocks = Ocpp201Events.toMeterSample(0, request.getMeterValue()).blocks();
        for (int i = blocks.size() - 1; i >= 0; i--) {
            for (MeterSample.Reading reading : blocks.get(i).readings()) {
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
            logger.warn("Failed to process {} from session {}", what, session, e);
        }
    }

    /** Access-ordered map that drops its least recently used entry once it is over capacity. */
    private static final class BoundedMap<K, V> extends LinkedHashMap<K, V> {

        private static final long serialVersionUID = 1L;

        private final int capacity;

        BoundedMap(int capacity) {
            super(16, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        @NonNullByDefault({})
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > capacity;
        }
    }
}
