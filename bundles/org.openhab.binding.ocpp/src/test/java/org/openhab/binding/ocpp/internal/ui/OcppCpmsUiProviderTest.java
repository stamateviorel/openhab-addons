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
package org.openhab.binding.ocpp.internal.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.ui.components.RootUIComponent;

/**
 * Tests that the CPMS page provider serves a stable sidebar page.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings("null")
class OcppCpmsUiProviderTest {

    @Test
    void servesOneStableSidebarPageEvenWithNoServer() {
        ThingRegistry registry = mock(ThingRegistry.class);
        when(registry.getAll()).thenReturn(List.of());

        OcppCpmsUiProvider provider = new OcppCpmsUiProvider(registry);
        try {
            assertEquals("ui:page", provider.getNamespace());
            Collection<RootUIComponent> pages = provider.getAll();
            assertEquals(1, pages.size());
            assertEquals("ocpp_cpms", pages.iterator().next().getUID());
        } finally {
            provider.deactivate();
        }
    }
}
