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

/**
 * Persists transaction state so it survives an openHAB restart.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class TransactionStore {

    public record Location(String chargePointId, int connectorId, @Nullable String remoteId) {
        public Location(String chargePointId, int connectorId) {
            this(chargePointId, connectorId, null);
        }
    }

    private static final String SEQUENCE_KEY = "sequence";
    private static final String TX_PREFIX = "tx:";
    private static final char SEPARATOR = '\t';

    private final Storage<String> storage;
    // Guarded by this: increment and persistent write must be one atomic step.
    private int sequence;

    public TransactionStore(Storage<String> storage) {
        this.storage = storage;
        this.sequence = readSequence(storage);
    }

    private static int readSequence(Storage<String> storage) {
        String stored = storage.get(SEQUENCE_KEY);
        if (stored != null) {
            try {
                return Integer.parseInt(stored);
            } catch (NumberFormatException e) {
            }
        }
        return 0;
    }

    public synchronized int nextTransactionId() {
        int id = ++sequence;
        storage.put(SEQUENCE_KEY, Integer.toString(id));
        return id;
    }

    public synchronized void begin(int transactionId, String chargePointId, int connectorId) {
        begin(transactionId, chargePointId, connectorId, null);
    }

    /** {@code remoteId} is the name the charger itself gives the transaction, where it has one. */
    public synchronized void begin(int transactionId, String chargePointId, int connectorId,
            @Nullable String remoteId) {
        clear(chargePointId, connectorId);
        storage.put(TX_PREFIX + transactionId,
                chargePointId + SEPARATOR + connectorId + (remoteId == null ? "" : SEPARATOR + remoteId));
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
                try {
                    return Integer.parseInt(key.substring(TX_PREFIX.length()));
                } catch (NumberFormatException e) {
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
                try {
                    return Integer.parseInt(key.substring(TX_PREFIX.length()));
                } catch (NumberFormatException e) {
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

    private static boolean matches(@Nullable String value, String chargePointId, int connectorId) {
        Location location = parse(value);
        return location != null && location.chargePointId().equals(chargePointId)
                && location.connectorId() == connectorId;
    }

    private static @Nullable Location parse(@Nullable String value) {
        if (value == null) {
            return null;
        }
        // chargePointId, connectorId and, for a charger that names its transactions, that name.
        String[] fields = value.split(String.valueOf(SEPARATOR), 3);
        if (fields.length < 2) {
            return null;
        }
        try {
            return new Location(fields[0], Integer.parseInt(fields[1]), fields.length > 2 ? fields[2] : null);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
