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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.ConfigurableService;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binding-wide card settings, edited under Settings → Add-on Settings → OCPP Binding. Backed by
 * Configuration Admin under PID {@code binding.ocpp}, so authorization lives with the binding rather than
 * on each server Thing. {@link #addToWhitelist} writes back through the same PID, which the running binding
 * re-reads without reinitializing any Thing — a learned card takes effect without dropping charger sessions.
 *
 * @author Stamate Viorel - Initial contribution
 */
@Component(service = OcppBindingConfig.class, configurationPid = "binding.ocpp", immediate = true)
@ConfigurableService(category = "binding", label = "OCPP Binding", description_uri = "binding:ocpp")
@NonNullByDefault
public class OcppBindingConfig {

    private static final String PID = "binding.ocpp";
    private static final String KEY_WHITELIST = "whitelistTagIds";

    private final Logger logger = LoggerFactory.getLogger(OcppBindingConfig.class);
    private final ConfigurationAdmin configAdmin;

    private volatile boolean autoLearn;
    private volatile boolean discoverCards;
    private volatile List<String> whitelist = List.of();

    @Activate
    public OcppBindingConfig(@Reference ConfigurationAdmin configAdmin, @Nullable Map<String, Object> properties) {
        this.configAdmin = configAdmin;
        apply(properties);
    }

    @Modified
    public void modified(@Nullable Map<String, Object> properties) {
        apply(properties);
    }

    private void apply(@Nullable Map<String, Object> properties) {
        if (properties == null) {
            return;
        }
        autoLearn = truthy(properties.get("autoLearn"));
        discoverCards = truthy(properties.get("discoverCards"));
        whitelist = toList(properties.get(KEY_WHITELIST));
    }

    public boolean isAutoLearn() {
        return autoLearn;
    }

    public boolean isDiscoverCards() {
        return discoverCards;
    }

    public List<String> getWhitelist() {
        return whitelist;
    }

    /**
     * Persists a tag into the whitelist via Configuration Admin; the modified callback re-reads it.
     * Synchronized and built from the persisted list, not the in-memory field, so two cards learned before
     * the async callback lands do not overwrite each other.
     */
    public synchronized void addToWhitelist(String idTag) {
        try {
            Configuration configuration = configAdmin.getConfiguration(PID, null);
            Dictionary<String, Object> props = configuration.getProperties();
            if (props == null) {
                props = new Hashtable<>();
            }
            List<String> current = toList(props.get(KEY_WHITELIST));
            if (current.contains(idTag)) {
                return;
            }
            List<String> updated = new ArrayList<>(current);
            updated.add(idTag);
            props.put(KEY_WHITELIST, updated);
            configuration.update(props);
            logger.info("Auto-learned card {} into the whitelist", idTag);
        } catch (IOException e) {
            logger.warn("Could not persist learned card {}: {}", idTag, e.getMessage());
        }
    }

    private static boolean truthy(@Nullable Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value instanceof String s && Boolean.parseBoolean(s);
    }

    private static List<String> toList(@Nullable Object value) {
        Collection<?> raw;
        if (value instanceof Collection<?> collection) {
            raw = collection;
        } else if (value instanceof Object[] array) {
            raw = List.of(array);
        } else if (value instanceof String s) {
            raw = List.of(s.split(","));
        } else {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object element : raw) {
            String tag = String.valueOf(element).trim();
            if (!tag.isEmpty()) {
                result.add(tag);
            }
        }
        return List.copyOf(result);
    }
}
