package com.nextnodes.plugin;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Reads mongoUri/database from &lt;dataDir&gt;/config.properties (creates defaults on first run). */
public final class PluginConfig {
    public final String mongoUri;
    public final String database;

    private PluginConfig(String mongoUri, String database) {
        this.mongoUri = mongoUri;
        this.database = database;
    }

    public static PluginConfig loadOrCreate(Path dataDir, Logger logger) {
        String uri = "mongodb://localhost:27017";
        String db = "nextnodes_permissions";
        try {
            Files.createDirectories(dataDir);
            Path file = dataDir.resolve("config.properties");
            Properties props = new Properties();
            if (Files.exists(file)) {
                try (var in = Files.newInputStream(file)) { props.load(in); }
                uri = props.getProperty("mongoUri", uri);
                db = props.getProperty("database", db);
            } else {
                props.setProperty("mongoUri", uri);
                props.setProperty("database", db);
                try (var out = Files.newOutputStream(file)) {
                    props.store(out, "NextNode Plugin — usa el MISMO mongoUri/database que el mod");
                }
                logger.info("Config creada en {} — ajústala al Mongo del mod y reinicia Velocity.", file);
            }
        } catch (IOException ex) {
            logger.warn("No se pudo leer la config, usando defaults: {}", ex.getMessage());
        }
        return new PluginConfig(uri, db);
    }
}
