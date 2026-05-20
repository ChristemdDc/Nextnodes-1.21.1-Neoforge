package com.nextnodes.permissions.integration;

import com.nextnodes.permissions.NextNodesPermissions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForgeConfig;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.handler.DefaultPermissionHandler;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

public final class PermissionHandlerEvents {
    private final NextNodesPermissions mod;

    public PermissionHandlerEvents(NextNodesPermissions mod) {
        this.mod = mod;
    }

    @SubscribeEvent
    public void onPermissionGatherHandler(PermissionGatherEvent.Handler event) {
        ModConfigSpec.ConfigValue<String> handlerConfig = NeoForgeConfig.SERVER.permissionHandler;
        if (handlerConfig.get().equals(DefaultPermissionHandler.IDENTIFIER.toString())) {
            handlerConfig.set(NextNodesPermissionHandler.IDENTIFIER.toString());
        }
        event.addPermissionHandler(
                NextNodesPermissionHandler.IDENTIFIER,
                nodes -> new NextNodesPermissionHandler(this.mod.resolver(), nodes)
        );
    }

    @SubscribeEvent
    public void onPermissionGatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(new PermissionNode<>(
                NextNodesPermissions.MOD_ID,
                "admin",
                PermissionTypes.BOOLEAN,
                (player, uuid, context) -> player != null && player.hasPermissions(4)
        ));
    }
}
