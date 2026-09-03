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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.protocols.IProtocol;
import org.java_websocket.protocols.Protocol;
import org.junit.jupiter.api.Test;
import org.openhab.binding.ocpp.internal.transport.event.BootInfo;
import org.openhab.binding.ocpp.internal.transport.event.MeterSample;
import org.openhab.binding.ocpp.internal.transport.event.OcppVersion;
import org.openhab.binding.ocpp.internal.transport.event.StatusInfo;
import org.openhab.binding.ocpp.internal.transport.event.TokenType;
import org.openhab.binding.ocpp.internal.transport.event.TransactionEvent;

import eu.chargetime.ocpp.NotConnectedException;
import eu.chargetime.ocpp.model.Confirmation;
import eu.chargetime.ocpp.model.Request;
import eu.chargetime.ocpp.model.remotetrigger.TriggerMessageRequest;
import eu.chargetime.ocpp.model.remotetrigger.TriggerMessageRequestType;

/**
 * Tests that {@link ChargeTimeTransport} starts the embedded OCA-OCPP server and handles connections.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class ChargeTimeTransportTest {

    private OcppServerListener noopListener() {
        return listener(() -> {
        });
    }

    private final java.util.concurrent.atomic.AtomicReference<OcppVersion> negotiatedVersion = new java.util.concurrent.atomic.AtomicReference<>();

    private OcppServerListener listener(Runnable onOpen) {
        return new OcppServerListener() {
            @Override
            public void onSessionOpened(UUID session, @Nullable String chargePointId,
                    @Nullable InetSocketAddress remote, OcppVersion version) {
                negotiatedVersion.set(version);
                onOpen.run();
            }

            @Override
            public void onSessionClosed(UUID session) {
            }

            @Override
            public void onBootNotification(UUID session, BootInfo boot) {
            }

            @Override
            public void onStatusNotification(UUID session, StatusInfo status) {
            }

            @Override
            public void onMeterValues(UUID session, MeterSample sample) {
            }

            @Override
            public void onHeartbeat(UUID session) {
            }

            @Override
            public void onCapabilities(UUID session, java.util.Map<String, String> configurationKeys) {
            }

            @Override
            public void onTransactionEvent(UUID session, TransactionEvent event) {
            }

            @Override
            public void onAuthorize(UUID session, @Nullable String idToken, TokenType type) {
            }

            @Override
            public boolean isTagAuthorized(@Nullable String idTag) {
                return true;
            }

            @Override
            public int heartbeatFor(UUID session) {
                return 300;
            }

            @Override
            public int nextTransactionId() {
                return 1;
            }
        };
    }

    @Test
    void aChargerNegotiatingOcpp201IsAccepted() throws Exception {
        assertNegotiated("ocpp2.0.1", "ocpp2.0.1", OcppVersion.V2_0_1);
    }

    @Test
    void aChargerNegotiatingOcpp16IsStillAccepted() throws Exception {
        assertNegotiated("ocpp1.6", "ocpp1.6", OcppVersion.V1_6);
    }

    @Test
    void aChargerOfferingNoSubprotocolIsStillAccepted() throws Exception {
        // The multi-protocol feature repository rejects a null version, so the session factory has
        // to fall back to 1.6 for these; without that the connection is dropped.
        assertNegotiated("", "", OcppVersion.V1_6);
    }

    @Test
    void aShortPasswordBasicAuthChargerIsAcceptedOnOcpp201Too() throws Exception {
        // 2.0.1 is length-checked against its own limits (16-40) rather than the 1.6 pair, so a
        // charger set to security profile 1 is refused with a 401 unless both are relaxed.
        CountDownLatch opened = new CountDownLatch(1);
        ChargeTimeTransport transport = new ChargeTimeTransport(listener(opened::countDown), 0, 30, "", "", "");
        int port = findFreePort();
        transport.start("127.0.0.1", port);
        WebSocketClient client = new WebSocketClient(new URI("ws://127.0.0.1:" + port + "/charger201"),
                new Draft_6455(List.of(), List.<IProtocol> of(new Protocol("ocpp2.0.1")))) {
            @Override
            public void onOpen(@Nullable ServerHandshake handshake) {
            }

            @Override
            public void onMessage(@Nullable String message) {
            }

            @Override
            public void onClose(int code, @Nullable String reason, boolean remote) {
            }

            @Override
            public void onError(@Nullable Exception ex) {
            }
        };
        client.addHeader("Authorization",
                "Basic " + Base64.getEncoder().encodeToString("charger201:short".getBytes(StandardCharsets.UTF_8)));
        try {
            client.connectBlocking(5, TimeUnit.SECONDS);
            assertTrue(opened.await(5, TimeUnit.SECONDS),
                    "a short-password Basic-auth charger must be accepted on 2.0.1 when no authPassword is set");
            assertEquals(OcppVersion.V2_0_1, negotiatedVersion.get());
        } finally {
            client.close();
            transport.stop();
        }
    }

    @Test
    void aConfiguredPasswordOutsideTheLibrarysWindowIsStillUsable() throws Exception {
        // 25 characters is past the library's 1.6 maximum of 20; the binding, not the library,
        // decides whether a password is right.
        String password = "a-very-long-site-password";
        assertEquals(true, connectsWith(password, password, "ocpp1.6"),
                "the charge point should be accepted with the configured password");
    }

    @Test
    void aWrongPasswordIsStillRefused() throws Exception {
        assertEquals(false, connectsWith("a-very-long-site-password", "not-the-password", "ocpp1.6"),
                "authentication must still be enforced when a password is configured");
    }

    private boolean connectsWith(String configured, String offered, String subprotocol) throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        ChargeTimeTransport transport = new ChargeTimeTransport(listener(opened::countDown), 0, 30, configured, "", "");
        int port = findFreePort();
        transport.start("127.0.0.1", port);
        WebSocketClient client = new WebSocketClient(new URI("ws://127.0.0.1:" + port + "/authcharger"),
                new Draft_6455(List.of(), List.<IProtocol> of(new Protocol(subprotocol)))) {
            @Override
            public void onOpen(@Nullable ServerHandshake handshake) {
            }

            @Override
            public void onMessage(@Nullable String message) {
            }

            @Override
            public void onClose(int code, @Nullable String reason, boolean remote) {
            }

            @Override
            public void onError(@Nullable Exception ex) {
            }
        };
        client.addHeader("Authorization", "Basic "
                + Base64.getEncoder().encodeToString(("authcharger:" + offered).getBytes(StandardCharsets.UTF_8)));
        try {
            client.connectBlocking(5, TimeUnit.SECONDS);
            return opened.await(3, TimeUnit.SECONDS);
        } finally {
            client.close();
            transport.stop();
        }
    }

    private void assertNegotiated(String offered, String expected, OcppVersion expectedVersion) throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        ChargeTimeTransport transport = new ChargeTimeTransport(listener(opened::countDown), 0, 30, "", "", "");
        int port = findFreePort();
        transport.start("127.0.0.1", port);
        Draft_6455 draft = new Draft_6455(List.of(), List.<IProtocol> of(new Protocol(offered)));
        WebSocketClient client = new WebSocketClient(new URI("ws://127.0.0.1:" + port + "/charger"), draft) {
            @Override
            public void onOpen(@Nullable ServerHandshake handshake) {
            }

            @Override
            public void onMessage(@Nullable String message) {
            }

            @Override
            public void onClose(int code, @Nullable String reason, boolean remote) {
            }

            @Override
            public void onError(@Nullable Exception ex) {
            }
        };
        try {
            assertTrue(client.connectBlocking(5, TimeUnit.SECONDS), "the charger should connect");
            assertTrue(opened.await(5, TimeUnit.SECONDS), "the session should reach the listener");
            assertEquals(expected, client.getProtocol().getProvidedProtocol());
            assertEquals(expectedVersion, negotiatedVersion.get(), "the server must route the session by version");
        } finally {
            client.closeBlocking();
            transport.stop();
        }
    }

    private ChargeTimeTransport newTransport() {
        return new ChargeTimeTransport(noopListener(), 0, 30, "", "", "");
    }

    private static int findFreePort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void constructsTheEmbeddedJsonServer() {
        ChargeTimeTransport transport = newTransport();
        assertNotNull(transport);
        assertFalse(transport.isRunning());
    }

    @Test
    void normalizesTheChargePointIdentifierByStrippingTheLeadingSlash() {
        // The library reports the WebSocket path (e.g. "/charx"); the charge point id is "charx".
        assertEquals("charx", ChargeTimeTransport.normalizeIdentifier("/charx"));
        assertEquals("car3", ChargeTimeTransport.normalizeIdentifier("car3"));
        assertNull(ChargeTimeTransport.normalizeIdentifier(null));
    }

    // A request whose feature profile isn't registered can't be sent; an unknown session must fail as "not connected".
    @Test
    void nonCoreFeatureProfilesAreRegisteredSoTheirRequestsCanBeSent() throws java.io.IOException {
        ChargeTimeTransport transport = newTransport();
        transport.start("127.0.0.1", findFreePort());
        try {
            assertFailsAsNotConnected(transport, ChargingProfileBuilder.currentLimit(1, 16.0, true, null));
            assertFailsAsNotConnected(transport,
                    new TriggerMessageRequest(TriggerMessageRequestType.StatusNotification));
        } finally {
            transport.stop();
        }
    }

    @Test
    void aChargerSendingAShortBasicAuthPasswordIsAccepted() throws Exception {
        // The library rejects a Basic-auth password outside 16-20 chars; with no authPassword set, accept it anyway.
        CountDownLatch opened = new CountDownLatch(1);
        ChargeTimeTransport transport = new ChargeTimeTransport(listener(opened::countDown), 0, 30, "", "", "");
        int port = findFreePort();
        transport.start("127.0.0.1", port);
        WebSocketClient client = new WebSocketClient(new URI("ws://127.0.0.1:" + port + "/testcharger"),
                new Draft_6455(List.of(), List.<IProtocol> of(new Protocol("ocpp1.6")))) {
            @Override
            public void onOpen(@Nullable ServerHandshake handshake) {
            }

            @Override
            public void onMessage(@Nullable String message) {
            }

            @Override
            public void onClose(int code, @Nullable String reason, boolean remote) {
            }

            @Override
            public void onError(@Nullable Exception ex) {
            }
        };
        client.addHeader("Authorization",
                "Basic " + Base64.getEncoder().encodeToString("testcharger: ".getBytes(StandardCharsets.UTF_8)));
        try {
            client.connectBlocking(3, TimeUnit.SECONDS);
            assertTrue(opened.await(3, TimeUnit.SECONDS),
                    "a short-password Basic-auth charger must be accepted when no authPassword is set");
        } finally {
            client.close();
            transport.stop();
        }
    }

    @Test
    void aChargerConnectingOverTlsIsAccepted() throws Exception {
        Path keystore = Path.of(Objects.requireNonNull(getClass().getResource("/tls-test-keystore.p12")).toURI());
        CountDownLatch opened = new CountDownLatch(1);
        ChargeTimeTransport transport = new ChargeTimeTransport(listener(opened::countDown), 0, 30, "",
                keystore.toString(), "testpass");
        int port = findFreePort();
        transport.start("127.0.0.1", port);
        WebSocketClient client = new WebSocketClient(new URI("wss://127.0.0.1:" + port + "/tlscharger"),
                new Draft_6455(List.of(), List.<IProtocol> of(new Protocol("ocpp1.6")))) {
            @Override
            public void onOpen(@Nullable ServerHandshake handshake) {
            }

            @Override
            public void onMessage(@Nullable String message) {
            }

            @Override
            public void onClose(int code, @Nullable String reason, boolean remote) {
            }

            @Override
            public void onError(@Nullable Exception ex) {
            }
        };
        client.setSocketFactory(trustAllContext().getSocketFactory());
        try {
            client.connectBlocking(5, TimeUnit.SECONDS);
            assertTrue(opened.await(5, TimeUnit.SECONDS), "a charger connecting over wss must be accepted");
        } finally {
            client.close();
            transport.stop();
        }
    }

    private static SSLContext trustAllContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] { new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate @Nullable [] chain, @Nullable String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate @Nullable [] chain, @Nullable String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        } }, new SecureRandom());
        return context;
    }

    private void assertFailsAsNotConnected(ChargeTimeTransport transport, Request request) {
        CompletionStage<Confirmation> result = transport.send(UUID.randomUUID(), request);
        CompletionException thrown = assertThrows(CompletionException.class, () -> result.toCompletableFuture().join());
        assertInstanceOf(NotConnectedException.class, thrown.getCause(),
                "expected NotConnectedException — an UnsupportedFeatureException means the feature " + "profile for "
                        + request.getClass().getSimpleName() + " is not registered");
    }

    @Test
    void startVerifiesTheServerAcceptsARealConnection() throws java.io.IOException {
        // The embedded server binds asynchronously, so start() probes and returns only once a socket is listening.
        int port = findFreePort();
        ChargeTimeTransport transport = newTransport();
        transport.start("127.0.0.1", port);
        try {
            assertTrue(transport.isRunning());
            try (java.net.Socket connection = new java.net.Socket()) {
                connection.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                assertTrue(connection.isConnected(), "the started server must accept a TCP connection");
            }
        } finally {
            transport.stop();
        }
        assertFalse(transport.isRunning());
    }

    @Test
    void startFailsWhenThePortIsAlreadyOccupied() throws java.io.IOException {
        // The embedded server signals a failed bind only via an internal callback, so the transport must surface it.
        try (java.net.ServerSocket occupier = new java.net.ServerSocket(0)) {
            ChargeTimeTransport transport = newTransport();
            assertThrows(IllegalStateException.class, () -> transport.start("127.0.0.1", occupier.getLocalPort()));
        }
    }
}
