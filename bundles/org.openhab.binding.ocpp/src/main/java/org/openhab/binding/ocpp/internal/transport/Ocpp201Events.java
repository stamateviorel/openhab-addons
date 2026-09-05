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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ocpp.internal.transport.event.BootInfo;
import org.openhab.binding.ocpp.internal.transport.event.ConnectorStatus;
import org.openhab.binding.ocpp.internal.transport.event.MeterSample;
import org.openhab.binding.ocpp.internal.transport.event.StatusInfo;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;

import com.google.gson.annotations.SerializedName;

import eu.chargetime.ocpp.v201.model.messages.BootNotificationRequest;
import eu.chargetime.ocpp.v201.model.messages.StatusNotificationRequest;
import eu.chargetime.ocpp.v201.model.types.ChargingStateEnum;
import eu.chargetime.ocpp.v201.model.types.ChargingStation;
import eu.chargetime.ocpp.v201.model.types.ConnectorStatusEnum;
import eu.chargetime.ocpp.v201.model.types.IdTokenEnum;
import eu.chargetime.ocpp.v201.model.types.MeterValue;
import eu.chargetime.ocpp.v201.model.types.SampledValue;
import eu.chargetime.ocpp.v201.model.types.UnitOfMeasure;

/**
 * Translates inbound OCPP 2.0.1 requests into the binding's protocol-neutral events.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class Ocpp201Events {

    private Ocpp201Events() {
    }

    public static BootInfo toBootInfo(BootNotificationRequest request) {
        ChargingStation station = request.getChargingStation();
        if (station == null) {
            return new BootInfo(null, null, null, null);
        }
        return new BootInfo(station.getVendorName(), station.getModel(), station.getFirmwareVersion(),
                station.getSerialNumber());
    }

    /**
     * 2.0.1 addresses an EVSE and, optionally, a connector within it. The binding models one
     * connector per EVSE, as every dual-socket charger seen so far reports EVSE 1 and 2, so the EVSE
     * id is what maps onto the connector Thing.
     */
    public static StatusInfo toStatusInfo(StatusNotificationRequest request) {
        Integer evseId = request.getEvseId();
        // A plain StatusNotification carries no charging detail, unlike the state on a transaction event.
        return new StatusInfo(evseId == null ? 0 : evseId, toConnectorStatus(request.getConnectorStatus()), null, true);
    }

    public static MeterSample toMeterSample(int connectorId, MeterValue @Nullable [] meterValues) {
        List<MeterSample.Block> blocks = new ArrayList<>();
        if (meterValues != null) {
            for (MeterValue meterValue : meterValues) {
                List<MeterSample.Reading> readings = new ArrayList<>();
                SampledValue[] samples = meterValue.getSampledValue();
                if (samples != null) {
                    for (SampledValue sample : samples) {
                        readings.add(toReading(sample));
                    }
                }
                blocks.add(new MeterSample.Block(meterValue.getTimestamp(), readings));
            }
        }
        return new MeterSample(connectorId, blocks);
    }

    private static MeterSample.Reading toReading(SampledValue sample) {
        UnitOfMeasure unitOfMeasure = sample.getUnitOfMeasure();
        String unit = unitOfMeasure == null ? null : unitOfMeasure.getUnit();
        Integer multiplier = unitOfMeasure == null ? null : unitOfMeasure.getMultiplier();
        Double value = sample.getValue();
        String scaled = null;
        if (value != null) {
            BigDecimal decimal = BigDecimal.valueOf(value);
            // 2.0.1 carries a power-of-ten multiplier alongside the unit; 1.6 had none.
            scaled = (multiplier == null ? decimal : decimal.scaleByPowerOfTen(multiplier)).toPlainString();
        }
        return new MeterSample.Reading(wireName(sample.getMeasurand()), wireName(sample.getPhase()), unit, scaled);
    }

    public static @Nullable ConnectorStatus toConnectorStatus(@Nullable ConnectorStatusEnum status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case Available -> ConnectorStatus.AVAILABLE;
            case Reserved -> ConnectorStatus.RESERVED;
            case Unavailable -> ConnectorStatus.UNAVAILABLE;
            case Faulted -> ConnectorStatus.FAULTED;
            // Occupied says a vehicle is present but not what it is doing; the transaction event's
            // charging state refines it moments later.
            case Occupied -> ConnectorStatus.PREPARING;
        };
    }

    /** 2.0.1 moved the detail 1.6 reported as a connector status into the transaction event. */
    public static @Nullable ConnectorStatus toConnectorStatus(@Nullable ChargingStateEnum state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case Charging -> ConnectorStatus.CHARGING;
            case EVConnected -> ConnectorStatus.PREPARING;
            case SuspendedEV -> ConnectorStatus.SUSPENDED_EV;
            case SuspendedEVSE -> ConnectorStatus.SUSPENDED_EVSE;
            case Idle -> ConnectorStatus.FINISHING;
        };
    }

    public static TokenType toTokenType(@Nullable IdTokenEnum type) {
        if (type == null) {
            return TokenType.UNKNOWN;
        }
        return switch (type) {
            case ISO14443, ISO15693 -> TokenType.CARD;
            case MacAddress -> TokenType.VEHICLE;
            default -> TokenType.OTHER;
        };
    }

    /**
     * The measurand and phase enums serialise to the OCPP names the channel mapping keys off, which
     * are not the Java constant names ({@code CurrentImport} vs {@code Current.Import}).
     */
    private static @Nullable String wireName(@Nullable Enum<?> value) {
        if (value == null) {
            return null;
        }
        try {
            Field field = value.getDeclaringClass().getField(value.name());
            SerializedName serialized = field.getAnnotation(SerializedName.class);
            return serialized == null ? value.name() : serialized.value();
        } catch (NoSuchFieldException e) {
            return value.name();
        }
    }
}
