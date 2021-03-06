package org.duniter.elasticsearch.rest.network;

/*
 * #%L
 * duniter4j-elasticsearch-plugin
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

import org.duniter.core.client.model.bma.NetworkPeers;
import org.duniter.core.exception.TechnicalException;
import org.duniter.core.util.CollectionUtils;
import org.duniter.core.util.StringUtils;
import org.duniter.elasticsearch.PluginSettings;
import org.duniter.elasticsearch.rest.JacksonJsonRestResponse;
import org.duniter.elasticsearch.rest.security.RestSecurityController;
import org.duniter.elasticsearch.service.NetworkService;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.*;

import java.io.IOException;
import java.util.List;

/**
 * A rest to post a request to process a new currency/peer.
 *
 */
public class RestNetworkPeersGetAction extends BaseRestHandler {


    private NetworkService networkService;

    @Inject
    public RestNetworkPeersGetAction(Settings settings, PluginSettings pluginSettings, RestController controller, Client client, RestSecurityController securityController,
                                     NetworkService networkService) {
        super(settings, controller, client);

        if (StringUtils.isBlank(pluginSettings.getClusterRemoteHost())) {
            logger.warn(String.format("The cluster address can not be published on the network. /\\!\\\\ Fill in the options [cluster.remote.xxx] in the configuration (recommended)."));
        }
        else {
            securityController.allow(RestRequest.Method.GET, "(/[^/]+)?/network/peers");

            controller.registerHandler(RestRequest.Method.GET, "/network/peers", this);
            controller.registerHandler(RestRequest.Method.GET, "/{currency}/network/peers", this);
        }

        this.networkService = networkService;
    }

    @Override
    protected void handleRequest(RestRequest request, RestChannel channel, Client client) throws Exception {
        String currency = request.param("currency");


        try {
            NetworkPeers result = new NetworkPeers();
            List<NetworkPeers.Peer> peers = networkService.getBmaPeers(currency);
            if (CollectionUtils.isNotEmpty(peers)) {
                result.peers = peers.toArray(new NetworkPeers.Peer[peers.size()]);
            }
            else {
                result.peers = new NetworkPeers.Peer[0];
            }

            channel.sendResponse(new JacksonJsonRestResponse(request, RestStatus.OK, result));
        }
        catch(IOException ioe) {
            throw new TechnicalException(String.format("Error while generating JSON for [/network/peers]: %s", ioe.getMessage()), ioe);
        }
    }

}