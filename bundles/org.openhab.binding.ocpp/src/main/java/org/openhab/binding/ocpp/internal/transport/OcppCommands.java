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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;

import eu.chargetime.ocpp.model.Confirmation;
import eu.chargetime.ocpp.model.Request;

/**
 * Builds the outbound requests the handlers send, in the dialect of one OCPP version.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface OcppCommands {

    Request remoteStart(int connectorId, String idToken, TokenType type);

    Request remoteStop(int transactionId, @Nullable String remoteId);

    Request unlock(int connectorId);

    Request changeAvailability(int connectorId, boolean operative);

    Request reset();

    Request triggerStatusNotification(int connectorId);

    Request triggerMeterValues(int connectorId);

    /** {@code value} is final (0 for pause), in the unit the caller chose. */
    Request setChargingProfile(int connectorId, double value, boolean inWatts, int numberPhases, boolean txDefault,
            @Nullable Integer transactionId, @Nullable String remoteId);

    Request clearChargingProfile(int connectorId);

    /** 1.6 answers in the confirmation; 2.0.1 answers later via {@link OcppServerListener#onCapabilities}. */
    Request readCapabilities();

    /** {@code key} is the OCPP 1.6 name; null when this version cannot express it. */
    @Nullable
    Request setConfiguration(String key, String value);

    Request readLocalListVersion();

    Request sendLocalList(int versionNumber, Map<String, TokenType> idTokens);

    @Nullable
    Integer localListVersionOf(@Nullable Confirmation confirmation);

    /** Null on a version this binding does not offer it for. */
    @Nullable
    Request customMessage(String vendorId, @Nullable String messageId, @Nullable Object data);

    /** Empty text clears the display; null on a version without SetDisplayMessage. */
    @Nullable
    Request displayMessage(String text);

    boolean isAccepted(@Nullable Confirmation confirmation);

    default String describe(@Nullable Confirmation confirmation) {
        return String.valueOf(confirmation);
    }

    /** Whether the charger refused the value itself (not the setting), so a shorter list is worth a retry. */
    boolean isValueRejected(@Nullable Confirmation confirmation);

    /** Unknown component/variable, as distinct from a rejected value. */
    boolean isNotApplicable(@Nullable Confirmation confirmation);
}
