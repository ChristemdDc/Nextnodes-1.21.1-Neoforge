package com.nextnodes.plugin;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

public final class GrantCommand implements SimpleCommand {
    private final RankMongo mongo;
    private final Logger logger;

    public GrantCommand(RankMongo mongo, Logger logger) {
        this.mongo = mongo;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length < 2) {
            invocation.source().sendMessage(Component.text("Uso: /nngrant <jugador> <rango> [días]"));
            return;
        }
        Long expiresAt = null;
        if (args.length >= 3) {
            try {
                expiresAt = System.currentTimeMillis() + Days.toMillis(args[2]);
            } catch (IllegalArgumentException ex) {
                invocation.source().sendMessage(Component.text("Error: " + ex.getMessage()));
                return;
            }
        }
        try {
            mongo.grant(args[0], args[1], expiresAt);
            String extra = expiresAt != null ? " por " + args[2] + " día(s)" : "";
            invocation.source().sendMessage(Component.text("Rango '" + args[1] + "' otorgado a " + args[0] + extra));
            logger.info("grant {} {}{}", args[0], args[1], expiresAt != null ? " (" + args[2] + "d)" : "");
        } catch (Exception ex) {
            invocation.source().sendMessage(Component.text("Error: " + ex.getMessage()));
            logger.warn("grant falló ({} {}): {}", args[0], args[1], ex.getMessage());
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("nextnode.plugin");
    }
}
