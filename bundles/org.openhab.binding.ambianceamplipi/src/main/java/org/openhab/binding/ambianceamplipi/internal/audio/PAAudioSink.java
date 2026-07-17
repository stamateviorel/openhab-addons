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
package org.openhab.binding.ambianceamplipi.internal.audio;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.ambianceamplipi.internal.AmbianceAmplipiHandler;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioSinkSync;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.audio.StreamServed;
import org.openhab.core.audio.URLAudioStream;
import org.openhab.core.audio.UnsupportedAudioFormatException;
import org.openhab.core.audio.UnsupportedAudioStreamException;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audio sink that plays public-address announcements on the Ambiance AmpliPi. Enables
 * {@code Voice.say(...)} to the controller: openHAB serves the synthesized audio and the
 * controller fetches it via {@code POST /api/announce}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE, service = PAAudioSink.class)
@NonNullByDefault
public class PAAudioSink extends AudioSinkSync implements ThingHandlerService {

    private final Logger logger = LoggerFactory.getLogger(PAAudioSink.class);

    private static final Set<AudioFormat> SUPPORTED_AUDIO_FORMATS = Set.of(AudioFormat.MP3, AudioFormat.WAV);
    private static final Set<Class<? extends AudioStream>> SUPPORTED_AUDIO_STREAMS = Set.of(AudioStream.class);

    private @Nullable AmbianceAmplipiHandler handler;
    private @Nullable PercentType volume;

    @Override
    protected void processSynchronously(@Nullable AudioStream audioStream)
            throws UnsupportedAudioFormatException, UnsupportedAudioStreamException {
        if (audioStream == null) {
            return;
        }
        AmbianceAmplipiHandler localHandler = this.handler;
        if (localHandler == null) {
            tryClose(audioStream);
            return;
        }
        String callbackUrl = localHandler.getCallbackUrl();
        String audioUrl;
        if (audioStream instanceof URLAudioStream urlAudioStream) {
            audioUrl = urlAudioStream.getURL();
            tryClose(audioStream);
        } else if (callbackUrl != null) {
            StreamServed streamServed;
            try {
                streamServed = localHandler.getAudioHTTPServer().serve(audioStream, 10, true);
            } catch (IOException e) {
                tryClose(audioStream);
                throw new UnsupportedAudioStreamException(
                        "Ambiance was not able to handle the audio stream (cache on disk failed).",
                        audioStream.getClass(), e);
            }
            audioUrl = callbackUrl + streamServed.url();
        } else {
            logger.warn("No callback url, so Ambiance cannot play the audio stream!");
            tryClose(audioStream);
            return;
        }
        localHandler.playPA(audioUrl, volume);
        volume = null;
    }

    private void tryClose(@Nullable InputStream is) {
        if (is != null) {
            try {
                is.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public Set<AudioFormat> getSupportedFormats() {
        return SUPPORTED_AUDIO_FORMATS;
    }

    @Override
    public Set<Class<? extends AudioStream>> getSupportedStreams() {
        return SUPPORTED_AUDIO_STREAMS;
    }

    @Override
    public String getId() {
        AmbianceAmplipiHandler h = handler;
        if (h != null) {
            return h.getThing().getUID().toString();
        }
        throw new IllegalStateException();
    }

    @Override
    public @Nullable String getLabel(@Nullable Locale locale) {
        AmbianceAmplipiHandler h = handler;
        return h != null ? h.getThing().getLabel() : null;
    }

    @Override
    public PercentType getVolume() throws IOException {
        PercentType vol = volume;
        if (vol != null) {
            return vol;
        }
        throw new IOException("Audio sink does not support reporting the volume.");
    }

    @Override
    public void setVolume(final PercentType volume) throws IOException {
        this.volume = volume;
    }

    @Override
    public void setThingHandler(ThingHandler handler) {
        this.handler = (AmbianceAmplipiHandler) handler;
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return handler;
    }
}
