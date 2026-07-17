package com.nextnodes.plugin;

/** Lógica pura: decide si un jugador entra dado el estado del límite. Sin dependencias de Mongo/Velocity. */
public final class PlayerLimitDecision {
    private PlayerLimitDecision() {}

    public static boolean shouldAllow(boolean enabled, boolean joinerHasBypass, long nonBypassOnlineCount, int max) {
        if (!enabled) return true;
        if (joinerHasBypass) return true;
        return nonBypassOnlineCount < max;
    }
}
