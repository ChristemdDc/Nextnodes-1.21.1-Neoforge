package com.nextnodes.permissions;

import com.nextnodes.permissions.PermissionModels.Rank;
import com.nextnodes.permissions.PermissionModels.UserEntry;

import java.util.Map;

/** Decide, para el DISPLAY (no permisos), qué rango debe mostrarse: el disfraz si está puesto y existe. */
public final class DisguiseResolver {
    private DisguiseResolver() {}

    /** @return el nombre del rango de disfraz si el usuario tiene uno no-vacío y existe en {@code ranks}; si no, null. */
    public static String displayRank(UserEntry user, Map<String, Rank> ranks) {
        if (user == null || user.disguiseRank == null || user.disguiseRank.isBlank() || ranks == null) {
            return null;
        }
        return ranks.containsKey(user.disguiseRank) ? user.disguiseRank : null;
    }
}
