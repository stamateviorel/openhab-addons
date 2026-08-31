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
package org.openhab.binding.ocpp.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Dictionary;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

/**
 * Tests binding-wide card configuration and the Auto-learn write-back path.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings({ "null", "unchecked" })
class OcppBindingConfigTest {

    @Test
    void defaultsAreOffAndEmpty() {
        OcppBindingConfig config = new OcppBindingConfig(mock(ConfigurationAdmin.class), null);

        assertFalse(config.isAutoLearn());
        assertFalse(config.isDiscoverCards());
        assertTrue(config.getWhitelist().isEmpty());
    }

    @Test
    void togglesParseFromBooleanAndString() {
        OcppBindingConfig booleans = new OcppBindingConfig(mock(ConfigurationAdmin.class),
                Map.of("autoLearn", Boolean.TRUE, "discoverCards", Boolean.TRUE));
        assertTrue(booleans.isAutoLearn());
        assertTrue(booleans.isDiscoverCards());

        OcppBindingConfig strings = new OcppBindingConfig(mock(ConfigurationAdmin.class),
                Map.of("autoLearn", "true", "discoverCards", "false"));
        assertTrue(strings.isAutoLearn());
        assertFalse(strings.isDiscoverCards());
    }

    @Test
    void whitelistParsesFromListArrayAndCommaString() {
        assertEquals(List.of("A", "B"),
                new OcppBindingConfig(mock(ConfigurationAdmin.class), Map.of("whitelistTagIds", List.of("A", "B")))
                        .getWhitelist());
        assertEquals(List.of("A", "B"), new OcppBindingConfig(mock(ConfigurationAdmin.class),
                Map.of("whitelistTagIds", new Object[] { "A", "B" })).getWhitelist());
        assertEquals(List.of("A", "B"),
                new OcppBindingConfig(mock(ConfigurationAdmin.class), Map.of("whitelistTagIds", " A , B "))
                        .getWhitelist());
    }

    @Test
    void addToWhitelistPersistsThroughConfigurationAdmin() throws Exception {
        ConfigurationAdmin configAdmin = mock(ConfigurationAdmin.class);
        Configuration configuration = mock(Configuration.class);
        when(configAdmin.getConfiguration(eq("binding.ocpp"), any())).thenReturn(configuration);
        when(configuration.getProperties()).thenReturn(null);

        new OcppBindingConfig(configAdmin, Map.of("whitelistTagIds", List.of("KNOWN"))).addToWhitelist("NEW");

        ArgumentCaptor<Dictionary<String, Object>> captor = ArgumentCaptor.forClass(Dictionary.class);
        verify(configuration).update(captor.capture());
        Collection<String> stored = (Collection<String>) captor.getValue().get("whitelistTagIds");
        assertTrue(stored.contains("KNOWN"));
        assertTrue(stored.contains("NEW"));
    }

    @Test
    void addToWhitelistIgnoresATagAlreadyPresent() throws Exception {
        ConfigurationAdmin configAdmin = mock(ConfigurationAdmin.class);

        new OcppBindingConfig(configAdmin, Map.of("whitelistTagIds", List.of("KNOWN"))).addToWhitelist("KNOWN");

        verify(configAdmin, never()).getConfiguration(anyString(), any());
    }
}
