package com.nextnodes.plugin;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Derives the offline-mode UUID from a username, exactly as the server does
 * ({@code UUID.nameUUIDFromBytes("OfflinePlayer:<name>")} — a version-3 UUID).
 * Needed because an offline/proxied server stores this, NOT the premium Mojang UUID.
 */
public final class OfflineUuid {
    private OfflineUuid() {}

    public static String of(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("nombre de jugador vacío");
        }
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + username.trim()).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
