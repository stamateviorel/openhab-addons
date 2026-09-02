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
import eu.chargetime.ocpp.v201.model.types.Component;
import eu.chargetime.ocpp.v201.model.types.EVSE;
import eu.chargetime.ocpp.v201.model.types.ReportData;
import eu.chargetime.ocpp.v201.model.types.Variable;
import eu.chargetime.ocpp.v201.model.types.VariableAttribute;

/**
 * Collects an OCPP 2.0.1 device-model report and states it in the flat keys the binding's
 * {@link ChargerCapabilities} is built from.
 *
 * <p>
 * 2.0.1 replaced the flat GetConfiguration key list with a component tree whose values arrive
 * across one or more NotifyReport messages. Only the handful of variables the binding acts on are
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
        boolean enabled = Boolean.parseBoolean(value);
        switch (component + "." + variable) {
            case SMART_CHARGING_CTRLR + ".Available" -> {
                if (enabled) {
                    featureProfiles.add("SmartCharging");
                }
            }
            case LOCAL_AUTH_CTRLR + ".Available" -> {
                if (enabled) {
                    featureProfiles.add("LocalAuthListManagement");
                }
            }
            case SMART_CHARGING_CTRLR + ".ACPhaseSwitchingSupported" ->
                values.put("ConnectorSwitch3to1PhaseSupported", value);
            case SMART_CHARGING_CTRLR + ".ChargingScheduleChargingRateUnit" ->
                values.put("ChargingScheduleAllowedChargingRateUnit", rateUnits(value));
            case OCPP_COMM_CTRLR + ".HeartbeatInterval" -> values.put("HeartbeatInterval", value);
            default -> {
            }
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

    /**
     * The reported value, preferring what the charger is actually using over what it could be set
     * to. A variable with no Actual attribute is not something the binding can act on.
     */
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
            if (attribute.getType() == null
                    || attribute.getType() == eu.chargetime.ocpp.v201.model.types.AttributeEnum.Actual) {
                return value;
            }
            fallback = fallback == null ? value : fallback;
        }
        return fallback;
    }

    /** The report so far, in the flat form {@link ChargerCapabilities} consumes. */
    public Map<String, String> asConfigurationKeys() {
        Map<String, String> keys = new LinkedHashMap<>(values);
        if (!featureProfiles.isEmpty()) {
            keys.put("SupportedFeatureProfiles", String.join(",", featureProfiles));
        }
        if (!evseIds.isEmpty()) {
            // 2.0.1 has no connector count; the EVSEs the report mentions are the connectors here.
            keys.put("NumberOfConnectors", String.valueOf(evseIds.size()));
        }
        return keys;
    }
}
