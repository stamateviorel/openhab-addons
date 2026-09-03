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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

import eu.chargetime.ocpp.v201.model.messages.ChangeAvailabilityRequest;
import eu.chargetime.ocpp.v201.model.messages.ClearChargingProfileResponse;
import eu.chargetime.ocpp.v201.model.messages.DataTransferRequest;
import eu.chargetime.ocpp.v201.model.messages.GetLocalListVersionResponse;
import eu.chargetime.ocpp.v201.model.messages.RequestStopTransactionRequest;
import eu.chargetime.ocpp.v201.model.messages.ResetRequest;
import eu.chargetime.ocpp.v201.model.messages.ResetResponse;
import eu.chargetime.ocpp.v201.model.messages.SendLocalListRequest;
import eu.chargetime.ocpp.v201.model.messages.SetChargingProfileRequest;
import eu.chargetime.ocpp.v201.model.messages.SetChargingProfileResponse;
import eu.chargetime.ocpp.v201.model.messages.SetVariablesRequest;
import eu.chargetime.ocpp.v201.model.messages.SetVariablesResponse;
import eu.chargetime.ocpp.v201.model.types.AuthorizationStatusEnum;
import eu.chargetime.ocpp.v201.model.types.ChargingProfilePurposeEnum;
import eu.chargetime.ocpp.v201.model.types.ChargingProfileStatusEnum;
import eu.chargetime.ocpp.v201.model.types.ChargingRateUnitEnum;
import eu.chargetime.ocpp.v201.model.types.ClearChargingProfileStatusEnum;
import eu.chargetime.ocpp.v201.model.types.Component;
import eu.chargetime.ocpp.v201.model.types.OperationalStatusEnum;
import eu.chargetime.ocpp.v201.model.types.ResetEnum;
import eu.chargetime.ocpp.v201.model.types.ResetStatusEnum;
import eu.chargetime.ocpp.v201.model.types.SetVariableResult;
import eu.chargetime.ocpp.v201.model.types.SetVariableStatusEnum;
import eu.chargetime.ocpp.v201.model.types.UpdateEnum;
import eu.chargetime.ocpp.v201.model.types.Variable;

/**
 * The OCPP 2.0.1 shape of the outbound commands.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings({ "null" })
class Ocpp201CommandsTest {

    private final Ocpp201Commands commands = new Ocpp201Commands();

    @Test
    void aStopQuotesTheChargersOwnTransactionId() {
        // 2.0.1 identifies a transaction by the string the charger chose, not the binding's number.
        RequestStopTransactionRequest request = assertInstanceOf(RequestStopTransactionRequest.class,
                commands.remoteStop(77, "charger-side-id"));

        assertEquals("charger-side-id", request.getTransactionId());
    }

    @Test
    void aStopFallsBackToTheNumericIdWhenTheChargerNeverGaveOne() {
        RequestStopTransactionRequest request = assertInstanceOf(RequestStopTransactionRequest.class,
                commands.remoteStop(77, null));

        assertEquals("77", request.getTransactionId());
    }

    @Test
    void aChargeLimitTargetsTheEvseAndKeepsTheUnitItWasGiven() {
        SetChargingProfileRequest amps = assertInstanceOf(SetChargingProfileRequest.class,
                commands.setChargingProfile(2, 16, false, 3, false, null, null));

        assertEquals(2, amps.getEvseId());
        assertEquals(ChargingRateUnitEnum.A, amps.getChargingProfile().getChargingSchedule()[0].getChargingRateUnit());
        assertEquals(16, amps.getChargingProfile().getChargingSchedule()[0].getChargingSchedulePeriod()[0].getLimit());
        assertEquals(3,
                amps.getChargingProfile().getChargingSchedule()[0].getChargingSchedulePeriod()[0].getNumberPhases());

        SetChargingProfileRequest watts = assertInstanceOf(SetChargingProfileRequest.class,
                commands.setChargingProfile(2, 11000, true, 0, false, null, null));

        assertEquals(ChargingRateUnitEnum.W, watts.getChargingProfile().getChargingSchedule()[0].getChargingRateUnit());
    }

    @Test
    void aLimitInsideATransactionIsATxProfileCarryingTheChargersId() {
        SetChargingProfileRequest request = assertInstanceOf(SetChargingProfileRequest.class,
                commands.setChargingProfile(1, 16, false, 0, false, 55, "charger-side-id"));

        assertEquals(ChargingProfilePurposeEnum.TxProfile, request.getChargingProfile().getChargingProfilePurpose());
        assertEquals("charger-side-id", request.getChargingProfile().getTransactionId());
    }

    @Test
    void forcingATxDefaultProfileOverridesTheTransaction() {
        SetChargingProfileRequest request = assertInstanceOf(SetChargingProfileRequest.class,
                commands.setChargingProfile(1, 16, false, 0, true, 55, "charger-side-id"));

        assertEquals(ChargingProfilePurposeEnum.TxDefaultProfile,
                request.getChargingProfile().getChargingProfilePurpose());
    }

    @Test
    void aResetLeavesAChargeInProgressAlone() {
        // 2.0.1 split reset into Immediate and OnIdle; the 1.6 soft reset behaved like OnIdle.
        assertEquals(ResetEnum.OnIdle, assertInstanceOf(ResetRequest.class, commands.reset()).getType());
    }

    @Test
    void availabilityTargetsTheEvse() {
        ChangeAvailabilityRequest request = assertInstanceOf(ChangeAvailabilityRequest.class,
                commands.changeAvailability(2, false));

        assertEquals(OperationalStatusEnum.Inoperative, request.getOperationalStatus());
        assertEquals(2, request.getEvse().getId());
    }

    @Test
    void aKnownSettingIsWrittenToItsDeviceModelVariable() {
        SetVariablesRequest request = assertInstanceOf(SetVariablesRequest.class,
                commands.setConfiguration("MeterValueSampleInterval", "15"));

        assertEquals("SampledDataCtrlr", request.getSetVariableData()[0].getComponent().getName());
        assertEquals("TxUpdatedInterval", request.getSetVariableData()[0].getVariable().getName());
        assertEquals("15", request.getSetVariableData()[0].getAttributeValue());
    }

    @Test
    void anExtraSettingCanNameItsOwnComponentAndVariable() {
        SetVariablesRequest request = assertInstanceOf(SetVariablesRequest.class,
                commands.setConfiguration("SecurityCtrlr.OrganizationName", "interni"));

        assertEquals("SecurityCtrlr", request.getSetVariableData()[0].getComponent().getName());
        assertEquals("OrganizationName", request.getSetVariableData()[0].getVariable().getName());
    }

    @Test
    void aSettingWithNoDeviceModelEquivalentIsSkippedRatherThanSentWrong() {
        // A bare 1.6 key the mapping does not know cannot be addressed in the component tree.
        assertNull(commands.setConfiguration("SomeVendorKey", "1"));
    }

    @Test
    void aVariableWriteCountsOnlyIfEveryResultAccepted() {
        assertTrue(commands.isAccepted(
                new SetVariablesResponse(new SetVariableResult[] { result(SetVariableStatusEnum.Accepted) })));
        assertTrue(commands.isAccepted(
                new SetVariablesResponse(new SetVariableResult[] { result(SetVariableStatusEnum.RebootRequired) })));
        assertFalse(commands.isAccepted(new SetVariablesResponse(new SetVariableResult[] {
                result(SetVariableStatusEnum.Accepted), result(SetVariableStatusEnum.UnknownVariable) })));
    }

    private static SetVariableResult result(SetVariableStatusEnum status) {
        return new SetVariableResult(status, new Component("SampledDataCtrlr"), new Variable("TxUpdatedInterval"));
    }

    @Test
    void aCustomMessageIsSentAsADataTransfer() {
        DataTransferRequest request = assertInstanceOf(DataTransferRequest.class,
                commands.customMessage("Alfen", "SetSetting", "{\"key\":1}"));

        assertEquals("Alfen", request.getVendorId());
        assertEquals("SetSetting", request.getMessageId());
        assertEquals("{\"key\":1}", request.getData());
    }

    @Test
    void aCustomMessageNeedsOnlyAVendor() {
        DataTransferRequest request = assertInstanceOf(DataTransferRequest.class,
                commands.customMessage("Alfen", null, null));

        assertEquals("Alfen", request.getVendorId());
        assertNull(request.getMessageId());
        assertNull(request.getData());
    }

    @Test
    void theLocalListIsSentWithEveryTokenAccepted() {
        SendLocalListRequest request = assertInstanceOf(SendLocalListRequest.class,
                commands.sendLocalList(7, List.of("CARD1", "CARD2")));

        assertEquals(7, request.getVersionNumber());
        assertEquals(UpdateEnum.Full, request.getUpdateType());
        assertEquals(2, request.getLocalAuthorizationList().length);
        assertEquals("CARD1", request.getLocalAuthorizationList()[0].getIdToken().getIdToken());
        assertEquals(AuthorizationStatusEnum.Accepted,
                request.getLocalAuthorizationList()[0].getIdTokenInfo().getStatus());
    }

    @Test
    void theReportedLocalListVersionIsReadBack() {
        assertEquals(9, commands.localListVersionOf(new GetLocalListVersionResponse(9)));
        assertNull(commands.localListVersionOf(new ResetResponse(ResetStatusEnum.Accepted)));
    }

    @Test
    void aClearThatFindsNoProfileStillCounts() {
        // The connector was already uncapped, which is the state the caller asked for.
        ClearChargingProfileResponse unknown = new ClearChargingProfileResponse(ClearChargingProfileStatusEnum.Unknown);

        assertTrue(commands.isAccepted(unknown));
    }

    @Test
    void aRejectedProfileIsNotAccepted() {
        assertFalse(commands.isAccepted(new SetChargingProfileResponse(ChargingProfileStatusEnum.Rejected)));
        assertTrue(commands.isAccepted(new SetChargingProfileResponse(ChargingProfileStatusEnum.Accepted)));
    }
}
