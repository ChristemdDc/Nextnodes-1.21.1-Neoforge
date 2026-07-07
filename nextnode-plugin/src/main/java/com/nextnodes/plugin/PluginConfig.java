package com.nextnodes.plugin;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Reads config from &lt;dataDir&gt;/config.properties (creates defaults on first run). */
public final class PluginConfig {
    public final String mongoUri;
    public final String database;
    /** false (default) = servidor offline: el UUID se deriva del nombre. true = premium: el arg es el UUID. */
    public final boolean onlineMode;

    private PluginConfig(String mongoUri, String database, boolean onlineMode) {
        this.mongoUri = mongoUri;
        this.database = database;
        this.onlineMode = onlineMode;
    }

    public static PluginConfig loadOrCreate(Path dataDir, Logger logger) {
        String uri = "mongodb://localhost:27017";
        String db = "nextnodes_permissions";
        boolean online = false;
        try {
            Files.createDirectories(dataDir);
            Path file = dataDir.resolve("config.properties");
            Properties props = new Properties();
            if (Files.exists(file)) {
                try (var in = Files.newInputStream(file)) { props.load(in); }
                uri = props.getProperty("mongoUri", uri);
                db = props.getProperty("database", db);
                online = Boolean.parseBoolean(props.getProperty("onlineMode", "false"));
            } else {
                props.setProperty("mongoUri", uri);
                props.setProperty("database", db);
                props.setProperty("onlineMode", "false");
                try (var out = Files.newOutputStream(file)) {
                    props.store(out, "NextNode Plugin — usa el MISMO mongoUri/database que el mod. "
                            + "onlineMode=false: servidor offline, el UUID se deriva del nombre (usa {username} en la tienda). "
                            + "onlineMode=true: premium (usa {uuid}).");
                }
                logger.info("Config creada en {} — ajústala al Mongo del mod y reinicia Velocity.", file);
            }
        } catch (IOException ex) {
            logger.warn("No se pudo leer la config, usando defaults: {}", ex.getMessage());
        }
        return new PluginConfig(uri, db, online);
    }
}
