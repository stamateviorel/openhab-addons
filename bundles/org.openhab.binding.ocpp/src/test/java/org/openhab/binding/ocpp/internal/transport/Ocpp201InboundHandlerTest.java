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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.ocpp.internal.transport.event.ConnectorStatus;
import org.openhab.binding.ocpp.internal.transport.event.MeterSample;
import org.openhab.binding.ocpp.internal.transport.event.StatusInfo;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;
import org.openhab.binding.ocpp.internal.transport.event.TransactionEvent;

import eu.chargetime.ocpp.v201.model.messages.BootNotificationRequest;
import eu.chargetime.ocpp.v201.model.messages.StatusNotificationRequest;
import eu.chargetime.ocpp.v201.model.messages.TransactionEventRequest;
import eu.chargetime.ocpp.v201.model.messages.TransactionEventResponse;
import eu.chargetime.ocpp.v201.model.types.AuthorizationStatusEnum;
import eu.chargetime.ocpp.v201.model.types.ChargingStateEnum;
import eu.chargetime.ocpp.v201.model.types.ChargingStation;
import eu.chargetime.ocpp.v201.model.types.ConnectorStatusEnum;
import eu.chargetime.ocpp.v201.model.types.EVSE;
import eu.chargetime.ocpp.v201.model.types.IdToken;
import eu.chargetime.ocpp.v201.model.types.IdTokenEnum;
import eu.chargetime.ocpp.v201.model.types.MeasurandEnum;
import eu.chargetime.ocpp.v201.model.types.MeterValue;
import eu.chargetime.ocpp.v201.model.types.PhaseEnum;
import eu.chargetime.ocpp.v201.model.types.SampledValue;
import eu.chargetime.ocpp.v201.model.types.Transaction;
import eu.chargetime.ocpp.v201.model.types.TransactionEventEnum;
import eu.chargetime.ocpp.v201.model.types.TriggerReasonEnum;
import eu.chargetime.ocpp.v201.model.types.UnitOfMeasure;

/**
 * Translation of inbound OCPP 2.0.1 messages onto the binding's protocol-neutral events.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings({ "null" })
class Ocpp201InboundHandlerTest {

    private @NonNullByDefault({}) OcppServerListener listener;
    private @NonNullByDefault({}) Ocpp201InboundHandler handler;
    private final UUID session = UUID.randomUUID();
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void setUp() {
        listener = mock(OcppServerListener.class);
        when(listener.isTagAuthorized(any())).thenReturn(true);
        when(listener.heartbeatFor(any())).thenReturn(300);
        // Mockito answers 0 for a boxed Integer, which would read as a known transaction.
        when(listener.knownTransactionId(any(), any())).thenReturn(null);
        when(listener.knownConnector(any(), anyInt())).thenReturn(null);
        AtomicInteger sequence = new AtomicInteger();
        when(listener.nextTransactionId()).thenAnswer(invocation -> sequence.incrementAndGet());
        handler = new Ocpp201InboundHandler(listener);
    }

    @Test
    void bootNotificationReportsTheChargingStationIdentity() {
        ChargingStation station = new ChargingStation("model", "vendor");
        station.setFirmwareVersion("1.2.3");
        station.setSerialNumber("SER1");

        handler.handleBootNotificationRequest(session,
                new BootNotificationRequest(station, eu.chargetime.ocpp.v201.model.types.BootReasonEnum.PowerUp));

        var captor = ArgumentCaptor.forClass(org.openhab.binding.ocpp.internal.transport.event.BootInfo.class);
        verify(listener).onBootNotification(eq(session), captor.capture());
        assertEquals("vendor", captor.getValue().vendor());
        assertEquals("model", captor.getValue().model());
        assertEquals("1.2.3", captor.getValue().firmwareVersion());
        assertEquals("SER1", captor.getValue().serialNumber());
    }

    @Test
    void anOccupiedConnectorIsReportedAsPreparingUntilTheTransactionSaysMore() {
        // 2.0.1 has five connector states where 1.6 has nine; Occupied only says a vehicle is there.
        StatusNotificationRequest request = new StatusNotificationRequest(ZonedDateTime.now(ZoneOffset.UTC),
                ConnectorStatusEnum.Occupied, 2, 1);

        handler.handleStatusNotificationRequest(session, request);

        ArgumentCaptor<StatusInfo> captor = ArgumentCaptor.forClass(StatusInfo.class);
        verify(listener).onStatusNotification(eq(session), captor.capture());
        assertEquals(2, captor.getValue().connectorId());
        assertEquals(ConnectorStatus.PREPARING, captor.getValue().status());
    }

    @Test
    void aStartedTransactionKeepsItsIdWhenItEnds() {
        handler.handleTransactionEventRequest(session, transaction(TransactionEventEnum.Started, "abc", null));
        handler.handleTransactionEventRequest(session, transaction(TransactionEventEnum.Ended, "abc", null));

        ArgumentCaptor<TransactionEvent> captor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(listener, times(2)).onTransactionEvent(eq(session), captor.capture());
        List<TransactionEvent> events = captor.getAllValues();
        assertEquals(TransactionEvent.Kind.STARTED, events.get(0).kind());
        assertEquals(TransactionEvent.Kind.ENDED, events.get(1).kind());
        assertEquals(events.get(0).transactionId(), events.get(1).transactionId(),
                "both halves of one transaction must log under the same id");
        assertEquals("abc", events.get(0).remoteId(), "the charger's own id has to survive for RequestStopTransaction");
    }

    @Test
    void twoTransactionsGetDifferentIds() {
        handler.handleTransactionEventRequest(session, transaction(TransactionEventEnum.Started, "one", null));
        handler.handleTransactionEventRequest(session, transaction(TransactionEventEnum.Started, "two", null));

        ArgumentCaptor<TransactionEvent> captor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(listener, times(2)).onTransactionEvent(eq(session), captor.capture());
        assertEquals(2, captor.getAllValues().stream().map(TransactionEvent::transactionId).distinct().count());
    }

    @Test
    void anEventReplayedAfterAnOutageIsNotCountedTwice() {
        // A charger numbers a transaction's events from zero and re-sends any it could not deliver
        // while offline; counting one twice would double it in the usage log.
        handler.handleTransactionEventRequest(session, seq(TransactionEventEnum.Started, "abc", 0));
        handler.handleTransactionEventRequest(session, seq(TransactionEventEnum.Updated, "abc", 1));
        handler.handleTransactionEventRequest(session, seq(TransactionEventEnum.Updated, "abc", 1));

        verify(listener, times(2)).onTransactionEvent(eq(session), any());
    }

    @Test
    void aFreshTransactionStartsCountingAgain() {
        handler.handleTransactionEventRequest(session, seq(TransactionEventEnum.Started, "abc", 0));
        handler.handleTransactionEventRequest(session, seq(TransactionEventEnum.Ended, "abc", 1));
        handler.handleTransactionEventRequest(session, seq(TransactionEventEnum.Started, "abc", 0));

        verify(listener, times(3)).onTransactionEvent(eq(session), any());
    }

    @Test
    void aVehicleRecognisedByItsMacIsReportedAsAVehicle() {
        // AutoCharge presents the car's MAC where a card would be; 2.0.1 says which it is.
        TransactionEventRequest request = transaction(TransactionEventEnum.Started, "abc", null);
        request.setIdToken(new IdToken("001122334455", IdTokenEnum.MacAddress));

        handler.handleTransactionEventRequest(session, request);

        ArgumentCaptor<TransactionEvent> captor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(listener).onTransactionEvent(eq(session), captor.capture());
        assertEquals(TokenType.VEHICLE, captor.getValue().tokenType());
        assertEquals("001122334455", captor.getValue().idToken());
    }

    @Test
    void aCardIsReportedAsACardWhicheverReaderStandardItUses() {
        TransactionEventRequest request = transaction(TransactionEventEnum.Started, "abc", null);
        request.setIdToken(new IdToken("04318BCA682095", IdTokenEnum.ISO15693));

        handler.handleTransactionEventRequest(session, request);

        ArgumentCaptor<TransactionEvent> captor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(listener).onTransactionEvent(eq(session), captor.capture());
        assertEquals(TokenType.CARD, captor.getValue().tokenType());
    }

    @Test
    void aTokenTheChargerDoesNotClassifyIsNotGuessedAt() {
        TransactionEventRequest request = transaction(TransactionEventEnum.Started, "abc", null);
        request.setIdToken(new IdToken("free-vend", IdTokenEnum.Local));

        handler.handleTransactionEventRequest(session, request);

        ArgumentCaptor<TransactionEvent> captor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(listener).onTransactionEvent(eq(session), captor.capture());
        assertEquals(TokenType.OTHER, captor.getValue().tokenType());
    }

    @Test
    void aStartFromAnUnknownTokenIsNotRoutedOnwards() {
        when(listener.isTagAuthorized(any())).thenReturn(false);

        handler.handleTransactionEventRequest(session, transaction(TransactionEventEnum.Started, "abc", null));

        verify(listener, never()).onTransactionEvent(any(), any());
    }

    @Test
    void anUpdateCarriesTheReadingsThatUsedToArriveAsMeterValues() {
        SampledValue sample = new SampledValue(14.2);
        sample.setMeasurand(MeasurandEnum.CurrentImport);
        sample.setPhase(PhaseEnum.L1);
        sample.setUnitOfMeasure(new UnitOfMeasure().withUnit("A"));

        handler.handleTransactionEventRequest(session,
                transaction(TransactionEventEnum.Updated, "abc", new SampledValue[] { sample }));

        ArgumentCaptor<MeterSample> captor = ArgumentCaptor.forClass(MeterSample.class);
        verify(listener).onMeterValues(eq(session), captor.capture());
        MeterSample.Reading reading = captor.getValue().blocks().get(0).readings().get(0);
        // The enums serialise to the OCPP names the channel mapping keys off, not the Java names.
        assertEquals("Current.Import", reading.measurand());
        assertEquals("L1", reading.phase());
        assertEquals("A", reading.unit());
        assertEquals("14.2", reading.value());
    }

    @Test
    void aUnitMultiplierIsAppliedToTheReading() {
        // 2.0.1 may report 12 kWh as value 12 with multiplier 3; 1.6 had no multiplier at all.
        SampledValue sample = new SampledValue(12d);
        sample.setMeasurand(MeasurandEnum.EnergyActiveImportRegister);
        sample.setUnitOfMeasure(new UnitOfMeasure().withUnit("Wh").withMultiplier(3));

        handler.handleTransactionEventRequest(session,
                transaction(TransactionEventEnum.Updated, "abc", new SampledValue[] { sample }));

        ArgumentCaptor<MeterSample> captor = ArgumentCaptor.forClass(MeterSample.class);
        verify(listener).onMeterValues(eq(session), captor.capture());
        assertEquals("12000", captor.getValue().blocks().get(0).readings().get(0).value());
    }

    @Test
    void theChargingStateBecomesTheConnectorStatus() {
        TransactionEventRequest request = transaction(TransactionEventEnum.Updated, "abc", null);
        request.getTransactionInfo().setChargingState(ChargingStateEnum.SuspendedEVSE);

        handler.handleTransactionEventRequest(session, request);

        ArgumentCaptor<StatusInfo> captor = ArgumentCaptor.forClass(StatusInfo.class);
        verify(listener).onStatusNotification(eq(session), captor.capture());
        assertEquals(ConnectorStatus.SUSPENDED_EVSE, captor.getValue().status());
    }

    @Test
    void aTransactionWithNoIdStillGetsOne() {
        TransactionEventRequest request = transaction(TransactionEventEnum.Started, null, null);

        handler.handleTransactionEventRequest(session, request);

        ArgumentCaptor<TransactionEvent> captor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(listener).onTransactionEvent(eq(session), captor.capture());
        assertEquals("", captor.getValue().remoteId());
    }

    private TransactionEventRequest seq(TransactionEventEnum kind, String transactionId, int seqNo) {
        TransactionEventRequest request = transaction(kind, transactionId, null);
        request.setSeqNo(seqNo);
        return request;
    }

    @Test
    void aPlugFirstStartWithoutATokenIsDeliveredWithoutBeingAskedAbout() {
        when(listener.isTagAuthorized(any())).thenReturn(false);
        TransactionEventRequest request = transaction(TransactionEventEnum.Started, "abc", null);
        request.setIdToken(null);

        TransactionEventResponse response = handler.handleTransactionEventRequest(session, request);

        assertNull(response.getIdTokenInfo());
        verify(listener, never()).isTagAuthorized(any());
        ArgumentCaptor<TransactionEvent> captor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(listener).onTransactionEvent(eq(session), captor.capture());
        assertEquals(TransactionEvent.Kind.STARTED, captor.getValue().kind());
        assertNull(captor.getValue().idToken());
    }

    @Test
    void aRefusedTokenOnALaterEventIsAnsweredButTheEventStillCounts() {
        when(listener.isTagAuthorized("CARD1")).thenReturn(false);
        TransactionEventRequest started = transaction(TransactionEventEnum.Started, "abc", null);
        started.setIdToken(null);
        handler.handleTransactionEventRequest(session, started);

        TransactionEventResponse response = handler.handleTransactionEventRequest(session,
                transaction(TransactionEventEnum.Ended, "abc", null));

        assertEquals(AuthorizationStatusEnum.Invalid, response.getIdTokenInfo().getStatus());
        ArgumentCaptor<TransactionEvent> captor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(listener, times(2)).onTransactionEvent(eq(session), captor.capture());
        assertEquals(TransactionEvent.Kind.ENDED, captor.getAllValues().get(1).kind());
    }

    @Test
    void aRefusedTokenKeepsASessionFromStarting() {
        when(listener.isTagAuthorized("CARD1")).thenReturn(false);

        TransactionEventResponse response = handler.handleTransactionEventRequest(session,
                transaction(TransactionEventEnum.Started, "abc", null));

        assertEquals(AuthorizationStatusEnum.Invalid, response.getIdTokenInfo().getStatus());
        verify(listener, never()).onTransactionEvent(any(), any());
    }

    @Test
    void theEnergyRegisterIsTakenInWattHoursWhateverUnitItCameIn() {
        SampledValue perPhase = new SampledValue(999d);
        perPhase.setMeasurand(MeasurandEnum.EnergyActiveImportRegister);
        perPhase.setPhase(PhaseEnum.L1);
        SampledValue total = new SampledValue(1.5);
        total.setMeasurand(MeasurandEnum.EnergyActiveImportRegister);
        total.setUnitOfMeasure(new UnitOfMeasure().withUnit("kWh"));

        handler.handleTransactionEventRequest(session,
                transaction(TransactionEventEnum.Started, "abc", new SampledValue[] { perPhase, total }));

        ArgumentCaptor<TransactionEvent> captor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(listener).onTransactionEvent(eq(session), captor.capture());
        assertEquals(1500, captor.getValue().meterWh());
    }

    @Test
    void anUpdateWithoutAnEvseIsPlacedOnTheConnectorTheTransactionStartedOn() {
        handler.handleTransactionEventRequest(session, transaction(TransactionEventEnum.Started, "abc", null));
        TransactionEventRequest update = transaction(TransactionEventEnum.Updated, "abc", null);
        update.setEvse(null);
        update.getTransactionInfo().setChargingState(ChargingStateEnum.Charging);

        handler.handleTransactionEventRequest(session, update);

        verify(listener).onStatusNotification(eq(session),
                argThat(status -> status.connectorId() == 1 && status.status() == ConnectorStatus.CHARGING));
    }

    @Test
    void aTransactionThatBeganBeforeARestartKeepsItsIdAndConnector() {
        when(listener.knownTransactionId(session, "abc")).thenReturn(42);
        when(listener.knownConnector(session, 42)).thenReturn(2);
        TransactionEventRequest update = transaction(TransactionEventEnum.Updated, "abc", null);
        update.setEvse(null);
        update.getTransactionInfo().setChargingState(ChargingStateEnum.SuspendedEV);

        handler.handleTransactionEventRequest(session, update);

        ArgumentCaptor<TransactionEvent> captor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(listener).onTransactionEvent(eq(session), captor.capture());
        assertEquals(42, captor.getValue().transactionId());
        assertEquals(Integer.valueOf(2), captor.getValue().connectorId());
        verify(listener, never()).nextTransactionId();
    }

    private TransactionEventRequest transaction(TransactionEventEnum kind, @Nullable String transactionId,
            SampledValue @Nullable [] samples) {
        Transaction info = new Transaction(transactionId == null ? "" : transactionId);
        // A charger numbers a transaction's events in order; reusing one would look like a replay.
        TransactionEventRequest request = new TransactionEventRequest(kind, ZonedDateTime.now(ZoneOffset.UTC),
                TriggerReasonEnum.Authorized, sequence.getAndIncrement(), info);
        IdToken idToken = new IdToken("CARD1", IdTokenEnum.ISO14443);
        request.setIdToken(idToken);
        EVSE evse = new EVSE(1);
        request.setEvse(evse);
        if (samples != null) {
            MeterValue meterValue = new MeterValue(samples, ZonedDateTime.now(ZoneOffset.UTC));
            request.setMeterValue(new MeterValue[] { meterValue });
        }
        return request;
    }
}
