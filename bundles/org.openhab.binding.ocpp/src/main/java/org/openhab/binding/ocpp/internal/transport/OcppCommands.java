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
 * <p>
 * The two versions renamed and reshaped every control message — RemoteStartTransaction became
 * RequestStartTransaction, a charging profile gained an EVSE id — so the handlers ask for an
 * operation and this decides what actually goes on the wire.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface OcppCommands {

    /** The type says how the charger should read the token: a card, or a vehicle's own identity. */
    Request remoteStart(int connectorId, String idToken, TokenType type);

    Request remoteStop(int transactionId, @Nullable String remoteId);

    Request unlock(int connectorId);

    Request changeAvailability(int connectorId, boolean operative);

    Request reset();

    Request triggerStatusNotification(int connectorId);

    Request triggerMeterValues(int connectorId);

    /**
     * A charge limit for one connector. The caller has already resolved which unit the charger takes
     * and what the effective value is, pause included, so both are passed through as given.
     */
    Request setChargingProfile(int connectorId, double value, boolean inWatts, int numberPhases, boolean txDefault,
            @Nullable Integer transactionId, @Nullable String remoteId);

    Request clearChargingProfile(int connectorId);

    /**
     * Ask the charger what it supports. 1.6 answers in the response; 2.0.1 accepts the request and
     * then streams its device model as NotifyReport messages.
     */
    Request readCapabilities();

    /**
     * Set one configuration value, named by its OCPP 1.6 key. Returns null when the version has no
     * way to express that key, which the caller treats as nothing to do rather than a failure.
     */
    @Nullable
    Request setConfiguration(String key, String value);

    Request readLocalListVersion();

    Request sendLocalList(int versionNumber, Map<String, TokenType> idTokens);

    /** The list version a {@code readLocalListVersion} answer reports, or null if it did not. */
    @Nullable
    Integer localListVersionOf(@Nullable Confirmation confirmation);

    /**
     * A vendor-specific message. Returns null on a version this binding does not offer it for.
     */
    @Nullable
    Request customMessage(String vendorId, @Nullable String messageId, @Nullable Object data);

    /**
     * Puts a message on the charger's own display, or clears it when the text is empty. Returns null
     * on a version with no such message.
     */
    @Nullable
    Request displayMessage(String text);

    /** Whether a confirmation reports the command as accepted, across both versions' status enums. */
    boolean isAccepted(@Nullable Confirmation confirmation);

    /** Whether the charger refused the value itself (not the setting), so a shorter list is worth a retry. */
    boolean isValueRejected(@Nullable Confirmation confirmation);

    /** Whether the charger has no such setting at all, so it is skipped rather than counted as a failure. */
    boolean isNotApplicable(@Nullable Confirmation confirmation);
}
