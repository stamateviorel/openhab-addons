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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.binding.BaseDynamicCommandDescriptionProvider;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.thing.type.DynamicCommandDescriptionProvider;
import org.openhab.core.types.CommandDescription;
import org.openhab.core.types.CommandOption;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * Provides the station channel's command options from the controller's live station list, so the
 * openHAB widget's station picker stays in sync with the (editable) station list on the Pi.
 *
 * @author Stamate Viorel - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE, service = { AmbianceStationOptionProvider.class,
        DynamicCommandDescriptionProvider.class })
@NonNullByDefault
public class AmbianceStationOptionProvider extends BaseDynamicCommandDescriptionProvider
        implements ThingHandlerService {

    private @Nullable AmbianceAmplipiHandler handler;

    @Override
    public void setThingHandler(ThingHandler handler) {
        this.handler = (AmbianceAmplipiHandler) handler;
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return handler;
    }

    @Override
    public @Nullable CommandDescription getCommandDescription(Channel channel,
            @Nullable CommandDescription originalCommandDescription, @Nullable Locale locale) {
        ChannelTypeUID typeUID = channel.getChannelTypeUID();
        AmbianceAmplipiHandler localHandler = handler;
        if (typeUID != null && AmbianceAmplipiBindingConstants.CHANNEL_STATION.equals(typeUID.getId())
                && localHandler != null) {
            List<CommandOption> options = new ArrayList<>();
            for (String station : localHandler.getStations()) {
                options.add(new CommandOption(station, station));
            }
            setCommandOptions(channel.getUID(), options);
        }
        return super.getCommandDescription(channel, originalCommandDescription, locale);
    }
}
