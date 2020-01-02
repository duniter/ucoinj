package org.duniter.elasticsearch.user.synchro;

import org.duniter.core.client.model.bma.EndpointApi;
import org.duniter.core.service.CryptoService;
import org.duniter.elasticsearch.user.PluginSettings;
import org.duniter.elasticsearch.client.Duniter4jClient;
import org.duniter.elasticsearch.synchro.AbstractSynchroAction;
import org.duniter.elasticsearch.threadpool.ThreadPool;

public abstract class AbstractSynchroUserAction extends AbstractSynchroAction {

    private EndpointApi endpointApi;

    public AbstractSynchroUserAction(String index, String type,
                                 Duniter4jClient client,
                                 PluginSettings pluginSettings,
                                 CryptoService cryptoService,
                                 ThreadPool threadPool) {
        super(index, type, index, type, client, pluginSettings.getDelegate(), cryptoService, threadPool);

        // Define endpoint API to used by synchronization, to select peers to request
        this.endpointApi = pluginSettings.getUserEndpointApi();

    }

    public AbstractSynchroUserAction(String fromIndex, String fromType,
                                 String toIndex, String toType,
                                 Duniter4jClient client,
                                 PluginSettings pluginSettings,
                                 CryptoService cryptoService,
                                 ThreadPool threadPool) {
        super(fromIndex, fromType, toIndex, toType, client, pluginSettings.getDelegate(), cryptoService, threadPool);

        // Define endpoint API to used by synchronization, to select peers to request
        this.endpointApi = pluginSettings.getUserEndpointApi();
    }

    @Override
    public EndpointApi getEndPointApi() {
        return endpointApi;
    }
}