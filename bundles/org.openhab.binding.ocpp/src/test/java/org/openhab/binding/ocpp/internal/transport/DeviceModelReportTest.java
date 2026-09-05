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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;

import eu.chargetime.ocpp.v201.model.messages.NotifyReportRequest;
import eu.chargetime.ocpp.v201.model.types.AttributeEnum;
import eu.chargetime.ocpp.v201.model.types.Component;
import eu.chargetime.ocpp.v201.model.types.EVSE;
import eu.chargetime.ocpp.v201.model.types.ReportData;
import eu.chargetime.ocpp.v201.model.types.Variable;
import eu.chargetime.ocpp.v201.model.types.VariableAttribute;

/**
 * Translation of an OCPP 2.0.1 device-model report into the flat capability keys.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings({ "null" })
class DeviceModelReportTest {

    @Test
    void aReportInSeveralPartsIsOnlyCompleteWhenTheChargerSaysSo() {
        DeviceModelReport report = new DeviceModelReport();

        assertFalse(report.add(notifyReport(true, data("SmartChargingCtrlr", "Available", "true", null))),
                "a report marked to-be-continued is not finished");
        assertTrue(report.add(notifyReport(false, data("OCPPCommCtrlr", "HeartbeatInterval", "300", null))));

        assertEquals("300", report.asConfigurationKeys().get("HeartbeatInterval"));
    }

    @Test
    void supportedProfilesAreBuiltFromTheControllersThatAreAvailable() {
        DeviceModelReport report = new DeviceModelReport();
        report.add(notifyReport(false, data("SmartChargingCtrlr", "Available", "true", null),
                data("LocalAuthListCtrlr", "Available", "true", null)));

        String profiles = report.asConfigurationKeys().get("SupportedFeatureProfiles");

        assertTrue(profiles.contains("SmartCharging"));
        assertTrue(profiles.contains("LocalAuthListManagement"));
    }

    @Test
    void aControllerReportedUnavailableIsAnAnswer() {
        // Distinct from a charger that never mentioned it: the key is present but does not list the
        // profile, so the binding reads "not supported" rather than "not known".
        DeviceModelReport report = new DeviceModelReport();
        report.add(notifyReport(false, data("SmartChargingCtrlr", "Available", "false", null)));

        String profiles = report.asConfigurationKeys().get("SupportedFeatureProfiles");
        assertEquals("", profiles);
    }

    @Test
    void aChargerThatMentionsNoControllerLeavesTheProfilesUnknown() {
        DeviceModelReport report = new DeviceModelReport();
        report.add(notifyReport(false, data("AlignedDataCtrlr", "Interval", "900", null)));

        assertFalse(report.asConfigurationKeys().containsKey("SupportedFeatureProfiles"));
    }

    @Test
    void alfensOwnSpellingOfTheSmartChargingVariablesIsUnderstood() {
        // Seen on an Alfen NG: RateUnit and Phases3to1 rather than the longer names in the spec.
        DeviceModelReport report = new DeviceModelReport();
        report.add(notifyReport(false, data("SmartChargingCtrlr", "RateUnit", "A", null),
                data("SmartChargingCtrlr", "Phases3to1", "false", null)));

        assertEquals("Current", report.asConfigurationKeys().get("ChargingScheduleAllowedChargingRateUnit"));
        assertEquals("false", report.asConfigurationKeys().get("ConnectorSwitch3to1PhaseSupported"));
    }

    @Test
    void enabledStandsInForAvailableWhenAChargerOmitsIt() {
        DeviceModelReport report = new DeviceModelReport();
        report.add(notifyReport(false, data("LocalAuthListCtrlr", "Enabled", "true", null)));

        assertTrue(report.asConfigurationKeys().get("SupportedFeatureProfiles").contains("LocalAuthListManagement"));
    }

    @Test
    void theRateUnitsAreSpelledOutTheWayTheBindingReadsThem() {
        // 2.0.1 states them as A and W; the 1.6 key the binding matches on says Current and Power.
        DeviceModelReport report = new DeviceModelReport();
        report.add(notifyReport(false, data("SmartChargingCtrlr", "ChargingScheduleChargingRateUnit", "A,W", null)));

        assertEquals("Current,Power", report.asConfigurationKeys().get("ChargingScheduleAllowedChargingRateUnit"));
    }

    @Test
    void theEvsesInTheReportBecomeTheConnectorCount() {
        // 2.0.1 has no connector-count variable; the binding models one connector per EVSE.
        DeviceModelReport report = new DeviceModelReport();
        report.add(notifyReport(false, data("Connector", "AvailabilityState", "Available", 1),
                data("Connector", "AvailabilityState", "Available", 2),
                data("Connector", "AvailabilityState", "Available", 1)));

        assertEquals("2", report.asConfigurationKeys().get("NumberOfConnectors"));
    }

    @Test
    void theActualValueWinsOverWhatTheVariableCouldBeSetTo() {
        DeviceModelReport report = new DeviceModelReport();
        ReportData entry = data("OCPPCommCtrlr", "HeartbeatInterval", "300", null);
        VariableAttribute target = new VariableAttribute();
        target.setType(AttributeEnum.Target);
        target.setValue("60");
        VariableAttribute actual = entry.getVariableAttribute()[0];
        actual.setType(AttributeEnum.Actual);
        entry.setVariableAttribute(new VariableAttribute[] { target, actual });
        report.add(notifyReport(false, entry));

        assertEquals("300", report.asConfigurationKeys().get("HeartbeatInterval"));
    }

    @Test
    void everyReportedVariableStaysVisibleForDiagnostics() {
        DeviceModelReport report = new DeviceModelReport();
        report.add(notifyReport(false, data("AlignedDataCtrlr", "Interval", "900", null)));

        assertEquals("900", report.asConfigurationKeys().get("AlignedDataCtrlr.Interval"));
    }

    private static ReportData data(String component, String variable, String value, @Nullable Integer evseId) {
        Component componentModel = new Component(component);
        if (evseId != null) {
            componentModel.setEvse(new EVSE(evseId));
        }
        VariableAttribute attribute = new VariableAttribute();
        attribute.setValue(value);
        return new ReportData(componentModel, new Variable(variable), new VariableAttribute[] { attribute });
    }

    private static NotifyReportRequest notifyReport(boolean toBeContinued, ReportData... data) {
        NotifyReportRequest request = new NotifyReportRequest(1, ZonedDateTime.now(ZoneOffset.UTC), 0);
        request.setReportData(data);
        request.setTbc(toBeContinued);
        return request;
    }
}
