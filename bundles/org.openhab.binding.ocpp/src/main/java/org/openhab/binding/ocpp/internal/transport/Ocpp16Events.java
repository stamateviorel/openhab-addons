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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ocpp.internal.transport.event.BootInfo;
import org.openhab.binding.ocpp.internal.transport.event.ConnectorStatus;
import org.openhab.binding.ocpp.internal.transport.event.MeterSample;
import org.openhab.binding.ocpp.internal.transport.event.StatusInfo;
import org.openhab.binding.ocpp.internal.transport.event.TransactionEvent;

import eu.chargetime.ocpp.model.core.BootNotificationRequest;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import eu.chargetime.ocpp.model.core.MeterValue;
import eu.chargetime.ocpp.model.core.MeterValuesRequest;
import eu.chargetime.ocpp.model.core.SampledValue;
import eu.chargetime.ocpp.model.core.StartTransactionRequest;
import eu.chargetime.ocpp.model.core.StatusNotificationRequest;
import eu.chargetime.ocpp.model.core.StopTransactionRequest;

/**
 * Translates inbound OCPP 1.6 requests into the binding's protocol-neutral events.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class Ocpp16Events {

    private Ocpp16Events() {
    }

    public static BootInfo toBootInfo(BootNotificationRequest request) {
        return new BootInfo(request.getChargePointVendor(), request.getChargePointModel(), request.getFirmwareVersion(),
                request.getChargePointSerialNumber());
    }

    public static StatusInfo toStatusInfo(StatusNotificationRequest request) {
        String errorCode = request.getErrorCode() == null ? null : request.getErrorCode().name();
        return new StatusInfo(connectorOf(request.getConnectorId()), toConnectorStatus(request.getStatus()), errorCode);
    }

    public static MeterSample toMeterSample(MeterValuesRequest request) {
        List<MeterSample.Block> blocks = new ArrayList<>();
        MeterValue[] meterValues = request.getMeterValue();
        if (meterValues != null) {
            for (MeterValue meterValue : meterValues) {
                List<MeterSample.Reading> readings = new ArrayList<>();
                SampledValue[] samples = meterValue.getSampledValue();
                if (samples != null) {
                    for (SampledValue sample : samples) {
                        readings.add(new MeterSample.Reading(sample.getMeasurand(), sample.getPhase(), sample.getUnit(),
                                sample.getValue()));
                    }
                }
                blocks.add(new MeterSample.Block(meterValue.getTimestamp(), readings));
            }
        }
        return new MeterSample(connectorOf(request.getConnectorId()), blocks);
    }

    public static TransactionEvent toStarted(StartTransactionRequest request, int transactionId) {
        return new TransactionEvent(TransactionEvent.Kind.STARTED, request.getConnectorId(), transactionId,
                String.valueOf(transactionId), request.getIdTag(), request.getMeterStart(), request.getTimestamp(),
                null, null);
    }

    /** A 1.6 StopTransaction carries no connector id; the transaction id resolves it upstream. */
    public static TransactionEvent toEnded(StopTransactionRequest request, int transactionId) {
        String reason = request.getReason() == null ? null : request.getReason().name();
        return new TransactionEvent(TransactionEvent.Kind.ENDED, null, transactionId, String.valueOf(transactionId),
                request.getIdTag(), request.getMeterStop(), request.getTimestamp(), reason, null);
    }

    public static @Nullable ConnectorStatus toConnectorStatus(@Nullable ChargePointStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case Available -> ConnectorStatus.AVAILABLE;
            case Preparing -> ConnectorStatus.PREPARING;
            case Charging -> ConnectorStatus.CHARGING;
            case SuspendedEV -> ConnectorStatus.SUSPENDED_EV;
            case SuspendedEVSE -> ConnectorStatus.SUSPENDED_EVSE;
            case Finishing -> ConnectorStatus.FINISHING;
            case Reserved -> ConnectorStatus.RESERVED;
            case Unavailable -> ConnectorStatus.UNAVAILABLE;
            case Faulted -> ConnectorStatus.FAULTED;
        };
    }

    private static int connectorOf(@Nullable Integer connectorId) {
        return connectorId == null ? 0 : connectorId;
    }
}
