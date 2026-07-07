package com.nextnodes.tebex;

import java.util.Locale;

/** Mirrors PermissionModels.normalizeName: trim + lowercase. Rank names are ASCII. */
public final class RankNames {
    private RankNames() {}

    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
