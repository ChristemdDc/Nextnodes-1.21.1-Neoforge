package com.nextnodes.plugin;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

public final class RevokeCommand implements SimpleCommand {
    private final RankMongo mongo;
    private final Logger logger;

    public RevokeCommand(RankMongo mongo, Logger logger) {
        this.mongo = mongo;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length < 2) {
            invocation.source().sendMessage(Component.text("Uso: /nnrevoke <uuid> <rango>"));
            return;
        }
        try {
            mongo.revoke(args[0], args[1]);
            invocation.source().sendMessage(Component.text("Rango '" + args[1] + "' quitado a " + args[0]));
            logger.info("revoke {} {}", args[0], args[1]);
        } catch (Exception ex) {
            invocation.source().sendMessage(Component.text("Error: " + ex.getMessage()));
            logger.warn("revoke falló ({} {}): {}", args[0], args[1], ex.getMessage());
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("nextnode.plugin");
    }
}
