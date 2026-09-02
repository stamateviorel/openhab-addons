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

    Request remoteStart(int connectorId, String idToken);

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

    /** Whether a confirmation reports the command as accepted, across both versions' status enums. */
    boolean isAccepted(@Nullable Confirmation confirmation);
}
