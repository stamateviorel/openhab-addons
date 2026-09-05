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

import static org.openhab.binding.ocpp.internal.OcppBindingConstants.CONFIG_CHARGE_POINT_ID;
import static org.openhab.binding.ocpp.internal.OcppBindingConstants.THING_TYPE_CHARGEPOINT;
import static org.openhab.binding.ocpp.internal.OcppBindingConstants.THING_TYPE_SERVER;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Serves the charging dashboard from the binding: an overview page in the sidebar with the month's
 * figures, a stacked chart of the last twelve months, the split per charger and the people, and one
 * page per person with their own history. A read-only {@link UIComponentProvider} in the
 * {@code ui:page} namespace, rebuilt from the binding's own CPMS state — no items to wire.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(service = UIComponentProvider.class, immediate = true)
public class OcppCpmsUiProvider extends AbstractProvider<RootUIComponent> implements UIComponentProvider {

    private static final String NAMESPACE = "ui:page";
    private static final String PAGE_UID = "ocpp_cpms";
    private static final String USER_PAGE_PREFIX = "ocpp_user_";
    private static final int RECENT = 15;
    private static final int USER_RECENT = 30;
    private static final int MONTHS = 12;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ROOT);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yy", Locale.ROOT);
    private static final long REFRESH_SECONDS = 60;
    private static final String[] PALETTE = { "#2196f3", "#4caf50", "#ff9800", "#9c27b0", "#f44336", "#00bcd4",
            "#8bc34a", "#ff5722", "#3f51b5", "#e91e63" };

    private final ThingRegistry thingRegistry;
    private final ScheduledExecutorService scheduler = ThreadPoolManager.getScheduledPool("ocpp-cpms-ui");
    private volatile List<RootUIComponent> pages;
    private volatile String signature;
    private @Nullable ScheduledFuture<?> refreshTask;

    @Activate
    public OcppCpmsUiProvider(@Reference ThingRegistry thingRegistry) {
        this.thingRegistry = thingRegistry;
        this.signature = signatureOf(cpms());
        this.pages = computePages();
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

    /** The pages only exist once there are users — without them a CPMS view makes no sense. */
    private List<RootUIComponent> computePages() {
        CpmsService cpms = cpms();
        if (cpms == null || cpms.users().isEmpty()) {
            return List.of();
        }
        Dashboard dashboard = new Dashboard(cpms, chargerLabels(), ZonedDateTime.now());
        List<RootUIComponent> result = new ArrayList<>();
        result.add(dashboard.overview());
        for (CpmsUser user : cpms.users()) {
            result.add(dashboard.userPage(user));
        }
        return result;
    }

    private void refresh() {
        String current = signatureOf(cpms());
        if (current.equals(signature)) {
            return;
        }
        signature = current;
        Map<String, RootUIComponent> previous = new HashMap<>();
        for (RootUIComponent page : pages) {
            previous.put(page.getUID(), page);
        }
        pages = computePages();
        for (RootUIComponent page : pages) {
            RootUIComponent old = previous.remove(page.getUID());
            if (old != null) {
                notifyListenersAboutUpdatedElement(old, page);
            } else {
                notifyListenersAboutAddedElement(page);
            }
        }
        for (RootUIComponent gone : previous.values()) {
            notifyListenersAboutRemovedElement(gone);
        }
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

    /** What the site calls each charger, so the dashboard says "Charger 2" rather than its serial. */
    private Map<String, String> chargerLabels() {
        Map<String, String> labels = new HashMap<>();
        for (Thing thing : thingRegistry.getAll()) {
            if (THING_TYPE_CHARGEPOINT.equals(thing.getThingTypeUID())) {
                Object id = thing.getConfiguration().get(CONFIG_CHARGE_POINT_ID);
                String label = thing.getLabel();
                if (id != null && label != null && !label.isBlank()) {
                    labels.put(id.toString(), label);
                }
            }
        }
        return labels;
    }

    private String signatureOf(@Nullable CpmsService cpms) {
        if (cpms == null) {
            return "none";
        }
        // Include the month so the "this month" totals rebuild at the rollover, not only on the next session.
        StringBuilder sb = new StringBuilder().append(YearMonth.now()).append('#').append(cpms.transactions().size());
        for (CpmsUser user : cpms.users()) {
            sb.append('|').append(user.id()).append(user.enabled()).append(user.name()).append(user.monthlyCapKwh());
        }
        return sb.toString();
    }

    /** One consistent view of the CPMS state, from which every page is built. */
    private static final class Dashboard {
        private final CpmsService cpms;
        private final Map<String, String> chargerLabels;
        private final ZonedDateTime now;
        private final ZoneId zone;
        private final long nowMs;
        private final long monthStart;
        private final long yearStart;
        private final List<YearMonth> months = new ArrayList<>();
        private final List<CpmsTransaction> transactions;
        private final Map<String, String> userNames = new LinkedHashMap<>();
        private final Map<String, String> userColors = new HashMap<>();

        Dashboard(CpmsService cpms, Map<String, String> chargerLabels, ZonedDateTime now) {
            this.cpms = cpms;
            this.chargerLabels = chargerLabels;
            this.now = now;
            this.zone = now.getZone();
            this.nowMs = now.toInstant().toEpochMilli();
            this.monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli();
            this.yearStart = now.toLocalDate().withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli();
            YearMonth current = YearMonth.from(now);
            for (int i = MONTHS - 1; i >= 0; i--) {
                months.add(current.minusMonths(i));
            }
            this.transactions = cpms.transactions();
            int index = 0;
            for (CpmsUser user : cpms.users()) {
                userNames.put(user.id(), user.name());
                userColors.put(user.id(), PALETTE[index++ % PALETTE.length]);
            }
        }

        RootUIComponent overview() {
            RootUIComponent page = new RootUIComponent(PAGE_UID, "oh-layout-page");
            page.addConfig("label", "OCPP Charging");
            page.addConfig("sidebar", Boolean.TRUE);
            page.addConfig("icon", "f7:bolt_car_fill");
            page.updateTimestamp();
            List<UIComponent> root = page.addSlot("default");

            List<CpmsTransaction> thisMonth = between(transactions, monthStart, nowMs);
            List<CpmsTransaction> thisYear = between(transactions, yearStart, nowMs);
            UIComponent summary = block(now.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ROOT)));
            List<UIComponent> summaryRow = row(summary);
            summaryRow.add(col(statCard("f7:bolt_fill", "This month", kwh(kwhOf(thisMonth)) + " kWh"), 4));
            summaryRow.add(col(statCard("f7:calendar", "This year", kwh(kwhOf(thisYear)) + " kWh"), 4));
            summaryRow.add(col(statCard("f7:number", "Sessions this month", Integer.toString(thisMonth.size())), 4));
            summaryRow.add(col(statCard("f7:person_2_fill", "People charging this month",
                    Long.toString(thisMonth.stream().map(CpmsTransaction::userId).distinct().count())), 4));
            root.add(summary);

            UIComponent monthly = block("Energy per month");
            List<UIComponent> series = new ArrayList<>();
            for (Map.Entry<String, String> user : userNames.entrySet()) {
                series.add(barSeries(user.getValue(), userColors.get(user.getKey()),
                        monthlyKwh(tx -> user.getKey().equals(tx.userId()))));
            }
            row(monthly).add(col(chart(series, true), 1));
            root.add(monthly);

            UIComponent people = block("People");
            UIComponent list = new UIComponent("oh-list-card");
            list.addConfig("mediaList", Boolean.TRUE);
            List<UIComponent> items = list.addSlot("default");
            for (CpmsService.Usage usage : cpms.usage(monthStart, yearStart, nowMs)) {
                CpmsUser user = usage.user();
                UIComponent item = new UIComponent("oh-list-item");
                item.addConfig("title", user.name());
                item.addConfig("icon", user.vehicles().isEmpty() ? "f7:person_fill" : "f7:car_fill");
                item.addConfig("after", kwh(usage.monthKwh()) + " kWh");
                String cap = user.monthlyCapKwh() > 0 ? " · cap " + kwh(user.monthlyCapKwh()) + " kWh" : "";
                item.addConfig("subtitle", kwh(usage.yearKwh()) + " kWh this year" + cap);
                item.addConfig("footer", tokensOf(user));
                if (!user.enabled()) {
                    item.addConfig("badge", "disabled");
                    item.addConfig("badgeColor", "red");
                } else if (user.monthlyCapKwh() > 0 && usage.monthKwh() >= user.monthlyCapKwh()) {
                    item.addConfig("badge", "cap reached");
                    item.addConfig("badgeColor", "orange");
                }
                item.addConfig("action", "navigate");
                item.addConfig("actionPage", "page:" + userPageUid(user));
                items.add(item);
            }
            row(people).add(col(list, 1));
            root.add(people);

            UIComponent chargers = block("Chargers this year");
            List<UIComponent> chargersRow = row(chargers);
            chargersRow.add(col(chart(List.of(pieSeries(kwhPerCharger(thisYear))), false), 2));
            chargersRow.add(col(chargerList(thisMonth, thisYear), 2));
            root.add(chargers);

            UIComponent recent = block("Recent sessions");
            row(recent).add(col(sessionList(cpms.recentTransactions(RECENT), true), 1));
            root.add(recent);
            return page;
        }

        RootUIComponent userPage(CpmsUser user) {
            RootUIComponent page = new RootUIComponent(userPageUid(user), "oh-layout-page");
            page.addConfig("label", user.name());
            page.addConfig("sidebar", Boolean.FALSE);
            page.addConfig("icon", user.vehicles().isEmpty() ? "f7:person_fill" : "f7:car_fill");
            page.updateTimestamp();
            List<UIComponent> root = page.addSlot("default");

            List<CpmsTransaction> mine = transactions.stream().filter(tx -> user.id().equals(tx.userId())).toList();
            List<CpmsTransaction> thisMonth = between(mine, monthStart, nowMs);
            List<CpmsTransaction> thisYear = between(mine, yearStart, nowMs);
            double month = kwhOf(thisMonth);

            UIComponent summary = block(user.name() + (user.enabled() ? "" : " (disabled)"));
            List<UIComponent> summaryRow = row(summary);
            summaryRow.add(col(statCard("f7:bolt_fill", "This month", kwh(month) + " kWh"), 4));
            summaryRow.add(col(statCard("f7:calendar", "This year", kwh(kwhOf(thisYear)) + " kWh"), 4));
            summaryRow.add(col(statCard("f7:number", "Sessions this year", Integer.toString(thisYear.size())), 4));
            String capText = user.monthlyCapKwh() > 0
                    ? kwh(Math.max(0, user.monthlyCapKwh() - month)) + " of " + kwh(user.monthlyCapKwh()) + " kWh left"
                    : "no cap";
            summaryRow.add(col(statCard("f7:gauge", "Monthly cap", capText), 4));
            summaryRow.add(col(statCard("f7:creditcard", "Tokens", tokensOf(user)), 1));
            root.add(summary);

            UIComponent monthly = block("Energy per month");
            row(monthly).add(col(chart(List.of(
                    barSeries(user.name(), userColors.get(user.id()), monthlyKwh(tx -> user.id().equals(tx.userId())))),
                    false), 1));
            root.add(monthly);

            UIComponent chargers = block("Chargers this year");
            List<UIComponent> chargersRow = row(chargers);
            chargersRow.add(col(chart(List.of(pieSeries(kwhPerCharger(thisYear))), false), 2));
            chargersRow.add(col(chargerList(thisMonth, thisYear), 2));
            root.add(chargers);

            UIComponent sessions = block("Sessions");
            List<CpmsTransaction> latest = new ArrayList<>(mine);
            latest.sort((a, b) -> Long.compare(b.stopEpoch(), a.stopEpoch()));
            row(sessions).add(col(sessionList(latest.subList(0, Math.min(USER_RECENT, latest.size())), false), 1));
            root.add(sessions);
            return page;
        }

        private UIComponent chart(List<UIComponent> series, boolean stacked) {
            UIComponent chart = new UIComponent("oh-chart");
            chart.addConfig("height", "320px");
            boolean pie = !series.isEmpty() && "pie".equals(series.get(0).getConfig().get("type"));
            if (!pie) {
                UIComponent grid = new UIComponent("oh-chart-grid");
                grid.addConfig("includeLabels", Boolean.TRUE);
                chart.addSlot("grid").add(grid);
                UIComponent xAxis = new UIComponent("oh-category-axis");
                xAxis.addConfig("categoryType", "values");
                xAxis.addConfig("data", months.stream().map(m -> m.format(MONTH)).toList());
                chart.addSlot("xAxis").add(xAxis);
                UIComponent yAxis = new UIComponent("oh-value-axis");
                yAxis.addConfig("name", "kWh");
                yAxis.addConfig("min", 0);
                chart.addSlot("yAxis").add(yAxis);
            }
            chart.addSlot("series").addAll(series);
            UIComponent tooltip = new UIComponent("oh-chart-tooltip");
            tooltip.addConfig("show", Boolean.TRUE);
            tooltip.addConfig("trigger", pie ? "item" : "axis");
            if (pie) {
                tooltip.addConfig("formatter", "{b}: {c} kWh ({d}%)");
            }
            chart.addSlot("tooltip").add(tooltip);
            if (stacked || pie) {
                UIComponent legend = new UIComponent("oh-chart-legend");
                legend.addConfig("show", Boolean.TRUE);
                legend.addConfig("bottom", 0);
                legend.addConfig("type", "scroll");
                chart.addSlot("legend").add(legend);
            }
            return chart;
        }

        /** Points go out as [month, kWh] pairs: the UI's tooltip reads the value from the second slot. */
        private UIComponent barSeries(String name, @Nullable String color, List<Double> values) {
            UIComponent series = new UIComponent("oh-data-series");
            series.addConfig("type", "bar");
            series.addConfig("name", name);
            series.addConfig("stack", "month");
            List<List<Object>> data = new ArrayList<>();
            for (int i = 0; i < months.size(); i++) {
                data.add(List.of(months.get(i).format(MONTH), values.get(i)));
            }
            series.addConfig("data", data);
            series.addConfig("barMaxWidth", 40);
            if (color != null) {
                series.addConfig("color", color);
            }
            return series;
        }

        private static UIComponent pieSeries(Map<String, Double> slices) {
            UIComponent series = new UIComponent("oh-data-series");
            series.addConfig("type", "pie");
            series.addConfig("name", "kWh");
            series.addConfig("radius", List.of("35%", "65%"));
            List<Map<String, Object>> data = new ArrayList<>();
            for (Map.Entry<String, Double> slice : slices.entrySet()) {
                data.add(Map.of("name", slice.getKey(), "value", Math.round(slice.getValue() * 10) / 10.0));
            }
            series.addConfig("data", data);
            series.addConfig("label", Map.of("formatter", "{b}\n{c} kWh"));
            return series;
        }

        private UIComponent chargerList(List<CpmsTransaction> thisMonth, List<CpmsTransaction> thisYear) {
            UIComponent list = new UIComponent("oh-list-card");
            List<UIComponent> items = list.addSlot("default");
            Map<String, Double> year = kwhPerCharger(thisYear);
            Map<String, Double> month = kwhPerCharger(thisMonth);
            if (year.isEmpty()) {
                items.add(listNote("No sessions this year."));
            }
            for (Map.Entry<String, Double> charger : year.entrySet()) {
                UIComponent item = new UIComponent("oh-list-item");
                item.addConfig("title", charger.getKey());
                item.addConfig("icon", "f7:bolt_car");
                item.addConfig("after", kwh(charger.getValue()) + " kWh");
                item.addConfig("subtitle", kwh(month.getOrDefault(charger.getKey(), 0.0)) + " kWh this month");
                items.add(item);
            }
            return list;
        }

        private UIComponent sessionList(List<CpmsTransaction> sessions, boolean withUser) {
            UIComponent list = new UIComponent("oh-list-card");
            list.addConfig("mediaList", Boolean.TRUE);
            List<UIComponent> items = list.addSlot("default");
            if (sessions.isEmpty()) {
                items.add(listNote("No sessions logged yet."));
            }
            for (CpmsTransaction tx : sessions) {
                String userId = tx.userId();
                String who = userId != null ? userNames.getOrDefault(userId, tx.idTag()) : tx.idTag();
                ZonedDateTime start = Instant.ofEpochMilli(tx.startEpoch()).atZone(zone);
                ZonedDateTime stop = Instant.ofEpochMilli(tx.stopEpoch()).atZone(zone);
                UIComponent item = new UIComponent("oh-list-item");
                item.addConfig("title",
                        withUser ? who : chargerLabel(tx.chargePointId()) + " · socket " + tx.connectorId());
                item.addConfig("icon", "f7:bolt_fill");
                item.addConfig("after", kwh(tx.energyWh() / 1000.0) + " kWh");
                item.addConfig("subtitle", withUser ? chargerLabel(tx.chargePointId()) + " · socket " + tx.connectorId()
                        : start.format(WHEN));
                item.addConfig("footer", start.format(WHEN) + " → " + stop.format(CLOCK) + " · "
                        + duration(Duration.ofMillis(Math.max(0, tx.stopEpoch() - tx.startEpoch()))));
                items.add(item);
            }
            return list;
        }

        private List<Double> monthlyKwh(java.util.function.Predicate<CpmsTransaction> filter) {
            Map<YearMonth, Double> byMonth = new HashMap<>();
            for (CpmsTransaction tx : transactions) {
                if (filter.test(tx)) {
                    YearMonth month = YearMonth.from(Instant.ofEpochMilli(tx.stopEpoch()).atZone(zone));
                    byMonth.merge(month, tx.energyWh() / 1000.0, Double::sum);
                }
            }
            List<Double> data = new ArrayList<>();
            for (YearMonth month : months) {
                data.add(Math.round(byMonth.getOrDefault(month, 0.0) * 10) / 10.0);
            }
            return data;
        }

        private Map<String, Double> kwhPerCharger(List<CpmsTransaction> sessions) {
            Map<String, Double> result = new LinkedHashMap<>();
            for (CpmsTransaction tx : sessions) {
                result.merge(chargerLabel(tx.chargePointId()), tx.energyWh() / 1000.0, Double::sum);
            }
            return result;
        }

        private String chargerLabel(String chargePointId) {
            return chargerLabels.getOrDefault(chargePointId, chargePointId);
        }

        private static List<CpmsTransaction> between(List<CpmsTransaction> sessions, long from, long to) {
            return sessions.stream().filter(tx -> tx.stopEpoch() >= from && tx.stopEpoch() < to).toList();
        }

        private static double kwhOf(List<CpmsTransaction> sessions) {
            return sessions.stream().mapToDouble(CpmsTransaction::energyWh).sum() / 1000.0;
        }

        private static String tokensOf(CpmsUser user) {
            List<String> parts = new ArrayList<>();
            if (!user.cards().isEmpty()) {
                parts.add(user.cards().size() == 1 ? "card " + user.cards().get(0) : user.cards().size() + " cards");
            }
            if (!user.vehicles().isEmpty()) {
                parts.add(user.vehicles().size() == 1 ? "vehicle " + user.vehicles().get(0)
                        : user.vehicles().size() + " vehicles");
            }
            return parts.isEmpty() ? "no tokens" : String.join(" · ", parts);
        }

        private static String userPageUid(CpmsUser user) {
            return USER_PAGE_PREFIX + user.id().replaceAll("[^A-Za-z0-9_]", "_");
        }
    }

    private static UIComponent block(String title) {
        UIComponent block = new UIComponent("oh-block");
        block.addConfig("title", title);
        return block;
    }

    /** A grid row inside a block, so cards line up in columns instead of stacking. */
    private static List<UIComponent> row(UIComponent block) {
        UIComponent row = new UIComponent("oh-grid-row");
        block.addSlot("default").add(row);
        return row.addSlot("default");
    }

    /** A column taking the whole width on a phone and a {@code share}-th of it on a wide screen. */
    private static UIComponent col(UIComponent content, int share) {
        UIComponent col = new UIComponent("oh-grid-col");
        col.addConfig("width", "100");
        if (share > 1) {
            col.addConfig("medium", "50");
            col.addConfig("large", Integer.toString(100 / share));
        }
        col.addSlot("default").add(content);
        return col;
    }

    private static UIComponent statCard(String icon, String title, String value) {
        UIComponent card = new UIComponent("oh-label-card");
        card.addConfig("icon", icon);
        card.addConfig("title", title);
        card.addConfig("label", value);
        card.addConfig("vertical", Boolean.TRUE);
        return card;
    }

    private static UIComponent listNote(String text) {
        UIComponent item = new UIComponent("oh-list-item");
        item.addConfig("title", text);
        return item;
    }

    private static String kwh(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String duration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return hours > 0 ? hours + " h " + minutes + " min" : minutes + " min";
    }
}
