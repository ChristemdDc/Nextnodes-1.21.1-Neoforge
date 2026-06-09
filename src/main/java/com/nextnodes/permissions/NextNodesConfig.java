package com.nextnodes.permissions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mod configuration stored in <code>config/nextnodes.json</code>. The file is created with
 * defaults on first run. JVM system properties (e.g. <code>-Dnextnodes.web.host=...</code>)
 * still work and take precedence over the file, so existing setups keep working.
 */
public final class NextNodesConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "nextnodes.json";

    /** MongoDB connection string (may include credentials). */
    public String mongoUri = "mongodb://localhost:27017";
    /** MongoDB database name. */
    public String mongoDatabase = "nextnodes_permissions";
    /** TCP port the web panel listens on. On a managed host use one of your allocated ports. */
    public int webPort = 25900;
    /** Public IP or domain shown in the panel URL. Empty = auto-detect (the server's local IP). */
    public String webHost = "";
    /** Minutes the web panel stays open before auto-closing. */
    public int sessionMinutes = 15;

    /**
     * Loads {@code config/nextnodes.json} (creating it with defaults if missing), then applies any
     * JVM system-property overrides. The file always stores the file-based values, never the overrides.
     */
    public static NextNodesConfig load() {
        Path file = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        NextNodesConfig config = new NextNodesConfig();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                NextNodesConfig loaded = GSON.fromJson(reader, NextNodesConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (Exception ignored) {
                // Corrupt/unreadable file: fall back to defaults and rewrite it below.
            }
        }
        config.sanitize();
        config.write(file);
        config.applySystemPropertyOverrides();
        return config;
    }

    private void sanitize() {
        if (this.mongoUri == null || this.mongoUri.isBlank()) {
            this.mongoUri = "mongodb://localhost:27017";
        }
        if (this.mongoDatabase == null || this.mongoDatabase.isBlank()) {
            this.mongoDatabase = "nextnodes_permissions";
        }
        if (this.webPort <= 0 || this.webPort > 65535) {
            this.webPort = 25900;
        }
        if (this.webHost == null) {
            this.webHost = "";
        }
        this.webHost = this.webHost.trim();
        if (this.sessionMinutes <= 0) {
            this.sessionMinutes = 15;
        }
    }

    private void write(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException ignored) {
            // Read-only config dir: keep going with the in-memory values.
        }
    }

    private void applySystemPropertyOverrides() {
        this.mongoUri = System.getProperty("nextnodes.mongodb.uri", this.mongoUri);
        this.mongoDatabase = System.getProperty("nextnodes.mongodb.database", this.mongoDatabase);
        Integer port = Integer.getInteger("nextnodes.web.port");
        if (port != null && port > 0 && port <= 65535) {
            this.webPort = port;
        }
        String host = System.getProperty("nextnodes.web.host");
        if (host != null && !host.trim().isEmpty()) {
            this.webHost = host.trim();
        }
        Integer session = Integer.getInteger("nextnodes.web.session");
        if (session != null && session > 0) {
            this.sessionMinutes = session;
        }
    }
}
