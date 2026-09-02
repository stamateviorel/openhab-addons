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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import eu.chargetime.ocpp.model.Confirmation;
import eu.chargetime.ocpp.model.Request;
import eu.chargetime.ocpp.model.core.AvailabilityStatus;
import eu.chargetime.ocpp.model.core.AvailabilityType;
import eu.chargetime.ocpp.model.core.ChangeAvailabilityConfirmation;
import eu.chargetime.ocpp.model.core.ChangeAvailabilityRequest;
import eu.chargetime.ocpp.model.core.ChangeConfigurationConfirmation;
import eu.chargetime.ocpp.model.core.ChangeConfigurationRequest;
import eu.chargetime.ocpp.model.core.ChargingRateUnitType;
import eu.chargetime.ocpp.model.core.ConfigurationStatus;
import eu.chargetime.ocpp.model.core.RemoteStartStopStatus;
import eu.chargetime.ocpp.model.core.RemoteStartTransactionRequest;
import eu.chargetime.ocpp.model.core.RemoteStopTransactionRequest;
import eu.chargetime.ocpp.model.core.ResetRequest;
import eu.chargetime.ocpp.model.core.ResetType;
import eu.chargetime.ocpp.model.core.UnlockConnectorRequest;
import eu.chargetime.ocpp.model.remotetrigger.TriggerMessageConfirmation;
import eu.chargetime.ocpp.model.remotetrigger.TriggerMessageRequest;
import eu.chargetime.ocpp.model.remotetrigger.TriggerMessageRequestType;
import eu.chargetime.ocpp.model.remotetrigger.TriggerMessageStatus;
import eu.chargetime.ocpp.model.smartcharging.ChargingProfileStatus;
import eu.chargetime.ocpp.model.smartcharging.ClearChargingProfileConfirmation;
import eu.chargetime.ocpp.model.smartcharging.ClearChargingProfileStatus;
import eu.chargetime.ocpp.model.smartcharging.SetChargingProfileConfirmation;

/**
 * Outbound requests in the OCPP 1.6 dialect.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class Ocpp16Commands implements OcppCommands {

    @Override
    public Request remoteStart(int connectorId, String idToken) {
        RemoteStartTransactionRequest request = new RemoteStartTransactionRequest(idToken);
        request.setConnectorId(connectorId);
        return request;
    }

    @Override
    public Request remoteStop(int transactionId, @Nullable String remoteId) {
        return new RemoteStopTransactionRequest(transactionId);
    }

    @Override
    public Request unlock(int connectorId) {
        return new UnlockConnectorRequest(connectorId);
    }

    @Override
    public Request changeAvailability(int connectorId, boolean operative) {
        return new ChangeAvailabilityRequest(connectorId,
                operative ? AvailabilityType.Operative : AvailabilityType.Inoperative);
    }

    @Override
    public Request reset() {
        return new ResetRequest(ResetType.Soft);
    }

    @Override
    public Request triggerStatusNotification(int connectorId) {
        TriggerMessageRequest request = new TriggerMessageRequest(TriggerMessageRequestType.StatusNotification);
        request.setConnectorId(connectorId);
        return request;
    }

    @Override
    public Request triggerMeterValues(int connectorId) {
        TriggerMessageRequest request = new TriggerMessageRequest(TriggerMessageRequestType.MeterValues);
        request.setConnectorId(connectorId);
        return request;
    }

    @Override
    public Request setChargingProfile(int connectorId, double value, boolean inWatts, int numberPhases,
            boolean txDefault, @Nullable Integer transactionId, @Nullable String remoteId) {
        return ChargingProfileBuilder.limit(connectorId, inWatts ? ChargingRateUnitType.W : ChargingRateUnitType.A,
                value, numberPhases > 0 ? numberPhases : null, txDefault, transactionId);
    }

    @Override
    public Request clearChargingProfile(int connectorId) {
        return ChargingProfileBuilder.clearLimit(connectorId);
    }

    @Override
    public Request readCapabilities() {
        return new eu.chargetime.ocpp.model.core.GetConfigurationRequest();
    }

    @Override
    public @Nullable Request setConfiguration(String key, String value) {
        return new ChangeConfigurationRequest(key, value);
    }

    @Override
    public boolean isAccepted(@Nullable Confirmation confirmation) {
        if (confirmation instanceof SetChargingProfileConfirmation profile) {
            return profile.getStatus() == ChargingProfileStatus.Accepted;
        }
        if (confirmation instanceof ClearChargingProfileConfirmation clear) {
            return clear.getStatus() == ClearChargingProfileStatus.Accepted
                    || clear.getStatus() == ClearChargingProfileStatus.Unknown;
        }
        if (confirmation instanceof ChangeAvailabilityConfirmation availability) {
            return availability.getStatus() == AvailabilityStatus.Accepted
                    || availability.getStatus() == AvailabilityStatus.Scheduled;
        }
        if (confirmation instanceof TriggerMessageConfirmation trigger) {
            return trigger.getStatus() == TriggerMessageStatus.Accepted;
        }
        if (confirmation instanceof ChangeConfigurationConfirmation configuration) {
            return configuration.getStatus() == ConfigurationStatus.Accepted
                    || configuration.getStatus() == ConfigurationStatus.RebootRequired;
        }
        return confirmation != null;
    }

    /** Whether a RemoteStart/RemoteStop was accepted; the connector handler retries on a refusal. */
    public static boolean isRemoteAccepted(@Nullable Confirmation confirmation) {
        return confirmation instanceof eu.chargetime.ocpp.model.core.RemoteStartTransactionConfirmation start
                ? start.getStatus() == RemoteStartStopStatus.Accepted
                : confirmation instanceof eu.chargetime.ocpp.model.core.RemoteStopTransactionConfirmation stop
                        && stop.getStatus() == RemoteStartStopStatus.Accepted;
    }
}
