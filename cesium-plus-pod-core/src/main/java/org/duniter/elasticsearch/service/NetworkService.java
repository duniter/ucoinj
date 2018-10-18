package org.duniter.elasticsearch.service;

/*
 * #%L
 * UCoin Java Client :: Core API
 * %%
 * Copyright (C) 2014 - 2015 EIS
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


import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.ArrayUtils;
import org.duniter.core.client.dao.CurrencyDao;
import org.duniter.core.client.dao.PeerDao;
import org.duniter.core.client.model.bma.*;
import org.duniter.core.client.model.local.Currency;
import org.duniter.core.client.model.local.Peer;
import org.duniter.core.client.service.HttpService;
import org.duniter.core.client.service.bma.NetworkRemoteService;
import org.duniter.core.client.util.KnownBlocks;
import org.duniter.core.client.util.KnownCurrencies;
import org.duniter.core.exception.TechnicalException;
import org.duniter.core.service.CryptoService;
import org.duniter.core.util.CollectionUtils;
import org.duniter.core.util.Preconditions;
import org.duniter.core.util.StringUtils;
import org.duniter.elasticsearch.PluginSettings;
import org.duniter.elasticsearch.client.Duniter4jClient;
import org.duniter.elasticsearch.dao.CurrencyExtendDao;
import org.duniter.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.common.inject.Inject;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Created by Benoit on 30/03/2015.
 */
public class NetworkService extends AbstractService {

    private static final BlockchainBlock DEFAULT_BLOCK = KnownBlocks.getFirstBlock(KnownCurrencies.G1);

    private CurrencyExtendDao currencyDao;
    private BlockchainService blockchainService;
    private Map<String, NetworkPeering> peeringByCurrencyCache = Maps.newHashMap();

    // API where to send the peer document
    private final static Set<EndpointApi> targetPeersEndpointApis = Sets.newHashSet();
    // API to include inside the peer document
    private final static Set<EndpointApi> publishedEndpointApis = Sets.newHashSet();

    private final ThreadPool threadPool;
    private final PeerDao peerDao;
    private HttpService httpService;
    private NetworkRemoteService networkRemoteService;
    private PeerService peerService;

    @Inject
    public NetworkService(Duniter4jClient client,
                          PluginSettings settings,
                          CryptoService cryptoService,
                          CurrencyDao currencyDao,
                          PeerDao peerDao,
                          BlockchainService blockchainService,
                          PeerService peerService,
                          ThreadPool threadPool,
                          final ServiceLocator serviceLocator
    ) {
        super("duniter.network", client, settings, cryptoService);
        this.peerDao = peerDao;
        this.currencyDao = (CurrencyExtendDao)currencyDao;
        this.blockchainService = blockchainService;
        this.peerService = peerService;
        this.threadPool = threadPool;
        threadPool.scheduleOnStarted(() -> {
            this.httpService = serviceLocator.getHttpService();
            this.networkRemoteService = serviceLocator.getNetworkRemoteService();
            setIsReady(true);
        });

        // If published API defined in settings, use this list
        if (CollectionUtils.isNotEmpty(pluginSettings.getPeeringPublishedApis())) {
            addAllPublishEndpointApis(pluginSettings.getPeeringPublishedApis());
        }
        // Else (nothing in settings), register ES_CORE_API as published API
        else {
            addPublishEndpointApi(EndpointApi.ES_CORE_API);
        }

        // If targeted API defined in settings, use this list
        if (CollectionUtils.isNotEmpty(pluginSettings.getPeeringTargetedApis())) {
            addAllTargetPeerEndpointApis(pluginSettings.getPeeringTargetedApis());
        }
    }


    protected List<Peer> getConfigIncludesPeers(final String currencyId, final EndpointApi api) {
        Preconditions.checkNotNull(currencyId);
        String[] endpoints = pluginSettings.getSynchroIncludesEndpoints();
        if (ArrayUtils.isEmpty(endpoints)) return null;

        List<Peer> peers = Lists.newArrayList();
        for (String endpoint: endpoints) {
            try {
                String[] endpointPart = endpoint.split(":");
                if (endpointPart.length > 2) {
                    logger.warn(String.format("Error in config: Unable to parse P2P endpoint [%s]: %s", endpoint));
                }
                String epCurrencyId = (endpointPart.length == 2) ? endpointPart[0] : null /*optional*/;

                NetworkPeering.Endpoint ep = (endpointPart.length == 2) ? Endpoints.parse(endpointPart[1]) : Endpoints.parse(endpoint);
                if (ep != null && ep.api == api && (epCurrencyId == null || currencyId.equals(epCurrencyId))) {
                    Peer peer = Peer.newBuilder()
                            .setEndpoint(ep)
                            .setCurrency(currencyId)
                            .build();

                    String hash = cryptoService.hash(peer.computeKey());
                    peer.setHash(hash);
                    peer.setId(hash);

                    peers.add(peer);
                }

            } catch (IOException e) {
                if (logger.isDebugEnabled()) {
                    logger.warn(String.format("Unable to parse P2P endpoint [%s]: %s", endpoint, e.getMessage()), e);
                }
                else {
                    logger.warn(String.format("Unable to parse P2P endpoint [%s]: %s", endpoint, e.getMessage()));
                }
            }
        }
        return peers;
    }

    public boolean hasSomePeers(Set<EndpointApi> peerApiFilters) {

        List<String> currencyIds = currencyDao.getCurrencyIds();
        if (CollectionUtils.isEmpty(currencyIds)) return false;

        for (String currencyId: currencyIds) {
            boolean hasSome = peerDao.hasPeersUpWithApi(currencyId, peerApiFilters);
            if (hasSome) return true;
        }

        return false;
    }

    public boolean waitPeersReady(Set<EndpointApi> peerApiFilters) throws InterruptedException{

        waitReady();

        final int sleepTime = 30 * 1000 /*30s*/;

        int maxWaitingDuration = 5 * 6 * sleepTime; // 5 min
        int waitingDuration = 0;
        while (!isReady() && !hasSomePeers(peerApiFilters)) {
            // Wait
            Thread.sleep(sleepTime);
            waitingDuration += sleepTime;
            if (waitingDuration >= maxWaitingDuration) {
                logger.warn(String.format("Could not start to publish peering. No Peer found (after waiting %s min).", waitingDuration/60/1000));
                return false; // stop here
            }
        }

        // Wait again, to make sure all peers have been saved by NetworkService
        Thread.sleep(sleepTime*2);

        return true;
    }

    public Collection<Peer> getPeersFromApis(final String currencyId, final Collection<EndpointApi> apis) {

        return apis.stream().flatMap(api -> getPeersFromApi(currencyId, api).stream()).collect(Collectors.toList());
    }

    public Collection<Peer> getPeersFromApi(final String currencyId, final EndpointApi api) {
        Preconditions.checkNotNull(api);
        Preconditions.checkArgument(StringUtils.isNotBlank(currencyId));

        try {

            // Use map by URL, to avoid duplicated peer
            Map<String, Peer> peersByUrls = Maps.newHashMap();

            // Get peers from config
            List<Peer> configPeers = getConfigIncludesPeers(currencyId, api);
            if (CollectionUtils.isNotEmpty(configPeers)) {
                configPeers.forEach(p -> peersByUrls.put(p.getUrl(), p));
            }

            // Get peers by pubkeys, from config
            String[] includePubkeys = pluginSettings.getSynchroIncludesPubkeys();
            if (ArrayUtils.isNotEmpty(includePubkeys)) {

                // Get from DAO, by API and pubkeys
                List<Peer> pubkeysPeers = peerDao.getPeersByCurrencyIdAndApiAndPubkeys(currencyId, api.name(), includePubkeys);
                if (CollectionUtils.isNotEmpty(pubkeysPeers)) {
                    pubkeysPeers.stream()
                            .filter(Objects::nonNull)
                            .forEach(p -> peersByUrls.put(p.getUrl(), p));
                }
            }

            // Add discovered peers
            if (pluginSettings.enableSynchroDiscovery()) {
                List<Peer> discoveredPeers = peerDao.getPeersByCurrencyIdAndApi(currencyId, api.name());
                if (CollectionUtils.isNotEmpty(discoveredPeers)) {
                    discoveredPeers.stream()
                            .filter(Objects::nonNull)
                            .forEach(p -> peersByUrls.put(p.getUrl(), p));
                }
            }

            return peersByUrls.values();
        }
        catch (Exception e) {
            logger.error(String.format("Could not get peers for Api [%s]", api.name()), e);
            return null;
        }
    }

    public boolean isEsNodeAliveAndValid(Peer peer) {
        Preconditions.checkNotNull(peer);
        Preconditions.checkNotNull(peer.getCurrency());

        try {
            // TODO: check version is compatible
            //String version = networkService.getVersion(peer);

            Currency currency = currencyDao.getById(peer.getCurrency());
            if (currency == null) return false;

            BlockchainBlock block = httpService.executeRequest(peer, String.format("/%s/block/0/_source", peer.getCurrency()), BlockchainBlock.class);

            return Objects.equals(block.getCurrency(), peer.getCurrency()) &&
                    Objects.equals(block.getSignature(), currency.getFirstBlockSignature());

        }
        catch(Exception e) {
            logger.debug(String.format("[%s] [%s] Peer not alive or invalid: %s", peer.getCurrency(), peer, e.getMessage()));
            return false;
        }
    }

    public void addTargetPeerEndpointApi(EndpointApi api) {
        Preconditions.checkNotNull(api);

        if (!targetPeersEndpointApis.contains(api)) {
            targetPeersEndpointApis.add(api);
        }
    }


    public void addPublishEndpointApi(EndpointApi api) {
        Preconditions.checkNotNull(api);

        if (!publishedEndpointApis.contains(api)) {
            if (pluginSettings.enablePeering()) {
                logger.debug(String.format("Adding {%s} as published endpoint", api.name()));
            }
            publishedEndpointApis.add(api);
        }
    }

    public NetworkPeering getPeering(String currency, boolean useCache) {

        waitReady();

        // Retrieve the currency to use
        boolean enableBlockchainIndexation = pluginSettings.enableBlockchainIndexation() && currencyDao.existsIndex();
        if (StringUtils.isBlank(currency)) {
            List<String> currencyIds = enableBlockchainIndexation ? currencyDao.getCurrencyIds() : null;
            if (CollectionUtils.isNotEmpty(currencyIds)) {
                currency = currencyIds.get(0);
            } else {
                currency = DEFAULT_BLOCK.getCurrency();
            }
        }

        // Get result from cache, is allow
        if (useCache) {
            NetworkPeering result = peeringByCurrencyCache.get(currency);
            if (result != null) return result;
        }

        // create and fill a new peering object
        NetworkPeering result = new NetworkPeering();

        // Get current block
        BlockchainBlock currentBlock = enableBlockchainIndexation ? blockchainService.getCurrentBlock(currency) : null;
        if (currentBlock == null) {
            currentBlock = DEFAULT_BLOCK;
            currency = currentBlock.getCurrency();
        }

        result.setVersion(Protocol.VERSION);
        result.setCurrency(currency);
        result.setBlock(String.format("%s-%s", currentBlock.getNumber(), currentBlock.getHash()));
        result.setPubkey(pluginSettings.getNodePubkey());
        result.setStatus("UP");

        // Add endpoints
        if (CollectionUtils.isNotEmpty(publishedEndpointApis)) {
            List<NetworkPeering.Endpoint> endpoints = Lists.newArrayList();
            for (EndpointApi endpointApi: publishedEndpointApis) {
                NetworkPeering.Endpoint ep = new NetworkPeering.Endpoint();
                ep.setDns(pluginSettings.getClusterRemoteHost());
                ep.setApi(endpointApi);
                ep.setPort(pluginSettings.getClusterRemotePort());
                endpoints.add(ep);
            }
            result.setEndpoints(endpoints.toArray(new NetworkPeering.Endpoint[endpoints.size()]));
        }


        // Compute raw, then sign it
        String raw = result.toString();
        String signature = cryptoService.sign(raw, pluginSettings.getNodeKeypair().getSecKey());
        raw += signature + "\n";

        result.setRaw(raw);
        result.setSignature(signature);

        // Add result to cache
        peeringByCurrencyCache.put(currency, result);

        return result;
    }

    public NetworkPeering getLastPeering(String currency) {
        return getPeering(currency, true);
    }

    public NetworkService startPublishingPeerDocumentToNetwork() {

        if (CollectionUtils.isEmpty(publishedEndpointApis)) {
            logger.debug("Skipping peer document publishing (No endpoint API to publish)");
            return this;
        }
        if (CollectionUtils.isEmpty(targetPeersEndpointApis)) {
            logger.debug("Skipping peer document publishing (No endpoint API to target)");
            return this;
        }

        // Launch once, at startup (after a delay)
        threadPool.schedule(() -> {
                logger.info(String.format("Publishing endpoints %s to targeted peers %s", publishedEndpointApis, targetPeersEndpointApis));
                boolean launchAtStartup;
                try {
                    // wait for some peers
                    launchAtStartup = waitPeersReady(targetPeersEndpointApis);
                } catch (InterruptedException e) {
                    return; // stop
                }

                if (launchAtStartup) {
                    publishPeerDocumentToNetwork();
                }

                // Schedule next execution
                threadPool.scheduleAtFixedRate(
                        this::publishPeerDocumentToNetwork,
                        pluginSettings.getPeeringInterval() * 1000,
                        pluginSettings.getPeeringInterval() * 1000 /* convert in ms */,
                        TimeUnit.MILLISECONDS);
            },
            30 * 1000 /*wait 30 s */ ,
            TimeUnit.MILLISECONDS
        );

        return this;
    }

    public NetworkPeering checkAndSavePeering(String peeringDocument) {
        Preconditions.checkNotNull(peeringDocument);
        NetworkPeering peering;
        try {
            peering = NetworkPeerings.parse(peeringDocument);
        }
        catch(Exception e) {
            throw new TechnicalException("Inavlid peer document: " + e.getMessage(), e);
        }

        // Check validity then save
        return checkAndSavePeering(peering);

    }

    public NetworkPeering checkAndSavePeering(NetworkPeering peering) {

        if (CollectionUtils.isEmpty(peering.getEndpoints())) {
            logger.debug("Ignoring peer document (no endpoint to process)");
            return peering;
        }

        // Check signature
        checkSignature(peering);

        // Transform endpoint to peers
        List<Peer> peers = Lists.newArrayList();
        for (NetworkPeering.Endpoint ep : peering.getEndpoints()) {
            Peer peer = Peer.newBuilder()
                    .setCurrency(peering.getCurrency())
                    .setPubkey(peering.getPubkey())
                    .setEndpoint(ep).build();
            EndpointApi api = EndpointApi.valueOf(peer.getApi());
            peers.add(peer);

            // TODO: filter to keep only useful API ?
            //if (targetPeersEndpointApis.contains(api)) {
            //    peers.add(peer);
            //}
            //else {
            //    logger.debug(String.format("Ignoring endpoint {%s}: not a targeted API", peer));
            //}
        }

        // Save peers
        if (CollectionUtils.isEmpty(peers)) {
            peerService.save(peering.getCurrency(), peers, false);
        }

        return peering;
    }

    /* -- protected -- */

    public void checkSignature(NetworkPeering peering) {
        Preconditions.checkNotNull(peering);
        Preconditions.checkNotNull(peering.getSignature());

        String signature = peering.getSignature();

        try {
            // Generate raw document
            peering.setSignature(null);
            String raw = peering.toString();

            // Check signature
            if (!cryptoService.verify(raw, signature, peering.getPubkey())) {
                throw new TechnicalException("Invalid document signature");
            }
        }
        finally {
            peering.setSignature(signature); // Restore the signature
        }
    }

    protected void publishPeerDocumentToNetwork() {
        List<String> currencyIds;
        try {
            currencyIds = currencyDao.getCurrencyIds();
        }
        catch (Exception e) {
            logger.error("Could not retrieve indexed currencies", e);
            currencyIds = null;
        }
        if (CollectionUtils.isEmpty(currencyIds)) {
            logger.warn("Skipping the publication of peer document (no indexed currency)");
            return;
        }

        if (CollectionUtils.isEmpty(targetPeersEndpointApis) ||
            CollectionUtils.isEmpty(publishedEndpointApis)) {
            logger.warn("Skipping the publication of peer document (no targeted API, or no API to publish)");
            return;
        }

        // For each currency
        currencyIds.forEach(currencyId -> {

            logger.debug(String.format("[%s] Publishing peer document to network... {peers discovery: %s}", currencyId, pluginSettings.enableSynchroDiscovery()));

            // Create a new peer document (will add it to cache)
            String peerDocument = getPeering(currencyId, false/*force new peering*/).toString();

            // Get peers for targeted APIs
            Collection<Peer> peers = getPeersFromApis(currencyId, targetPeersEndpointApis);

            if (CollectionUtils.isNotEmpty(peers)) {
                // Send document to every peers
                long count = peers.stream().map(p -> this.safePublishPeerDocumentToPeer(currencyId, p, peerDocument)).filter(Boolean.TRUE::equals).count();

                logger.info(String.format("[%s] Peer document sent to %s/%s peers", currencyId, count, peers.size()));
            } else {
                logger.debug(String.format("[%s] No peer document sent (no targeted peer found)", currencyId));
            }
        });
    }

    protected boolean safePublishPeerDocumentToPeer(final String currencyId, final Peer peer, final String peerDocument) {
        Preconditions.checkNotNull(currencyId);
        Preconditions.checkNotNull(peer);
        Preconditions.checkNotNull(peer.getApi());
        Preconditions.checkNotNull(peer.getUrl());
        Preconditions.checkNotNull(peerDocument);

        try {
            networkRemoteService.postPeering(peer, peerDocument);
            return true;
        }
        catch(Exception e) {
            logger.error(String.format("[%s] [%s] Error when sending peer document: %s", currencyId, peer, e.getMessage()));
            return false;
        }

    }

    protected void addAllTargetPeerEndpointApis(List<EndpointApi> apis) {
        Preconditions.checkNotNull(apis);
        apis.forEach(this::addTargetPeerEndpointApi);
    }

    protected void addAllPublishEndpointApis(List<EndpointApi> apis) {
        Preconditions.checkNotNull(apis);
        apis.forEach(this::addPublishEndpointApi);
    }
}