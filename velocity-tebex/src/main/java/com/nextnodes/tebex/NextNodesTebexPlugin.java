package com.nextnodes.tebex;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

@Plugin(id = "nextnodes-tebex", name = "NextNodes Tebex", version = "1.0.0", authors = {"NextNodes"})
public final class NextNodesTebexPlugin {
    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public NextNodesTebexPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        this.logger.info("NextNodes Tebex cargado.");
    }
}
