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

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.ocpp.internal.transport.event.ConnectorStatus;
import org.openhab.binding.ocpp.internal.transport.event.MeterSample;
import org.openhab.binding.ocpp.internal.transport.event.StatusInfo;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;
import org.openhab.binding.ocpp.internal.transport.event.TransactionEvent;

import eu.chargetime.ocpp.model.core.ChargePointErrorCode;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import eu.chargetime.ocpp.model.core.MeterValue;
import eu.chargetime.ocpp.model.core.MeterValuesRequest;
import eu.chargetime.ocpp.model.core.Reason;
import eu.chargetime.ocpp.model.core.SampledValue;
import eu.chargetime.ocpp.model.core.StartTransactionRequest;
import eu.chargetime.ocpp.model.core.StatusNotificationRequest;
import eu.chargetime.ocpp.model.core.StopTransactionRequest;

/**
 * Translation of inbound OCPP 1.6 messages onto the binding's protocol-neutral events.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings({ "null" })
class Ocpp16EventsTest {

    @Test
    void everyStatusMapsOntoItsNeutralCounterpartAndKeepsItsWireName() {
        // The channel publishes label(), so the round trip through the neutral enum must be lossless.
        for (ChargePointStatus status : ChargePointStatus.values()) {
            ConnectorStatus mapped = Ocpp16Events.toConnectorStatus(status);
            assertEquals(status.name(), mapped.label(), "wire name must survive for " + status);
        }
        assertNull(Ocpp16Events.toConnectorStatus(null));
    }

    @Test
    void aStatusNotificationCarriesTheConnectorAndTheErrorCode() {
        StatusInfo info = Ocpp16Events.toStatusInfo(
                new StatusNotificationRequest(2, ChargePointErrorCode.PowerMeterFailure, ChargePointStatus.Faulted));

        assertEquals(2, info.connectorId());
        assertEquals(ConnectorStatus.FAULTED, info.status());
        assertEquals("PowerMeterFailure", info.errorCode());
    }

    @Test
    void meterValuesKeepTheirBlocksAndTimestamps() {
        // Aggregation of per-phase samples is only valid within a block, and the connector reports the
        // newest block's timestamp, so the grouping the charger sent has to survive translation.
        ZonedDateTime older = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        ZonedDateTime newer = ZonedDateTime.now(ZoneOffset.UTC);
        SampledValue first = new SampledValue("100");
        first.setMeasurand("Energy.Active.Import.Register");
        first.setUnit("Wh");
        SampledValue second = new SampledValue("14.2");
        second.setMeasurand("Current.Import");
        second.setPhase("L1");
        second.setUnit("A");
        MeterValuesRequest request = new MeterValuesRequest(1);
        request.setMeterValue(new MeterValue[] { new MeterValue(older, new SampledValue[] { first }),
                new MeterValue(newer, new SampledValue[] { second }) });

        MeterSample sample = Ocpp16Events.toMeterSample(request);

        assertEquals(1, sample.connectorId());
        assertEquals(2, sample.blocks().size());
        assertEquals(older, sample.blocks().get(0).timestamp());
        assertEquals(newer, sample.blocks().get(1).timestamp());
        MeterSample.Reading reading = sample.blocks().get(1).readings().get(0);
        assertEquals("Current.Import", reading.measurand());
        assertEquals("L1", reading.phase());
        assertEquals("A", reading.unit());
        assertEquals("14.2", reading.value());
    }

    @Test
    void aStartCarriesTheAssignedIdAsItsOwnWireId() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        TransactionEvent event = Ocpp16Events.toStarted(new StartTransactionRequest(2, "CARD1", 500, now), 77);

        assertEquals(TransactionEvent.Kind.STARTED, event.kind());
        assertEquals(2, event.connectorId());
        assertEquals(77, event.transactionId());
        assertEquals("77", event.remoteId(), "1.6 has no separate charger-side id; the number is it");
        assertEquals("CARD1", event.idToken());
        assertEquals(TokenType.UNKNOWN, event.tokenType(), "1.6 never says what kind of token it was");
        assertEquals(500, event.meterWh());
        assertEquals(now, event.timestamp());
    }

    @Test
    void aStopHasNoConnectorButKeepsTheReason() {
        StopTransactionRequest request = new StopTransactionRequest(1600, ZonedDateTime.now(ZoneOffset.UTC), 77);
        request.setReason(Reason.EVDisconnected);

        TransactionEvent event = Ocpp16Events.toEnded(request, 77);

        assertEquals(TransactionEvent.Kind.ENDED, event.kind());
        assertNull(event.connectorId(), "a 1.6 stop names only the transaction; the connector is resolved upstream");
        assertEquals(1600, event.meterWh());
        assertEquals("EVDisconnected", event.reason());
    }
}
