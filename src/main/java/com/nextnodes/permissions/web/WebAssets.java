package com.nextnodes.permissions.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

public final class WebAssets {
    private static final String WEB_ROOT = "/web/";
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private WebAssets() {
    }

    public static String indexHtml() {
        return text("index.html");
    }

    public static String text(String name) {
        return CACHE.computeIfAbsent(name, key -> {
            try (InputStream stream = WebAssets.class.getResourceAsStream(WEB_ROOT + key)) {
                if (stream == null) {
                    throw new IllegalStateException("Missing web asset: " + WEB_ROOT + key);
                }
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to read web asset: " + WEB_ROOT + key, ex);
            }
        });
    }
}
