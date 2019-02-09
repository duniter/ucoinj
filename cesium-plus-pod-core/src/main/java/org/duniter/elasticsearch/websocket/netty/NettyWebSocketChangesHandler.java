package org.duniter.elasticsearch.websocket.netty;

/*
 * #%L
 * Duniter4j :: ElasticSearch Plugin
 * %%
 * Copyright (C) 2014 - 2016 EIS
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

/*
    Copyright 2015 ForgeRock AS

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

import com.google.common.collect.Maps;
import org.apache.commons.collections4.MapUtils;
import org.duniter.elasticsearch.PluginSettings;
import org.duniter.elasticsearch.http.netty.NettyWebSocketServer;
import org.duniter.elasticsearch.http.netty.websocket.NettyBaseWebSocketEndpoint;
import org.duniter.elasticsearch.http.netty.websocket.NettyWebSocketSession;
import org.duniter.elasticsearch.service.changes.ChangeEvent;
import org.duniter.elasticsearch.service.changes.ChangeEvents;
import org.duniter.elasticsearch.service.changes.ChangeService;
import org.duniter.elasticsearch.service.changes.ChangeSource;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;

import javax.websocket.CloseReason;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class NettyWebSocketChangesHandler extends NettyBaseWebSocketEndpoint implements ChangeService.ChangeListener{

    private final static String PATH = WEBSOCKET_PATH + "/_changes";
    public static Collection<ChangeSource> DEFAULT_SOURCES = null;

    private static ESLogger logger;
    private NettyWebSocketSession session;
    private Map<String, ChangeSource> sources;

    public static class Init {

        @Inject
        public Init(NettyWebSocketServer webSocketServer, PluginSettings pluginSettings) {
            logger = Loggers.getLogger("duniter.ws.changes", pluginSettings.getSettings(), new String[0]);

            // Init default sources
            final String[] sourcesStr = pluginSettings.getWebSocketChangesListenSource();
            List<ChangeSource> sources = new ArrayList<>();
            for(String sourceStr : sourcesStr) {
                sources.add(new ChangeSource(sourceStr));
            }
            DEFAULT_SOURCES = sources;

            // Register endpoint
            webSocketServer.addEndpoint(PATH, NettyWebSocketChangesHandler.class);
        }
    }


    @OnOpen
    public void onOpen(NettyWebSocketSession session){
        logger.debug("Connected ... " + session.getId());
        this.session = session;
        this.sources = null;
        ChangeService.registerListener(this);
    }

    @Override
    public void onChange(ChangeEvent changeEvent) {
        session.sendText(ChangeEvents.toJson(changeEvent));
    }

    @Override
    public String getId() {
        return session == null ? null : session.getId();
    }

    @Override
    public Collection<ChangeSource> getChangeSources() {
        if (MapUtils.isEmpty(sources)) return DEFAULT_SOURCES;
        return sources.values();
    }

    @Override
    public void onMessage(String message) {
        addSourceFilter(message);
    }

    @Override
    public void onClose(CloseReason reason) {
        logger.debug("Closing websocket: "+reason);
        ChangeService.unregisterListener(this);
        this.session = null;
    }

    @OnError
    public void onError(Throwable t) {
        logger.error("Error on websocket "+(session == null ? null : session.getId()), t);
    }


    /* -- internal methods -- */

    private void addSourceFilter(String filter) {

        ChangeSource source = new ChangeSource(filter);
        if (source.isEmpty()) {
            logger.debug("Rejecting changes filter (seems to be empty): " + filter);
            return;
        }

        String sourceKey = source.toString();
        if (sources == null || !sources.containsKey(sourceKey)) {
            logger.debug("Adding changes filter: " + filter);
            if (sources == null) {
                sources = Maps.newHashMap();
            }
            sources.put(sourceKey, source);
            ChangeService.refreshListener(this);
        }
    }
}