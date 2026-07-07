package com.nextnodes.plugin;

import java.util.UUID;

/** Normalizes a UUID (dashed or 32-char hex) to canonical lowercase dashed form (matches the mod's _id). */
public final class UuidUtil {
    private UuidUtil() {}

    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("uuid nulo");
        String hex = raw.trim().replace("-", "");
        if (hex.length() != 32) throw new IllegalArgumentException("uuid inválido: " + raw);
        String dashed = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
        return UUID.fromString(dashed).toString(); // valida hex y devuelve minúsculas canónicas
    }
}
