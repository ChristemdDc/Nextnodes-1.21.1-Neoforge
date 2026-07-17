package com.nextnodes.plugin;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Rechaza logins que excedan el límite configurado, salvo que el jugador tenga un rango de bypass. */
public final class PlayerLimitListener {
    private final ProxyServer server;
    private final PlayerLimitMongo limitMongo;
    private final Logger logger;

    public PlayerLimitListener(ProxyServer server, PlayerLimitMongo limitMongo, Logger logger) {
        this.server = server;
        this.limitMongo = limitMongo;
        this.logger = logger;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        try {
            PlayerLimitMongo.Settings settings = limitMongo.loadSettings();
            if (!settings.enabled) return;

            Player joiner = event.getPlayer();
            String joinerUuid = joiner.getUniqueId().toString();
            Set<String> bypassRanks = limitMongo.bypassRankNames();
            boolean joinerHasBypass = limitMongo.hasBypassRank(joinerUuid, bypassRanks);
            if (joinerHasBypass) return;

            List<String> onlineUuids = server.getAllPlayers().stream()
                    .map(Player::getUniqueId).map(UUID::toString).collect(Collectors.toList());
            long nonBypassOnline = limitMongo.countOnlineWithoutBypass(onlineUuids, bypassRanks);

            if (!PlayerLimitDecision.shouldAllow(true, false, nonBypassOnline, settings.max)) {
                String message = settings.kickMessage
                        .replace("{online}", String.valueOf(nonBypassOnline))
                        .replace("{max}", String.valueOf(settings.max));
                event.setResult(ResultedEvent.ComponentResult.denied(Component.text(message)));
                logger.info("Login rechazado por límite ({}/{}): {}", nonBypassOnline, settings.max, joiner.getUsername());
            }
        } catch (Exception ex) {
            // Fail-open: un problema con Mongo no debe bloquear el acceso al servidor.
            logger.warn("No se pudo evaluar el límite de jugadores, se permite el acceso: {}", ex.toString(), ex);
        }
    }
}
