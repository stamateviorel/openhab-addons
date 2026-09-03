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

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import eu.chargetime.ocpp.model.Confirmation;
import eu.chargetime.ocpp.model.Request;
import eu.chargetime.ocpp.v201.model.messages.ChangeAvailabilityRequest;
import eu.chargetime.ocpp.v201.model.messages.ChangeAvailabilityResponse;
import eu.chargetime.ocpp.v201.model.messages.ClearChargingProfileRequest;
import eu.chargetime.ocpp.v201.model.messages.ClearChargingProfileResponse;
import eu.chargetime.ocpp.v201.model.messages.RequestStartTransactionRequest;
import eu.chargetime.ocpp.v201.model.messages.RequestStartTransactionResponse;
import eu.chargetime.ocpp.v201.model.messages.RequestStopTransactionRequest;
import eu.chargetime.ocpp.v201.model.messages.RequestStopTransactionResponse;
import eu.chargetime.ocpp.v201.model.messages.ResetRequest;
import eu.chargetime.ocpp.v201.model.messages.SetChargingProfileRequest;
import eu.chargetime.ocpp.v201.model.messages.SetChargingProfileResponse;
import eu.chargetime.ocpp.v201.model.messages.SetVariablesRequest;
import eu.chargetime.ocpp.v201.model.messages.SetVariablesResponse;
import eu.chargetime.ocpp.v201.model.messages.TriggerMessageRequest;
import eu.chargetime.ocpp.v201.model.messages.TriggerMessageResponse;
import eu.chargetime.ocpp.v201.model.messages.UnlockConnectorRequest;
import eu.chargetime.ocpp.v201.model.types.ChargingProfile;
import eu.chargetime.ocpp.v201.model.types.ChargingProfileKindEnum;
import eu.chargetime.ocpp.v201.model.types.ChargingProfilePurposeEnum;
import eu.chargetime.ocpp.v201.model.types.ChargingProfileStatusEnum;
import eu.chargetime.ocpp.v201.model.types.ChargingRateUnitEnum;
import eu.chargetime.ocpp.v201.model.types.ChargingSchedule;
import eu.chargetime.ocpp.v201.model.types.ChargingSchedulePeriod;
import eu.chargetime.ocpp.v201.model.types.ClearChargingProfile;
import eu.chargetime.ocpp.v201.model.types.ClearChargingProfileStatusEnum;
import eu.chargetime.ocpp.v201.model.types.Component;
import eu.chargetime.ocpp.v201.model.types.EVSE;
import eu.chargetime.ocpp.v201.model.types.IdToken;
import eu.chargetime.ocpp.v201.model.types.IdTokenEnum;
import eu.chargetime.ocpp.v201.model.types.MessageTriggerEnum;
import eu.chargetime.ocpp.v201.model.types.OperationalStatusEnum;
import eu.chargetime.ocpp.v201.model.types.RequestStartStopStatusEnum;
import eu.chargetime.ocpp.v201.model.types.ResetEnum;
import eu.chargetime.ocpp.v201.model.types.SetVariableData;
import eu.chargetime.ocpp.v201.model.types.SetVariableResult;
import eu.chargetime.ocpp.v201.model.types.SetVariableStatusEnum;
import eu.chargetime.ocpp.v201.model.types.TriggerMessageStatusEnum;
import eu.chargetime.ocpp.v201.model.types.Variable;

/**
 * Outbound requests in the OCPP 2.0.1 dialect.
 *
 * <p>
 * The binding models one connector per EVSE, so a connector id addresses an EVSE here.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class Ocpp201Commands implements OcppCommands {

    private static final int STACK_LEVEL = 0;
    private static final int PROFILE_ID_STRIDE = 10;
    private final AtomicInteger remoteStartIds = new AtomicInteger();
    private final AtomicInteger reportIds = new AtomicInteger();

    @Override
    public Request remoteStart(int connectorId, String idToken) {
        RequestStartTransactionRequest request = new RequestStartTransactionRequest(
                new IdToken(idToken, IdTokenEnum.ISO14443), remoteStartIds.incrementAndGet());
        request.setEvseId(connectorId);
        return request;
    }

    @Override
    public Request remoteStop(int transactionId, @Nullable String remoteId) {
        // 2.0.1 stops by the charger's own transaction id, not by the one the binding logs under.
        return new RequestStopTransactionRequest(remoteId == null ? String.valueOf(transactionId) : remoteId);
    }

    @Override
    public Request unlock(int connectorId) {
        return new UnlockConnectorRequest(connectorId, 1);
    }

    @Override
    public Request changeAvailability(int connectorId, boolean operative) {
        ChangeAvailabilityRequest request = new ChangeAvailabilityRequest(
                operative ? OperationalStatusEnum.Operative : OperationalStatusEnum.Inoperative);
        request.setEvse(new EVSE(connectorId));
        return request;
    }

    @Override
    public Request reset() {
        // OnIdle leaves a charge in progress alone, which is what a 1.6 soft reset did in practice.
        return new ResetRequest(ResetEnum.OnIdle);
    }

    @Override
    public Request triggerStatusNotification(int connectorId) {
        TriggerMessageRequest request = new TriggerMessageRequest(MessageTriggerEnum.StatusNotification);
        request.setEvse(new EVSE(connectorId));
        return request;
    }

    @Override
    public Request triggerMeterValues(int connectorId) {
        TriggerMessageRequest request = new TriggerMessageRequest(MessageTriggerEnum.MeterValues);
        request.setEvse(new EVSE(connectorId));
        return request;
    }

    @Override
    public Request setChargingProfile(int connectorId, double value, boolean inWatts, int numberPhases,
            boolean txDefault, @Nullable Integer transactionId, @Nullable String remoteId) {
        boolean useTxProfile = transactionId != null && !txDefault;
        ChargingSchedulePeriod period = new ChargingSchedulePeriod(0, value);
        if (numberPhases > 0) {
            period.setNumberPhases(numberPhases);
        }
        ChargingSchedule schedule = new ChargingSchedule(connectorId,
                inWatts ? ChargingRateUnitEnum.W : ChargingRateUnitEnum.A, new ChargingSchedulePeriod[] { period });
        ChargingProfile profile = new ChargingProfile(profileId(connectorId, useTxProfile), STACK_LEVEL,
                useTxProfile ? ChargingProfilePurposeEnum.TxProfile : ChargingProfilePurposeEnum.TxDefaultProfile,
                ChargingProfileKindEnum.Relative, new ChargingSchedule[] { schedule });
        if (useTxProfile && remoteId != null) {
            profile.setTransactionId(remoteId);
        }
        return new SetChargingProfileRequest(connectorId, profile);
    }

    @Override
    public Request clearChargingProfile(int connectorId) {
        ClearChargingProfile criteria = new ClearChargingProfile();
        criteria.setEvseId(connectorId);
        criteria.setStackLevel(STACK_LEVEL);
        ClearChargingProfileRequest request = new ClearChargingProfileRequest();
        request.setChargingProfileCriteria(criteria);
        return request;
    }

    @Override
    public Request readCapabilities() {
        return new eu.chargetime.ocpp.v201.model.messages.GetBaseReportRequest(reportIds.incrementAndGet(),
                eu.chargetime.ocpp.v201.model.types.ReportBaseEnum.FullInventory);
    }

    /**
     * The 1.6 settings the binding pushes, named in the 2.0.1 device model. An extraConfig entry can
     * address anything else by giving its key as {@code Component.Variable}.
     */
    private static final Map<String, String> VARIABLES = Map.of("MeterValueSampleInterval",
            "SampledDataCtrlr.TxUpdatedInterval", "MeterValuesSampledData", "SampledDataCtrlr.TxUpdatedMeasurands",
            "MeterValuesAlignedData", "AlignedDataCtrlr.Measurands", "ClockAlignedDataInterval",
            "AlignedDataCtrlr.Interval", "AuthorizeRemoteTxRequests", "AuthCtrlr.AuthorizeRemoteStart");

    @Override
    public @Nullable Request setConfiguration(String key, String value) {
        String path = VARIABLES.getOrDefault(key, key);
        int dot = path.indexOf('.');
        if (dot <= 0 || dot == path.length() - 1) {
            return null;
        }
        SetVariableData data = new SetVariableData(value, new Component(path.substring(0, dot)),
                new Variable(path.substring(dot + 1)));
        return new SetVariablesRequest(new SetVariableData[] { data });
    }

    @Override
    public Request readLocalListVersion() {
        return new eu.chargetime.ocpp.v201.model.messages.GetLocalListVersionRequest();
    }

    @Override
    public Request sendLocalList(int versionNumber, java.util.List<String> idTokens) {
        eu.chargetime.ocpp.v201.model.messages.SendLocalListRequest request = new eu.chargetime.ocpp.v201.model.messages.SendLocalListRequest(
                versionNumber, eu.chargetime.ocpp.v201.model.types.UpdateEnum.Full);
        eu.chargetime.ocpp.v201.model.types.AuthorizationData[] entries = idTokens.stream().map(
                tag -> new eu.chargetime.ocpp.v201.model.types.AuthorizationData(new IdToken(tag, IdTokenEnum.ISO14443))
                        .withIdTokenInfo(new eu.chargetime.ocpp.v201.model.types.IdTokenInfo(
                                eu.chargetime.ocpp.v201.model.types.AuthorizationStatusEnum.Accepted)))
                .toArray(eu.chargetime.ocpp.v201.model.types.AuthorizationData[]::new);
        request.setLocalAuthorizationList(entries);
        return request;
    }

    @Override
    public @Nullable Integer localListVersionOf(@Nullable Confirmation confirmation) {
        return confirmation instanceof eu.chargetime.ocpp.v201.model.messages.GetLocalListVersionResponse reported
                ? reported.getVersionNumber()
                : null;
    }

    @Override
    public @Nullable Request customMessage(String vendorId, @Nullable String messageId, @Nullable Object data) {
        eu.chargetime.ocpp.v201.model.messages.DataTransferRequest request = new eu.chargetime.ocpp.v201.model.messages.DataTransferRequest(
                vendorId);
        if (messageId != null) {
            request.setMessageId(messageId);
        }
        if (data != null) {
            request.setData(data);
        }
        return request;
    }

    @Override
    public boolean isAccepted(@Nullable Confirmation confirmation) {
        if (confirmation instanceof SetVariablesResponse variables) {
            SetVariableResult[] results = variables.getSetVariableResult();
            if (results == null || results.length == 0) {
                return false;
            }
            for (SetVariableResult result : results) {
                if (result.getAttributeStatus() != SetVariableStatusEnum.Accepted
                        && result.getAttributeStatus() != SetVariableStatusEnum.RebootRequired) {
                    return false;
                }
            }
            return true;
        }
        if (confirmation instanceof SetChargingProfileResponse profile) {
            return profile.getStatus() == ChargingProfileStatusEnum.Accepted;
        }
        if (confirmation instanceof ClearChargingProfileResponse clear) {
            return clear.getStatus() == ClearChargingProfileStatusEnum.Accepted
                    || clear.getStatus() == ClearChargingProfileStatusEnum.Unknown;
        }
        if (confirmation instanceof ChangeAvailabilityResponse availability) {
            return availability
                    .getStatus() != eu.chargetime.ocpp.v201.model.types.ChangeAvailabilityStatusEnum.Rejected;
        }
        if (confirmation instanceof eu.chargetime.ocpp.v201.model.messages.ResetResponse reset) {
            return reset.getStatus() != eu.chargetime.ocpp.v201.model.types.ResetStatusEnum.Rejected;
        }
        if (confirmation instanceof TriggerMessageResponse trigger) {
            return trigger.getStatus() == TriggerMessageStatusEnum.Accepted;
        }
        return confirmation != null;
    }

    /** Whether a RequestStart/RequestStop was accepted; the connector handler retries on a refusal. */
    public static boolean isRemoteAccepted(@Nullable Confirmation confirmation) {
        if (confirmation instanceof RequestStartTransactionResponse start) {
            return start.getStatus() == RequestStartStopStatusEnum.Accepted;
        }
        return confirmation instanceof RequestStopTransactionResponse stop
                && stop.getStatus() == RequestStartStopStatusEnum.Accepted;
    }

    static int profileId(int connectorId, boolean txProfile) {
        return connectorId * PROFILE_ID_STRIDE + (txProfile ? 2 : 1);
    }
}
