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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

import eu.chargetime.ocpp.v201.model.types.MeasurandEnum;
import eu.chargetime.ocpp.v201.model.types.PhaseEnum;

/**
 * Wire spellings of the OCPP 2.0.1 measurand and phase enums.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class Ocpp201EventsTest {

    @Test
    void measurandsCarryTheirWireSpelling() {
        Map<MeasurandEnum, String> expected = Map.of(MeasurandEnum.EnergyActiveImportRegister,
                "Energy.Active.Import.Register", MeasurandEnum.EnergyActiveImportInterval,
                "Energy.Active.Import.Interval", MeasurandEnum.CurrentImport, "Current.Import",
                MeasurandEnum.CurrentOffered, "Current.Offered", MeasurandEnum.PowerActiveImport, "Power.Active.Import",
                MeasurandEnum.PowerOffered, "Power.Offered", MeasurandEnum.PowerFactor, "Power.Factor",
                MeasurandEnum.Voltage, "Voltage", MeasurandEnum.Frequency, "Frequency", MeasurandEnum.SoC, "SoC");
        expected.forEach((measurand, wire) -> assertEquals(wire, Ocpp201Events.wireName(measurand)));
    }

    @Test
    void phasesCarryTheirWireSpelling() {
        Map<PhaseEnum, String> expected = Map.of(PhaseEnum.L1, "L1", PhaseEnum.L2, "L2", PhaseEnum.L3, "L3",
                PhaseEnum.N, "N", PhaseEnum.L1_N, "L1-N", PhaseEnum.L2_N, "L2-N", PhaseEnum.L3_N, "L3-N",
                PhaseEnum.L1_L2, "L1-L2", PhaseEnum.L2_L3, "L2-L3", PhaseEnum.L3_L1, "L3-L1");
        expected.forEach((phase, wire) -> assertEquals(wire, Ocpp201Events.wireName(phase)));
    }

    @Test
    void everyConstantIsMapped() {
        for (MeasurandEnum measurand : MeasurandEnum.values()) {
            assertNotNull(Ocpp201Events.wireName(measurand), "unmapped measurand " + measurand);
        }
        for (PhaseEnum phase : PhaseEnum.values()) {
            assertNotNull(Ocpp201Events.wireName(phase), "unmapped phase " + phase);
        }
        assertEquals(25, MeasurandEnum.values().length, "a library upgrade added or removed a measurand");
        assertEquals(10, PhaseEnum.values().length, "a library upgrade added or removed a phase");
    }

    @Test
    void anAbsentMeasurandOrPhaseStaysAbsent() {
        assertNull(Ocpp201Events.wireName((MeasurandEnum) null));
        assertNull(Ocpp201Events.wireName((PhaseEnum) null));
    }
}
