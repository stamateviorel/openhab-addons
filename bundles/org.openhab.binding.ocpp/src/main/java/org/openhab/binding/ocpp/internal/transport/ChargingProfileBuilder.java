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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import eu.chargetime.ocpp.model.core.ChargingProfile;
import eu.chargetime.ocpp.model.core.ChargingProfileKindType;
import eu.chargetime.ocpp.model.core.ChargingProfilePurposeType;
import eu.chargetime.ocpp.model.core.ChargingRateUnitType;
import eu.chargetime.ocpp.model.core.ChargingSchedule;
import eu.chargetime.ocpp.model.core.ChargingSchedulePeriod;
import eu.chargetime.ocpp.model.smartcharging.ClearChargingProfileRequest;
import eu.chargetime.ocpp.model.smartcharging.SetChargingProfileRequest;

/**
 * Builds the OCPP 1.6 SetChargingProfile / ClearChargingProfile requests for a connector cap.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ChargingProfileBuilder {

    static final int TX_DEFAULT_SUFFIX = 1;
    static final int TX_PROFILE_SUFFIX = 2;
    static final int CHARGE_POINT_MAX_SUFFIX = 3;
    /** An Absolute schedule starts this far back, so a charger clock a little behind never sees it in the future. */
    static final long ABSOLUTE_START_LEAD_SECONDS = 60;
    private static final int PROFILE_ID_STRIDE = 10;
    private static final int STACK_LEVEL = 0;

    private ChargingProfileBuilder() {
    }

    public static SetChargingProfileRequest limit(int connectorId, ChargingRateUnitType unit, double value,
            @Nullable Integer numberPhases, boolean forceTxDefault, @Nullable Integer transactionId) {
        return limit(connectorId, unit, value, numberPhases, forceTxDefault, transactionId, false,
                ZonedDateTime.now(ZoneOffset.UTC));
    }

    /**
     * With {@code chargePointMax} the cap goes out as a ChargePointMaxProfile on connectorId 0: OCPP's cap for the
     * whole charge point, which bounds every connector at once, running transactions included. It is Absolute,
     * since it has no transaction to be relative to, and so carries a startSchedule.
     */
    public static SetChargingProfileRequest limit(int connectorId, ChargingRateUnitType unit, double value,
            @Nullable Integer numberPhases, boolean forceTxDefault, @Nullable Integer transactionId,
            boolean chargePointMax, ZonedDateTime now) {
        boolean useTxProfile = !chargePointMax && transactionId != null && !forceTxDefault;
        ChargingProfilePurposeType purpose = chargePointMax ? ChargingProfilePurposeType.ChargePointMaxProfile
                : useTxProfile ? ChargingProfilePurposeType.TxProfile : ChargingProfilePurposeType.TxDefaultProfile;

        ChargingSchedulePeriod period = new ChargingSchedulePeriod(0, value);
        if (numberPhases != null) {
            period.setNumberPhases(numberPhases);
        }
        ChargingSchedule schedule = new ChargingSchedule(unit, new ChargingSchedulePeriod[] { period });
        if (chargePointMax) {
            // OCPP 1.6: an Absolute schedule without a startSchedule is invalid
            schedule.setStartSchedule(
                    now.withZoneSameInstant(ZoneOffset.UTC).withNano(0).minusSeconds(ABSOLUTE_START_LEAD_SECONDS));
        }
        int id = chargePointMax ? connectorId * PROFILE_ID_STRIDE + CHARGE_POINT_MAX_SUFFIX
                : profileId(connectorId, useTxProfile);
        ChargingProfile profile = new ChargingProfile(id, STACK_LEVEL, purpose,
                chargePointMax ? ChargingProfileKindType.Absolute : ChargingProfileKindType.Relative, schedule);
        if (useTxProfile) {
            profile.setTransactionId(transactionId);
        }
        // A ChargePointMaxProfile can only be set on connectorId 0 (OCPP 1.6, 3.13.2)
        return new SetChargingProfileRequest(chargePointMax ? 0 : connectorId, profile);
    }

    public static ClearChargingProfileRequest clearLimit(int connectorId) {
        return clearLimit(connectorId, false);
    }

    /**
     * Clears what {@link #limit} with the same {@code chargePointMax} set. A ChargePointMaxProfile is stored in the
     * charger and outlives every transaction, so it is cleared on connectorId 0 by its purpose; left there it would
     * cap every later charge.
     */
    public static ClearChargingProfileRequest clearLimit(int connectorId, boolean chargePointMax) {
        ClearChargingProfileRequest request = new ClearChargingProfileRequest();
        if (chargePointMax) {
            request.setConnectorId(0);
            request.setChargingProfilePurpose(ChargingProfilePurposeType.ChargePointMaxProfile);
        } else {
            request.setConnectorId(connectorId);
        }
        request.setStackLevel(STACK_LEVEL);
        return request;
    }

    public static ClearChargingProfileRequest clearDefaultLimit(int connectorId) {
        ClearChargingProfileRequest request = clearLimit(connectorId);
        request.setChargingProfilePurpose(ChargingProfilePurposeType.TxDefaultProfile);
        return request;
    }

    static int profileId(int connectorId, boolean txProfile) {
        return connectorId * PROFILE_ID_STRIDE + (txProfile ? TX_PROFILE_SUFFIX : TX_DEFAULT_SUFFIX);
    }
}
