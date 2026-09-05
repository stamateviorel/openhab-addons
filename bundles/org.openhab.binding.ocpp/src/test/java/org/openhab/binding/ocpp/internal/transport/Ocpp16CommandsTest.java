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

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;

import eu.chargetime.ocpp.model.core.AuthorizationStatus;
import eu.chargetime.ocpp.model.core.AvailabilityStatus;
import eu.chargetime.ocpp.model.core.AvailabilityType;
import eu.chargetime.ocpp.model.core.ChangeAvailabilityConfirmation;
import eu.chargetime.ocpp.model.core.ChangeAvailabilityRequest;
import eu.chargetime.ocpp.model.core.ChangeConfigurationRequest;
import eu.chargetime.ocpp.model.core.ChargingProfilePurposeType;
import eu.chargetime.ocpp.model.core.ChargingRateUnitType;
import eu.chargetime.ocpp.model.core.GetConfigurationRequest;
import eu.chargetime.ocpp.model.core.RemoteStartTransactionRequest;
import eu.chargetime.ocpp.model.core.RemoteStopTransactionRequest;
import eu.chargetime.ocpp.model.core.ResetConfirmation;
import eu.chargetime.ocpp.model.core.ResetRequest;
import eu.chargetime.ocpp.model.core.ResetStatus;
import eu.chargetime.ocpp.model.core.ResetType;
import eu.chargetime.ocpp.model.core.UnlockConnectorRequest;
import eu.chargetime.ocpp.model.localauthlist.GetLocalListVersionConfirmation;
import eu.chargetime.ocpp.model.localauthlist.GetLocalListVersionRequest;
import eu.chargetime.ocpp.model.localauthlist.SendLocalListRequest;
import eu.chargetime.ocpp.model.localauthlist.UpdateType;
import eu.chargetime.ocpp.model.remotetrigger.TriggerMessageRequest;
import eu.chargetime.ocpp.model.remotetrigger.TriggerMessageRequestType;
import eu.chargetime.ocpp.model.smartcharging.ClearChargingProfileConfirmation;
import eu.chargetime.ocpp.model.smartcharging.ClearChargingProfileRequest;
import eu.chargetime.ocpp.model.smartcharging.ClearChargingProfileStatus;
import eu.chargetime.ocpp.model.smartcharging.SetChargingProfileRequest;

/**
 * The OCPP 1.6 shape of the outbound commands — the counterpart of {@link Ocpp201CommandsTest}, so a
 * change to one dialect is caught if it drifts from the other.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings({ "null" })
class Ocpp16CommandsTest {

    private final Ocpp16Commands commands = new Ocpp16Commands();

    @Test
    void aRemoteStartNamesTheConnectorAndTheTag() {
        RemoteStartTransactionRequest request = assertInstanceOf(RemoteStartTransactionRequest.class,
                commands.remoteStart(2, "openhab", TokenType.CARD));

        assertEquals(2, request.getConnectorId());
        assertEquals("openhab", request.getIdTag());
    }

    @Test
    void aRemoteStopUsesTheNumericIdAndIgnoresTheChargersOwn() {
        // 1.6 has no charger-side id; the binding's number is the transaction id on the wire.
        RemoteStopTransactionRequest request = assertInstanceOf(RemoteStopTransactionRequest.class,
                commands.remoteStop(77, "ignored-on-1.6"));

        assertEquals(77, request.getTransactionId());
    }

    @Test
    void aChargeLimitKeepsTheUnitItWasGiven() {
        SetChargingProfileRequest amps = assertInstanceOf(SetChargingProfileRequest.class,
                commands.setChargingProfile(1, 16, false, 3, false, null, null));
        assertEquals(ChargingRateUnitType.A, amps.getCsChargingProfiles().getChargingSchedule().getChargingRateUnit());
        assertEquals(3,
                amps.getCsChargingProfiles().getChargingSchedule().getChargingSchedulePeriod()[0].getNumberPhases());

        SetChargingProfileRequest watts = assertInstanceOf(SetChargingProfileRequest.class,
                commands.setChargingProfile(1, 11000, true, 0, false, null, null));
        assertEquals(ChargingRateUnitType.W, watts.getCsChargingProfiles().getChargingSchedule().getChargingRateUnit());
    }

    @Test
    void aLimitInsideATransactionIsATxProfileUnlessForcedOtherwise() {
        SetChargingProfileRequest tx = assertInstanceOf(SetChargingProfileRequest.class,
                commands.setChargingProfile(1, 16, false, 0, false, 55, null));
        assertEquals(ChargingProfilePurposeType.TxProfile, tx.getCsChargingProfiles().getChargingProfilePurpose());
        assertEquals(55, tx.getCsChargingProfiles().getTransactionId());

        SetChargingProfileRequest forced = assertInstanceOf(SetChargingProfileRequest.class,
                commands.setChargingProfile(1, 16, false, 0, true, 55, null));
        assertEquals(ChargingProfilePurposeType.TxDefaultProfile,
                forced.getCsChargingProfiles().getChargingProfilePurpose());
    }

    @Test
    void aClearTargetsTheConnectorAtTheBindingsStackLevel() {
        ClearChargingProfileRequest request = assertInstanceOf(ClearChargingProfileRequest.class,
                commands.clearChargingProfile(2));

        assertEquals(2, request.getConnectorId());
        assertEquals(0, request.getStackLevel());
    }

    @Test
    void aResetIsSoft() {
        assertEquals(ResetType.Soft, assertInstanceOf(ResetRequest.class, commands.reset()).getType());
    }

    @Test
    void availabilityNamesTheConnector() {
        ChangeAvailabilityRequest request = assertInstanceOf(ChangeAvailabilityRequest.class,
                commands.changeAvailability(2, false));

        assertEquals(2, request.getConnectorId());
        assertEquals(AvailabilityType.Inoperative, request.getType());
    }

    @Test
    void triggersNameTheConnectorAndTheMessage() {
        TriggerMessageRequest meter = assertInstanceOf(TriggerMessageRequest.class, commands.triggerMeterValues(2));
        assertEquals(TriggerMessageRequestType.MeterValues, meter.getRequestedMessage());
        assertEquals(2, meter.getConnectorId());

        TriggerMessageRequest status = assertInstanceOf(TriggerMessageRequest.class,
                commands.triggerStatusNotification(1));
        assertEquals(TriggerMessageRequestType.StatusNotification, status.getRequestedMessage());
    }

    @Test
    void unlockNamesTheConnector() {
        assertEquals(2, assertInstanceOf(UnlockConnectorRequest.class, commands.unlock(2)).getConnectorId());
    }

    @Test
    void capabilitiesAreReadWithGetConfiguration() {
        assertInstanceOf(GetConfigurationRequest.class, commands.readCapabilities());
    }

    @Test
    void aSettingIsWrittenAsAChangeConfigurationUnderItsOwnKey() {
        ChangeConfigurationRequest request = assertInstanceOf(ChangeConfigurationRequest.class,
                commands.setConfiguration("MeterValueSampleInterval", "15"));

        assertEquals("MeterValueSampleInterval", request.getKey());
        assertEquals("15", request.getValue());
    }

    @Test
    void theLocalListIsSentWithEveryTagAccepted() {
        assertInstanceOf(GetLocalListVersionRequest.class, commands.readLocalListVersion());
        SendLocalListRequest request = assertInstanceOf(SendLocalListRequest.class,
                commands.sendLocalList(7, tokens("CARD1", "CARD2")));

        assertEquals(7, request.getListVersion());
        assertEquals(UpdateType.Full, request.getUpdateType());
        assertEquals(2, request.getLocalAuthorizationList().length);
        assertEquals("CARD1", request.getLocalAuthorizationList()[0].getIdTag());
        assertEquals(AuthorizationStatus.Accepted, request.getLocalAuthorizationList()[0].getIdTagInfo().getStatus());
        assertEquals(9, commands.localListVersionOf(new GetLocalListVersionConfirmation(9)));
    }

    @Test
    void theTwoPointOhOneOnlyChannelsHaveNothingToSendOnOnePointSix() {
        assertNull(commands.customMessage("Alfen", "SetSetting", "{}"));
        assertNull(commands.displayMessage("hello"));
    }

    @Test
    void acceptanceReadsEachConfirmationsOwnStatus() {
        assertTrue(commands.isAccepted(new ClearChargingProfileConfirmation(ClearChargingProfileStatus.Unknown)),
                "clearing a profile the charger does not have is still a cleared connector");
        assertTrue(commands.isAccepted(new ResetConfirmation(ResetStatus.Accepted)));
        assertFalse(commands.isAccepted(new ResetConfirmation(ResetStatus.Rejected)));
        assertTrue(commands.isAccepted(new ChangeAvailabilityConfirmation(AvailabilityStatus.Scheduled)),
                "scheduled is the charger agreeing to do it once the connector is free");
    }

    private static Map<String, TokenType> tokens(String... ids) {
        Map<String, TokenType> map = new LinkedHashMap<>();
        for (String id : ids) {
            map.put(id, TokenType.CARD);
        }
        return map;
    }
}
