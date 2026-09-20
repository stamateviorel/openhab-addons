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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import eu.chargetime.ocpp.v201.model.messages.NotifyReportRequest;
import eu.chargetime.ocpp.v201.model.types.AttributeEnum;
import eu.chargetime.ocpp.v201.model.types.Component;
import eu.chargetime.ocpp.v201.model.types.EVSE;
import eu.chargetime.ocpp.v201.model.types.ReportData;
import eu.chargetime.ocpp.v201.model.types.Variable;
import eu.chargetime.ocpp.v201.model.types.VariableAttribute;

/**
 * Collects an OCPP 2.0.1 device-model report and states it in the flat keys the binding's
 * {@link ChargerCapabilities} is built from.
 *
 * translated; the rest of the report is kept verbatim as {@code Component.Variable} so it is still
 * visible for diagnostics.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class DeviceModelReport {

    private static final String SMART_CHARGING_CTRLR = "SmartChargingCtrlr";
    private static final String LOCAL_AUTH_CTRLR = "LocalAuthListCtrlr";
    private static final String OCPP_COMM_CTRLR = "OCPPCommCtrlr";

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Set<Integer> evseIds = new TreeSet<>();
    private final Set<String> featureProfiles = new LinkedHashSet<>();
    private boolean sawController;

    /** Absorb one NotifyReport. Returns true once the charger says no more are coming. */
    public boolean add(NotifyReportRequest request) {
        ReportData[] data = request.getReportData();
        if (data != null) {
            for (ReportData entry : data) {
                absorb(entry);
            }
        }
        return !Boolean.TRUE.equals(request.getTbc());
    }

    private void absorb(ReportData entry) {
        Component component = entry.getComponent();
        Variable variable = entry.getVariable();
        if (component == null || variable == null) {
            return;
        }
        String componentName = component.getName();
        String variableName = variable.getName();
        if (componentName == null || variableName == null) {
            return;
        }
        EVSE evse = component.getEvse();
        if (evse != null && evse.getId() != null) {
            evseIds.add(evse.getId());
        }
        String value = actualValue(entry.getVariableAttribute());
        if (value == null) {
            return;
        }
        values.put(componentName + "." + variableName, value);
        translate(componentName, variableName, value);
    }

    private void translate(String component, String variable, String value) {
        // A 2.0.1 controller may report Enabled without Available.
        boolean available = Boolean.parseBoolean(value);
        switch (component + "." + variable) {
            case SMART_CHARGING_CTRLR + ".Available" -> {
                sawController = true;
                profile("SmartCharging", available);
            }
            case SMART_CHARGING_CTRLR + ".Enabled" -> {
                sawController = true;
                if (!values.containsKey(SMART_CHARGING_CTRLR + ".Available")) {
                    profile("SmartCharging", available);
                }
            }
            case LOCAL_AUTH_CTRLR + ".Available" -> {
                sawController = true;
                profile("LocalAuthListManagement", available);
            }
            case LOCAL_AUTH_CTRLR + ".Enabled" -> {
                sawController = true;
                if (!values.containsKey(LOCAL_AUTH_CTRLR + ".Available")) {
                    profile("LocalAuthListManagement", available);
                }
            }
            // Phases3to1 is the 2.0.1 count-switch flag; ACPhaseSwitchingSupported is phase selection, seen
            // used for the same on Alfen.
            case SMART_CHARGING_CTRLR + ".Phases3to1", SMART_CHARGING_CTRLR + ".ACPhaseSwitchingSupported" ->
                values.put("ConnectorSwitch3to1PhaseSupported", value);
            case SMART_CHARGING_CTRLR + ".RateUnit", SMART_CHARGING_CTRLR + ".ChargingScheduleChargingRateUnit" ->
                values.put("ChargingScheduleAllowedChargingRateUnit", rateUnits(value));
            case OCPP_COMM_CTRLR + ".HeartbeatInterval" -> values.put("HeartbeatInterval", value);
            default -> {
            }
        }
    }

    private void profile(String name, boolean supported) {
        if (supported) {
            featureProfiles.add(name);
        } else {
            featureProfiles.remove(name);
        }
    }

    /** 2.0.1 states the rate units as A and W; the binding's keys spell them out. */
    private static String rateUnits(String value) {
        StringBuilder units = new StringBuilder();
        if (value.contains("A")) {
            units.append("Current");
        }
        if (value.contains("W")) {
            units.append(units.isEmpty() ? "" : ",").append("Power");
        }
        return units.isEmpty() ? value : units.toString();
    }

    /** The Actual attribute value when present, else the first attribute value. */
    private static @Nullable String actualValue(VariableAttribute @Nullable [] attributes) {
        if (attributes == null) {
            return null;
        }
        String fallback = null;
        for (VariableAttribute attribute : attributes) {
            String value = attribute.getValue();
            if (value == null) {
                continue;
            }
            if (attribute.getType() == null || attribute.getType() == AttributeEnum.Actual) {
                return value;
            }
            fallback = fallback == null ? value : fallback;
        }
        return fallback;
    }

    public Map<String, String> asConfigurationKeys() {
        Map<String, String> keys = new LinkedHashMap<>(values);
        if (sawController) {
            // Empty means "none supported"; absent means "unknown" to ChargerCapabilities.
            keys.put("SupportedFeatureProfiles", String.join(",", featureProfiles));
        }
        // 2.0.1 has no connector count; the highest EVSE id the report names stands in, since callers count 1..n.
        int highest = evseIds.stream().mapToInt(Integer::intValue).filter(id -> id > 0).max().orElse(0);
        if (highest > 0) {
            keys.put("NumberOfConnectors", String.valueOf(highest));
        }
        return keys;
    }
}
