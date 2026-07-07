package com.nextnodes.tebex;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(id = "nextnodes-tebex", name = "NextNodes Tebex", version = "1.0.0", authors = {"NextNodes"})
public final class NextNodesTebexPlugin {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDir;
    private RankMongo mongo;

    @Inject
    public NextNodesTebexPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDir) {
        this.server = server;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        PluginConfig config = PluginConfig.loadOrCreate(dataDir, logger);
        try {
            this.mongo = new RankMongo(config.mongoUri, config.database);
        } catch (Exception ex) {
            logger.error("No se pudo conectar a MongoDB ({}). Los comandos fallarán hasta corregir la config.",
                    ex.getMessage());
            return;
        }
        CommandManager cm = server.getCommandManager();
        cm.register(cm.metaBuilder("nngrant").plugin(this).build(), new GrantCommand(mongo, logger));
        cm.register(cm.metaBuilder("nnrevoke").plugin(this).build(), new RevokeCommand(mongo, logger));
        logger.info("NextNodes Tebex listo (base '{}').", config.database);
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (this.mongo != null) this.mongo.close();
    }
}
