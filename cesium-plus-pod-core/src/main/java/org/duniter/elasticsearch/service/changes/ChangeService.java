package org.duniter.elasticsearch.service.changes;

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

import com.google.common.collect.ImmutableMap;
import org.duniter.core.util.Preconditions;
import org.duniter.core.util.CollectionUtils;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.indexing.IndexingOperationListener;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesLifecycle;
import org.elasticsearch.indices.IndicesService;
import org.joda.time.DateTime;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

public class ChangeService {

    public interface ChangeListener {
        String getId();
        void onChange(ChangeEvent change);
        Collection<ChangeSource> getChangeSources();
    }

    private static final String SETTING_PRIMARY_SHARD_ONLY = "duniter.changes.primaryShardOnly";

    private final ESLogger log = Loggers.getLogger(ChangeService.class);

    private static final Map<String, ChangeListener> LISTENERS = new ConcurrentHashMap<>();

    private static Map<String, ChangeSource> LISTENERS_SOURCES = new ConcurrentHashMap<>();
    private static Map<String, LongAdder> LISTENERS_SOURCES_USAGE_COUNT = new ConcurrentHashMap<>();

    @Inject
    public ChangeService(final Settings settings, IndicesService indicesService) {
        final boolean allShards = !settings.getAsBoolean(SETTING_PRIMARY_SHARD_ONLY, Boolean.FALSE);


        indicesService.indicesLifecycle().addListener(new IndicesLifecycle.Listener() {
            @Override
            public void afterIndexShardStarted(IndexShard indexShard) {
                final String indexName = indexShard.routingEntry().getIndex();
                if (allShards || indexShard.routingEntry().primary()) {

                    indexShard.indexingService().addListener(new IndexingOperationListener() {
                        @Override
                        public void postCreate(Engine.Create create) {
                            if (!hasListener(indexName, create.type(), create.id())) {
                                return;
                            }

                            ChangeEvent change=new ChangeEvent(
                                    indexName,
                                    create.type(),
                                    create.id(),
                                    new DateTime(),
                                    ChangeEvent.Operation.CREATE,
                                    create.version(),
                                    create.source()
                            );

                            emitChange(change);
                        }

                        @Override
                        public Engine.Delete preDelete(Engine.Delete delete) {

                            return delete;
                        }

                        @Override
                        public void postDelete(Engine.Delete delete) {
                            if (!hasListener(indexName, delete.type(), delete.id())) {
                                return;
                            }

                            ChangeEvent change=new ChangeEvent(
                                    indexName,
                                    delete.type(),
                                    delete.id(),
                                    new DateTime(),
                                    ChangeEvent.Operation.DELETE,
                                    delete.version(),
                                    null
                            );

                            emitChange(change);
                        }

                        @Override
                        public void postIndex(Engine.Index index, boolean created) {
                            if (!hasListener(indexName, index.type(), index.id())) {
                                return;
                            }

                            ChangeEvent change = new ChangeEvent(
                                    indexName,
                                    index.type(),
                                    index.id(),
                                    new DateTime(),
                                    created ? ChangeEvent.Operation.CREATE : ChangeEvent.Operation.INDEX,
                                    index.version(),
                                    index.source()
                            );

                            emitChange(change);
                        }

                        private boolean hasListener(String index, String type, String id) {
                            if (LISTENERS_SOURCES.isEmpty()) return false;

                            for (ChangeSource source : LISTENERS_SOURCES.values()) {
                                if (source.apply(index, type, id)) {
                                    return true;
                                }
                            }

                            return false;
                        }

                        private boolean apply(ChangeListener listener, ChangeEvent change) {
                            Collection<ChangeSource> sources = listener.getChangeSources();

                            // Exclude when no source defined
                            if (CollectionUtils.isEmpty(sources)) return false;

                            for (ChangeSource source : sources) {
                                if (source.apply(change.getIndex(), change.getType(), change.getId())) {
                                    return true;
                                }
                            }

                            return false;
                        }

                        private void emitChange(final ChangeEvent change) {
                            LISTENERS.values().parallelStream()
                                .filter(listener -> apply(listener, change))
                                .forEach(listener -> {
                                    try {
                                        listener.onChange(change);
                                    } catch (Exception e) {
                                        log.error("Failed to emit change event on listener: " + listener.getClass().getName(), e);
                                    }
                                });
                        }
                    });
                }
            }

        });
    }

    public static ChangeListener registerListener(ChangeListener listener) {
        Preconditions.checkNotNull(listener);
        Preconditions.checkNotNull(listener.getId());
        if (LISTENERS.containsKey(listener.getId())) {
            throw new IllegalArgumentException("Listener with id [%s] already registered. Id should be unique");
        }

        // Add to list
        LISTENERS.put(listener.getId(), listener);

        // Update sources
        if (CollectionUtils.isNotEmpty(listener.getChangeSources())) {
            for (ChangeSource source: listener.getChangeSources()) {
                String sourceKey = source.toString();
                if (!LISTENERS_SOURCES.containsKey(sourceKey)) {
                    LISTENERS_SOURCES.put(sourceKey, source);
                }
                LISTENERS_SOURCES_USAGE_COUNT
                        .computeIfAbsent(sourceKey, k -> new LongAdder())
                        .increment();
            }
        }
        return listener;
    }

    /**
     * Usefull when listener sources has changed
     * @param listener
     */
    public static ChangeListener refreshListener(ChangeListener listener) {
        unregisterListener(listener);
        registerListener(listener);
        return listener;
    }

    public static void unregisterListener(ChangeListener listener) {
        LISTENERS.remove(listener.getId());

        // Update sources
        if (CollectionUtils.isNotEmpty(listener.getChangeSources())) {
            for (ChangeSource source: listener.getChangeSources()) {
                String sourceKey = source.toString();
                if (LISTENERS_SOURCES.containsKey(sourceKey)) {
                    LongAdder usageCounter = LISTENERS_SOURCES_USAGE_COUNT.get(sourceKey);;
                    long usageCount = usageCounter != null ? usageCounter.longValue() : 0;
                    if (usageCount > 0) {
                        usageCounter.decrement();
                    }
                    else {
                        LISTENERS_SOURCES.remove(sourceKey);
                        LISTENERS_SOURCES_USAGE_COUNT.remove(sourceKey);
                    }
                }
            }
        }
    }

    public Map<String, Long> getUsageStatistics() {
        return LISTENERS_SOURCES_USAGE_COUNT
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().longValue()
                ));
    }

}
