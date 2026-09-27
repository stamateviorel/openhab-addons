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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Tests the update instructions that bring Things created by earlier builds up to the current channels.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class OcppUpdateInstructionsTest {

    private static final String INSTRUCTIONS = "/OH-INF/update/instructions.xml";
    private static final String THING_TYPES = "/OH-INF/thing/thing-types.xml";

    @Test
    public void aReplayedMigrationNeverAddsAChannelTwice() throws Exception {
        assertEquals(0, parse(INSTRUCTIONS).getElementsByTagName("add-channel").getLength());
    }

    @Test
    public void eachThingTypeDeclaresTheVersionItsMigrationEndsAt() throws Exception {
        Document thingTypes = parse(THING_TYPES);
        for (Element type : elements(parse(INSTRUCTIONS), "thing-type")) {
            int last = 0;
            for (Element set : elements(type, "instruction-set")) {
                last = Math.max(last, Integer.parseInt(set.getAttribute("targetVersion")));
            }
            String uid = type.getAttribute("uid");
            assertEquals(Integer.toString(last), declaredVersion(thingType(thingTypes, uid)), uid);
        }
    }

    @Test
    public void aMigratedChannelHasTheTypeANewThingGets() throws Exception {
        Document thingTypes = parse(THING_TYPES);
        for (Element type : elements(parse(INSTRUCTIONS), "thing-type")) {
            Map<String, String> declared = new HashMap<>();
            for (Element channel : elements(thingType(thingTypes, type.getAttribute("uid")), "channel")) {
                declared.put(channel.getAttribute("id"), "ocpp:" + channel.getAttribute("typeId"));
            }
            for (Element update : elements(type, "update-channel")) {
                String id = update.getAttribute("id");
                assertEquals(declared.get(id), elements(update, "type").get(0).getTextContent(), id);
            }
        }
    }

    private Document parse(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
    }

    private static Element thingType(Document thingTypes, String uid) {
        String id = uid.substring(uid.indexOf(':') + 1);
        for (String tag : List.of("thing-type", "bridge-type")) {
            for (Element type : elements(thingTypes, tag)) {
                if (id.equals(type.getAttribute("id"))) {
                    return type;
                }
            }
        }
        throw new AssertionError("no thing type " + uid);
    }

    private static @Nullable String declaredVersion(Element thingType) {
        for (Element property : elements(thingType, "property")) {
            if ("thingTypeVersion".equals(property.getAttribute("name"))) {
                return property.getTextContent();
            }
        }
        return null;
    }

    private static List<Element> elements(Node parent, String tag) {
        NodeList nodes = parent instanceof Document document ? document.getElementsByTagName(tag)
                : ((Element) parent).getElementsByTagName(tag);
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            result.add((Element) nodes.item(i));
        }
        return result;
    }
}
