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
package org.openhab.binding.knx.internal.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.openhab.binding.knx.internal.handler.KNXBridgeBaseThingHandler.CommandExtensionData;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.types.Type;

import io.calimero.CloseEvent;
import io.calimero.GroupAddress;
import io.calimero.link.KNXNetworkLink;
import io.calimero.secure.Security;

/**
 * A command that arrives while the client replaces its connection.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class SendDuringReconnectTest {

    private static class ClosableLink extends DummyKNXNetworkLink {
        volatile boolean open = true;

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    private static class ReconnectingClient extends AbstractKNXClient {
        final List<ClosableLink> links = new ArrayList<>();
        final CountDownLatch releaseEntered = new CountDownLatch(1);
        final CountDownLatch releaseMayContinue = new CountDownLatch(1);
        volatile boolean holdRelease;

        ReconnectingClient(ScheduledExecutorService scheduler) {
            super(60, new ThingUID("knx:ip:test"), 2, 50, 3, scheduler, new CommandExtensionData(new TreeMap<>()),
                    Security.newSecurity(), mock(StatusUpdateCallback.class));
        }

        @Override
        protected KNXNetworkLink establishConnection() {
            ClosableLink link = new ClosableLink();
            links.add(link);
            return link;
        }

        @Override
        protected void releaseConnection() {
            if (holdRelease) {
                holdRelease = false;
                releaseEntered.countDown();
                try {
                    releaseMayContinue.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            super.releaseConnection();
        }
    }

    private static final OutboundSpec SWITCH_ON = new OutboundSpec() {
        @Override
        public String getDPT() {
            return "1.001";
        }

        @Override
        public GroupAddress getGroupAddress() {
            return new GroupAddress(31, 0, 1);
        }

        @Override
        public Type getValue() {
            return OnOffType.ON;
        }

        @Override
        public boolean matchesDestination(GroupAddress groupAddress) {
            return getGroupAddress().equals(groupAddress);
        }
    };

    @Test
    @Timeout(30)
    void commandWaitingForTheReconnectIsSentOnTheNewConnection() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        doReturn(mock(ScheduledFuture.class)).when(scheduler).schedule(ArgumentMatchers.<Callable<Boolean>> any(),
                anyLong(), any(TimeUnit.class));
        ReconnectingClient client = new ReconnectingClient(scheduler);
        client.initialize();
        assertTrue(client.isConnected());

        // the link is lost, the client schedules its reconnect and keeps the old communicator until then
        ClosableLink lost = client.links.get(0);
        lost.open = false;
        client.linkClosed(new CloseEvent(lost, CloseEvent.INTERNAL, "communication failure"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Boolean>> reconnect = ArgumentCaptor.forClass(Callable.class);
        verify(scheduler).schedule(reconnect.capture(), anyLong(), eq(TimeUnit.SECONDS));

        // the reconnect has started and is about to tear the old connection down
        client.holdRelease = true;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> reconnecting = executor.submit(reconnect.getValue());
            assertTrue(client.releaseEntered.await(10, TimeUnit.SECONDS));

            // a command arrives now and has to wait for the reconnect
            AtomicReference<@Nullable Thread> commandThread = new AtomicReference<>();
            Future<?> command = executor.submit(() -> {
                commandThread.set(Thread.currentThread());
                client.writeToKNX(SWITCH_ON);
                return null;
            });
            Thread waiting;
            while ((waiting = commandThread.get()) == null || waiting.getState() != Thread.State.BLOCKED) {
                assertFalse(command.isDone(), "the command did not wait for the reconnect");
                Thread.sleep(10);
            }

            client.releaseMayContinue.countDown();
            assertTrue(reconnecting.get());
            command.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, client.links.size());
        assertNotNull(client.links.get(1).getLastFrame(), "the command was not sent on the new connection");
        client.dispose();
    }
}
