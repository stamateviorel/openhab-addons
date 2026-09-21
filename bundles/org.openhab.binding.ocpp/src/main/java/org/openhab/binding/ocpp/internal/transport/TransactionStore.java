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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists transaction state so it survives an openHAB restart.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class TransactionStore {

    public record Location(String chargePointId, int connectorId, @Nullable String remoteId,
            @Nullable Integer meterStart) {
    }

    private static final String SEQUENCE_KEY = "sequence";
    private static final String TX_PREFIX = "tx:";
    private static final char SEPARATOR = '\t';

    private final Logger logger = LoggerFactory.getLogger(TransactionStore.class);
    private final Storage<String> storage;
    // Guarded by this: increment and persistent write must be one atomic step.
    private int sequence;

    public TransactionStore(Storage<String> storage) {
        this.storage = storage;
        this.sequence = readSequence();
    }

    private int readSequence() {
        String stored = storage.get(SEQUENCE_KEY);
        if (stored == null) {
            return 0;
        }
        Integer parsed = parseInt(stored);
        if (parsed == null) {
            logger.warn("Persisted transaction sequence '{}' is not a number; transaction ids restart at 0", stored);
            return 0;
        }
        return parsed;
    }

    public synchronized int nextTransactionId() {
        int id = ++sequence;
        storage.put(SEQUENCE_KEY, Integer.toString(id));
        return id;
    }

    /** {@code remoteId} is the charger's own name for the transaction, {@code meterStart} its starting register. */
    public synchronized void begin(int transactionId, String chargePointId, int connectorId, @Nullable String remoteId,
            @Nullable Integer meterStart) {
        clear(chargePointId, connectorId);
        StringBuilder value = new StringBuilder(chargePointId).append(SEPARATOR).append(connectorId);
        if (remoteId != null || meterStart != null) {
            value.append(SEPARATOR).append(remoteId == null ? "" : remoteId);
        }
        if (meterStart != null) {
            value.append(SEPARATOR).append(meterStart);
        }
        storage.put(TX_PREFIX + transactionId, value.toString());
    }

    /** The id the binding gave the transaction a charger names {@code remoteId}, if it is still open. */
    public synchronized @Nullable Integer byRemoteId(String chargePointId, String remoteId) {
        for (String key : storage.getKeys()) {
            if (!key.startsWith(TX_PREFIX)) {
                continue;
            }
            Location location = parse(storage.get(key));
            if (location != null && chargePointId.equals(location.chargePointId())
                    && remoteId.equals(location.remoteId())) {
                Integer transactionId = transactionIdOf(key);
                if (transactionId != null) {
                    return transactionId;
                }
            }
        }
        return null;
    }

    public synchronized void end(int transactionId) {
        storage.remove(TX_PREFIX + transactionId);
    }

    public synchronized @Nullable Location locate(int transactionId) {
        return parse(storage.get(TX_PREFIX + transactionId));
    }

    public synchronized @Nullable Integer openTransaction(String chargePointId, int connectorId) {
        for (String key : storage.getKeys()) {
            if (key.startsWith(TX_PREFIX) && matches(storage.get(key), chargePointId, connectorId)) {
                Integer transactionId = transactionIdOf(key);
                if (transactionId != null) {
                    return transactionId;
                }
            }
        }
        return null;
    }

    private void clear(String chargePointId, int connectorId) {
        for (String key : new ArrayList<>(storage.getKeys())) {
            if (key.startsWith(TX_PREFIX) && matches(storage.get(key), chargePointId, connectorId)) {
                storage.remove(key);
            }
        }
    }

    private @Nullable Integer transactionIdOf(String key) {
        String id = key.substring(TX_PREFIX.length());
        Integer parsed = parseInt(id);
        if (parsed == null) {
            logger.warn("Persisted transaction key '{}' has no numeric id; the entry stays hidden until it is cleared",
                    key);
        }
        return parsed;
    }

    private boolean matches(@Nullable String value, String chargePointId, int connectorId) {
        Location location = parse(value);
        return location != null && location.chargePointId().equals(chargePointId)
                && location.connectorId() == connectorId;
    }

    private static @Nullable Integer parseInt(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private @Nullable Location parse(@Nullable String value) {
        if (value == null) {
            return null;
        }
        // The optional tail fields are written only when the charger supplies them.
        String[] fields = value.split(String.valueOf(SEPARATOR), 4);
        if (fields.length < 2) {
            return null;
        }
        Integer connectorId = parseInt(fields[1]);
        if (connectorId == null) {
            logger.warn("Persisted transaction entry '{}' has no numeric connector; it is treated as absent", value);
            return null;
        }
        String remoteId = fields.length > 2 && !fields[2].isEmpty() ? fields[2] : null;
        Integer meterStart = fields.length > 3 ? parseInt(fields[3]) : null;
        if (fields.length > 3 && meterStart == null) {
            logger.warn("Persisted transaction entry '{}' has no numeric meter start; the session is sized from the "
                    + "charger's own reading instead", value);
        }
        return new Location(fields[0], connectorId, remoteId, meterStart);
    }
}
