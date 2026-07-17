package com.nextnodes.plugin;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Muestra en la lista de multijugador el límite configurado y los jugadores que cuentan para él,
 * en vez del max-players real del proxy (que va deliberadamente muy alto para no bloquear a los
 * rangos con bypass). Los jugadores con bypass no suman al conteo, igual que en el límite real.
 */
public final class PlayerLimitPingListener {
    /** El ping se dispara constantemente; sin caché, cada refresco de la lista costaría consultas a Mongo. */
    private static final long CACHE_TTL_NANOS = 3_000_000_000L;

    private final ProxyServer server;
    private final PlayerLimitMongo limitMongo;
    private final Logger logger;
    private volatile Counts cached;

    public PlayerLimitPingListener(ProxyServer server, PlayerLimitMongo limitMongo, Logger logger) {
        this.server = server;
        this.limitMongo = limitMongo;
        this.logger = logger;
    }

    /** Snapshot inmutable: evita mezclar campos de dos refrescos distintos al leerlos por separado. */
    private static final class Counts {
        final boolean enabled;
        final int max;
        final int online;
        final long atNanos;

        Counts(boolean enabled, int max, int online, long atNanos) {
            this.enabled = enabled;
            this.max = max;
            this.online = online;
            this.atNanos = atNanos;
        }
    }

    @Subscribe
    public void onPing(ProxyPingEvent event) {
        try {
            ServerPing ping = event.getPing();
            // Sin bloque de jugadores, asBuilder() marca nullOutPlayers y build() descarta en silencio
            // los valores que pongamos, así que no hay nada que reescribir.
            if (ping.getPlayers().isEmpty()) return;

            Counts counts = counts();
            if (!counts.enabled) return;

            event.setPing(ping.asBuilder()
                    .onlinePlayers(counts.online)
                    .maximumPlayers(counts.max)
                    .build());
        } catch (Exception ex) {
            // Fail-open: un problema con Mongo no debe romper la lista de servidores.
            logger.warn("No se pudo aplicar el límite al ping, se envía sin modificar: {}", ex.toString(), ex);
        }
    }

    private Counts counts() {
        long now = System.nanoTime();
        Counts snapshot = this.cached;
        if (isFresh(snapshot, now)) return snapshot;
        // Un pico de pings al expirar la caché no debe disparar una estampida de consultas.
        synchronized (this) {
            snapshot = this.cached;
            if (isFresh(snapshot, now)) return snapshot;
            snapshot = compute(now);
            this.cached = snapshot;
            return snapshot;
        }
    }

    private static boolean isFresh(Counts snapshot, long now) {
        return snapshot != null && now - snapshot.atNanos < CACHE_TTL_NANOS;
    }

    private Counts compute(long now) {
        PlayerLimitMongo.Settings settings = limitMongo.loadSettings();
        if (!settings.enabled) return new Counts(false, settings.max, 0, now);

        Set<String> bypassRanks = limitMongo.bypassRankNames();
        List<String> onlineUuids = server.getAllPlayers().stream()
                .map(Player::getUniqueId).map(UUID::toString).collect(Collectors.toList());
        long nonBypassOnline = limitMongo.countOnlineWithoutBypass(onlineUuids, bypassRanks);
        return new Counts(true, settings.max, (int) nonBypassOnline, now);
    }
}
