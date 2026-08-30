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

import static org.openhab.binding.ocpp.internal.OcppBindingConstants.THING_TYPE_SERVER;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ocpp.internal.cpms.CpmsService;
import org.openhab.binding.ocpp.internal.cpms.CpmsTransaction;
import org.openhab.binding.ocpp.internal.cpms.CpmsUser;
import org.openhab.binding.ocpp.internal.handler.OcppServerBridgeHandler;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.common.registry.AbstractProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.ui.components.RootUIComponent;
import org.openhab.core.ui.components.UIComponent;
import org.openhab.core.ui.components.UIComponentProvider;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

/**
 * Serves the CPMS sidebar page from the binding: each person with their month/year kWh, and the recent
 * charging sessions. A read-only {@link UIComponentProvider} in the {@code ui:page} namespace, rebuilt
 * from the binding's own CPMS state — no items to wire.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(service = UIComponentProvider.class, immediate = true)
public class OcppCpmsUiProvider extends AbstractProvider<RootUIComponent> implements UIComponentProvider {

    private static final String NAMESPACE = "ui:page";
    private static final String PAGE_UID = "ocpp_cpms";
    private static final int RECENT = 20;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ROOT);
    private static final long REFRESH_SECONDS = 60;

    private final ThingRegistry thingRegistry;
    private final ScheduledExecutorService scheduler = ThreadPoolManager.getScheduledPool("ocpp-cpms-ui");
    private volatile List<RootUIComponent> pages;
    private volatile String signature;
    private @Nullable ScheduledFuture<?> refreshTask;

    @Activate
    public OcppCpmsUiProvider(@Reference ThingRegistry thingRegistry) {
        this.thingRegistry = thingRegistry;
        this.signature = signatureOf(cpms());
        this.pages = List.of(buildPage());
        refreshTask = scheduler.scheduleWithFixedDelay(this::refresh, REFRESH_SECONDS, REFRESH_SECONDS,
                TimeUnit.SECONDS);
    }

    @Deactivate
    public void deactivate() {
        ScheduledFuture<?> task = refreshTask;
        if (task != null) {
            task.cancel(true);
            refreshTask = null;
        }
    }

    @Override
    public String getNamespace() {
        return NAMESPACE;
    }

    @Override
    public Collection<RootUIComponent> getAll() {
        return pages;
    }

    private void refresh() {
        String now = signatureOf(cpms());
        if (now.equals(signature)) {
            return;
        }
        signature = now;
        RootUIComponent previous = pages.get(0);
        pages = List.of(buildPage());
        notifyListenersAboutUpdatedElement(previous, pages.get(0));
    }

    private @Nullable CpmsService cpms() {
        for (Thing thing : thingRegistry.getAll()) {
            if (THING_TYPE_SERVER.equals(thing.getThingTypeUID())
                    && thing.getHandler() instanceof OcppServerBridgeHandler handler) {
                return handler.getCpms();
            }
        }
        return null;
    }

    private String signatureOf(@Nullable CpmsService cpms) {
        if (cpms == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder().append(cpms.transactions().size());
        for (CpmsUser user : cpms.users()) {
            sb.append('|').append(user.id()).append(user.enabled()).append(user.name());
        }
        return sb.toString();
    }

    private RootUIComponent buildPage() {
        RootUIComponent page = new RootUIComponent(PAGE_UID, "oh-layout-page");
        page.addConfig("label", "OCPP Charging");
        page.addConfig("sidebar", Boolean.TRUE);
        page.addConfig("icon", "f7:bolt_car_fill");
        page.updateTimestamp();
        List<UIComponent> root = page.addSlot("default");

        CpmsService cpms = cpms();
        if (cpms == null) {
            root.add(note("Add an OCPP Server thing to start."));
            return page;
        }

        ZonedDateTime now = ZonedDateTime.now();
        long nowMs = now.toInstant().toEpochMilli();
        long monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.getZone()).toInstant().toEpochMilli();
        long yearStart = now.toLocalDate().withDayOfYear(1).atStartOfDay(now.getZone()).toInstant().toEpochMilli();

        UIComponent users = block("Users");
        List<UIComponent> userSlot = users.addSlot("default");
        List<CpmsService.Usage> usage = cpms.usage(monthStart, yearStart, nowMs);
        if (usage.isEmpty()) {
            userSlot.add(note("Add an OCPP User thing under the server to manage people and cards."));
        } else {
            for (CpmsService.Usage entry : usage) {
                String title = entry.user().name() + (entry.user().enabled() ? "" : " (disabled)");
                String value = kwh(entry.monthKwh()) + " kWh this month · " + kwh(entry.yearKwh()) + " kWh this year";
                userSlot.add(labelCard("f7:person_fill", title, value));
            }
        }
        root.add(users);

        Map<String, String> names = new HashMap<>();
        for (CpmsUser user : cpms.users()) {
            names.put(user.id(), user.name());
        }

        UIComponent recent = block("Recent sessions");
        List<UIComponent> recentSlot = recent.addSlot("default");
        List<CpmsTransaction> transactions = cpms.recentTransactions(RECENT);
        if (transactions.isEmpty()) {
            recentSlot.add(note("No sessions logged yet."));
        } else {
            for (CpmsTransaction tx : transactions) {
                String userId = tx.userId();
                String who = userId != null ? names.getOrDefault(userId, tx.idTag()) : tx.idTag();
                String when = Instant.ofEpochMilli(tx.stopEpoch()).atZone(now.getZone()).format(WHEN);
                String value = tx.chargePointId() + " · " + kwh(tx.energyWh() / 1000.0) + " kWh · " + when;
                recentSlot.add(labelCard("f7:bolt_fill", who, value));
            }
        }
        root.add(recent);
        return page;
    }

    private static UIComponent block(String title) {
        UIComponent block = new UIComponent("oh-block");
        block.addConfig("title", title);
        return block;
    }

    private static UIComponent labelCard(String icon, String title, String label) {
        UIComponent card = new UIComponent("oh-label-card");
        card.addConfig("icon", icon);
        card.addConfig("title", title);
        card.addConfig("label", label);
        return card;
    }

    private static UIComponent note(String text) {
        UIComponent card = new UIComponent("oh-label-card");
        card.addConfig("label", text);
        return card;
    }

    private static String kwh(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
