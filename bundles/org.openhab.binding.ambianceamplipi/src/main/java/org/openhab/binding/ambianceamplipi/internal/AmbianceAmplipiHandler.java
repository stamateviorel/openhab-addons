/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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
package org.openhab.binding.ambianceamplipi.internal;

import static org.openhab.binding.ambianceamplipi.internal.AmbianceAmplipiBindingConstants.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.ambianceamplipi.internal.audio.PAAudioSink;
import org.openhab.binding.ambianceamplipi.internal.discovery.AmbianceZoneDiscoveryService;
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceHealth;
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceRadio;
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceSleep;
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceSource;
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceSpotify;
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceStatus;
import org.openhab.binding.ambianceamplipi.internal.model.AmbianceSystemStats;
import org.openhab.core.audio.AudioHTTPServer;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.NextPreviousType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.RawType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * The bridge handler for an Ambiance AmpliPi controller: polls {@code /api/status}, pushes
 * channel states, fetches album art, fans the status out to zone handlers, and forwards
 * commands (station, transport, master volume/mute, siren, announce) to the controller.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class AmbianceAmplipiHandler extends BaseBridgeHandler {

    private static final int REQUEST_TIMEOUT = 5000;

    private final Logger logger = LoggerFactory.getLogger(AmbianceAmplipiHandler.class);
    private final HttpClient httpClient;
    private final AudioHTTPServer audioHTTPServer;
    private final @Nullable String callbackUrl;
    private final Gson gson = new Gson();

    private String baseUrl = "http://ambiance:8080";
    private volatile List<String> latestStations = List.of();
    private volatile List<String> latestSources = List.of();
    private volatile boolean sourceAware; // false against pre-source firmware -> radio-only endpoints
    private final CopyOnWriteArrayList<AmbianceStatusChangeListener> changeListeners = new CopyOnWriteArrayList<>();
    private @Nullable ScheduledFuture<?> refreshJob;
    private @Nullable ScheduledFuture<?> systemJob;

    public AmbianceAmplipiHandler(Bridge bridge, HttpClient httpClient, AudioHTTPServer audioHTTPServer,
            @Nullable String callbackUrl) {
        super(bridge);
        this.httpClient = httpClient;
        this.audioHTTPServer = audioHTTPServer;
        this.callbackUrl = callbackUrl;
    }

    @Override
    public void initialize() {
        AmbianceAmplipiConfiguration config = getConfigAs(AmbianceAmplipiConfiguration.class);
        String host = config.hostname;
        if (host == null || host.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Hostname not set");
            return;
        }
        baseUrl = "http://" + host + ":" + config.port;
        updateStatus(ThingStatus.UNKNOWN);
        refreshJob = scheduler.scheduleWithFixedDelay(this::poll, 0, config.refreshInterval, TimeUnit.SECONDS);
        // host diagnostics on a slower cadence — the controller samples its CPU on each call
        systemJob = scheduler.scheduleWithFixedDelay(this::pollSystem, 5, 60, TimeUnit.SECONDS);
    }

    private void poll() {
        try {
            ContentResponse response = httpClient.newRequest(baseUrl + "/api/status")
                    .timeout(REQUEST_TIMEOUT, TimeUnit.MILLISECONDS).send();
            if (response.getStatus() != HttpStatus.OK_200) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Ambiance API returned HTTP " + response.getStatus());
                return;
            }
            AmbianceStatus status = gson.fromJson(response.getContentAsString(), AmbianceStatus.class);
            if (status == null) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Empty API response");
                return;
            }
            updateStatus(ThingStatus.ONLINE);
            updateChannels(status);
            fetchCover();
            changeListeners.forEach(l -> l.receive(status));
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Ambiance request failed: " + e.getMessage());
        } catch (Exception e) {
            logger.warn("Unexpected error polling Ambiance: {}", e.getMessage());
        }
    }

    private void updateChannels(AmbianceStatus status) {
        AmbianceSource source = status.source;
        AmbianceSpotify spotify = status.spotify;
        sourceAware = source != null && source.active != null;
        boolean spotifyActive = sourceAware && "spotify".equals(source.active) && spotify != null && spotify.running;
        if (sourceAware) {
            updateState(CHANNEL_SOURCE, new StringType(source.active));
            latestSources = source.available != null ? source.available : List.of();
        }
        AmbianceRadio radio = status.radio;
        if (spotifyActive) {
            // now-playing reflects the source that owns the audio path: bold line = song,
            // artist line = artist, secondary line = album
            updateState(CHANNEL_CONTROL, spotify.playing ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
            updateState(CHANNEL_POWER, OnOffType.from(spotify.playing));
            updateState(CHANNEL_TRACK, new StringType(nullToEmpty(spotify.track)));
            updateState(CHANNEL_ARTIST, new StringType(nullToEmpty(spotify.artist)));
            updateState(CHANNEL_TITLE, new StringType(nullToEmpty(spotify.album)));
        } else if (radio != null) {
            updateState(CHANNEL_CONTROL, radio.playing ? PlayPauseType.PLAY : PlayPauseType.PAUSE);
            updateState(CHANNEL_POWER, OnOffType.from(radio.playing));
            updateState(CHANNEL_TITLE, new StringType(nullToEmpty(radio.title)));
            updateState(CHANNEL_ARTIST, new StringType(nullToEmpty(radio.artist)));
            updateState(CHANNEL_TRACK, new StringType(nullToEmpty(radio.track)));
        }
        if (radio != null) {
            updateState(CHANNEL_STATION, radio.station != null ? new StringType(radio.station) : UnDefType.NULL);
            latestStations = radio.stations != null ? radio.stations : List.of();
        }
        updateState(CHANNEL_MASTER_VOLUME, new PercentType(Math.max(0, Math.min(100, status.masterVol))));
        updateState(CHANNEL_MASTER_MUTE, OnOffType.from(status.masterMute));
        updateState(CHANNEL_SIREN, OnOffType.from(status.siren));
        AmbianceSleep sleep = status.sleep;
        if (sleep != null) {
            updateState(CHANNEL_SLEEP, new DecimalType(
                    sleep.active ? Math.max(1, (int) Math.ceil(sleep.remainingS / 60.0)) : 0));
        }
        AmbianceHealth health = status.health;
        if (health != null) {
            updateState(CHANNEL_HEALTH_OK, OnOffType.from(health.ok));
            String summary = health.ok ? "ok"
                    : (health.issues != null && !health.issues.isEmpty() ? String.join("; ", health.issues)
                            : "probleem");
            updateState(CHANNEL_HEALTH, new StringType(summary));
        }
    }

    private void pollSystem() {
        try {
            ContentResponse response = httpClient.newRequest(baseUrl + "/api/system")
                    .timeout(REQUEST_TIMEOUT, TimeUnit.MILLISECONDS).send();
            if (response.getStatus() != HttpStatus.OK_200) {
                return; // pre-system firmware — diagnostics stay undefined
            }
            AmbianceSystemStats stats = gson.fromJson(response.getContentAsString(), AmbianceSystemStats.class);
            if (stats == null) {
                return;
            }
            updateState(CHANNEL_CPU, new DecimalType(stats.cpuPct));
            AmbianceSystemStats.Usage mem = stats.mem;
            if (mem != null) {
                updateState(CHANNEL_MEMORY, new DecimalType(mem.pct));
            }
            AmbianceSystemStats.Usage disk = stats.disk;
            if (disk != null) {
                updateState(CHANNEL_DISK, new DecimalType(disk.pct));
            }
            Double temp = stats.tempC;
            if (temp != null) {
                updateState(CHANNEL_TEMPERATURE, new QuantityType<>(temp, SIUnits.CELSIUS));
            }
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            logger.debug("Ambiance system stats poll failed: {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("Unexpected error polling Ambiance system stats: {}", e.getMessage());
        }
    }

    private void fetchCover() {
        try {
            ContentResponse cover = httpClient.newRequest(baseUrl + "/api/cover").method(HttpMethod.GET)
                    .timeout(REQUEST_TIMEOUT, TimeUnit.MILLISECONDS).send();
            byte[] content = cover.getContent();
            if (cover.getStatus() == HttpStatus.OK_200 && content != null && content.length > 0) {
                updateState(CHANNEL_COVER, new RawType(content, "image/jpeg"));
            } else {
                updateState(CHANNEL_COVER, UnDefType.NULL);
            }
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            updateState(CHANNEL_COVER, UnDefType.NULL);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            return; // the poll refreshes everything
        }
        switch (channelUID.getId()) {
            case CHANNEL_STATION:
                if (command instanceof StringType) {
                    send(HttpMethod.POST, "/api/radio", Map.of("station", command.toString()));
                }
                break;
            case CHANNEL_POWER:
                if (command instanceof OnOffType) {
                    // source-aware: resumes the ACTIVE source (spotify falls back to radio when the
                    // session is gone); OFF silences every source (away-mode kill list)
                    send(HttpMethod.POST, command == OnOffType.ON ? playPath() : stopPath(), null);
                }
                break;
            case CHANNEL_CONTROL:
                if (command instanceof PlayPauseType) {
                    send(HttpMethod.POST, command == PlayPauseType.PLAY ? playPath() : stopPath(), null);
                } else if (command instanceof NextPreviousType) {
                    String next = sourceAware ? "/api/source/next" : "/api/radio/next";
                    String prev = sourceAware ? "/api/source/prev" : "/api/radio/prev";
                    send(HttpMethod.POST, command == NextPreviousType.NEXT ? next : prev, null);
                }
                break;
            case CHANNEL_SOURCE:
                if (command instanceof StringType) {
                    send(HttpMethod.POST, "/api/source", Map.of("name", command.toString()));
                }
                break;
            case CHANNEL_SLEEP:
                // minutes until the active source is silenced; 0 cancels the timer
                if (command instanceof DecimalType decimal) {
                    send(HttpMethod.POST, "/api/sleep", Map.of("minutes", Math.max(0, decimal.intValue())));
                } else if (command instanceof QuantityType<?> quantity) {
                    send(HttpMethod.POST, "/api/sleep", Map.of("minutes", Math.max(0, quantity.intValue())));
                }
                break;
            case CHANNEL_MASTER_VOLUME:
                if (command instanceof PercentType percent) {
                    send(HttpMethod.PATCH, "/api/zones", Map.of("vol", percent.intValue()));
                }
                break;
            case CHANNEL_MASTER_MUTE:
                if (command instanceof OnOffType) {
                    send(HttpMethod.PATCH, "/api/zones", Map.of("mute", command == OnOffType.ON));
                }
                break;
            case CHANNEL_SIREN:
                if (command instanceof OnOffType) {
                    send(HttpMethod.POST, "/api/alarm", Map.of("on", command == OnOffType.ON));
                }
                break;
            case CHANNEL_ANNOUNCE:
                if (command instanceof StringType) {
                    String url = command.toString().trim();
                    if (!url.isBlank()) {
                        playPA(url, null);
                    }
                }
                break;
            default:
                break;
        }
    }

    private String playPath() {
        return sourceAware ? "/api/source/play" : "/api/radio/play";
    }

    private String stopPath() {
        return sourceAware ? "/api/source/stop" : "/api/radio/stop";
    }

    /** Play a URL as a PA announcement (used by the announce channel and the audio sink). */
    public void playPA(String audioUrl, @Nullable PercentType volume) {
        Map<String, Object> body = volume != null ? Map.of("url", audioUrl, "vol", volume.intValue())
                : Map.of("url", audioUrl);
        send(HttpMethod.POST, "/api/announce", body);
    }

    private void send(HttpMethod method, String path, @Nullable Map<String, Object> body) {
        try {
            Request req = httpClient.newRequest(baseUrl + path).method(method).timeout(REQUEST_TIMEOUT,
                    TimeUnit.MILLISECONDS);
            if (body != null) {
                req.content(new StringContentProvider(gson.toJson(body)), "application/json");
            }
            ContentResponse resp = req.send();
            if (resp.getStatus() != HttpStatus.OK_200) {
                logger.warn("Ambiance API {} {} -> HTTP {}", method, path, resp.getStatus());
            }
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Ambiance request failed: " + e.getMessage());
        }
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> job = refreshJob;
        if (job != null) {
            job.cancel(true);
            refreshJob = null;
        }
        ScheduledFuture<?> sysJob = systemJob;
        if (sysJob != null) {
            sysJob.cancel(true);
            systemJob = null;
        }
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(PAAudioSink.class, AmbianceStationOptionProvider.class, AmbianceZoneDiscoveryService.class);
    }

    public void addStatusChangeListener(AmbianceStatusChangeListener listener) {
        changeListeners.addIfAbsent(listener); // idempotent: zones re-attach on bridge re-init
    }

    public void removeStatusChangeListener(AmbianceStatusChangeListener listener) {
        changeListeners.remove(listener);
    }

    public List<String> getStations() {
        return new ArrayList<>(latestStations);
    }

    public List<String> getSources() {
        return new ArrayList<>(latestSources);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public AudioHTTPServer getAudioHTTPServer() {
        return audioHTTPServer;
    }

    public @Nullable String getCallbackUrl() {
        return callbackUrl;
    }
}
