package com.nextnodes.permissions.integration;

import com.nextnodes.permissions.NextNodesPermissions;
import com.nextnodes.permissions.PermissionResolver;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.handler.IPermissionHandler;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class NextNodesPermissionHandler implements IPermissionHandler {
    public static final ResourceLocation IDENTIFIER = ResourceLocation.fromNamespaceAndPath(NextNodesPermissions.MOD_ID, "permission_handler");

    private final PermissionResolver resolver;
    private final Set<PermissionNode<?>> registeredNodes;

    public NextNodesPermissionHandler(PermissionResolver resolver, Collection<PermissionNode<?>> registeredNodes) {
        this.resolver = resolver;
        this.registeredNodes = Collections.unmodifiableSet(new HashSet<>(registeredNodes));
    }

    @Override
    public ResourceLocation getIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public Set<PermissionNode<?>> getRegisteredNodes() {
        return this.registeredNodes;
    }

    @Override
    public <T> T getPermission(ServerPlayer player, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
        T value = resolve(player == null ? null : player.getUUID(), player, node, context);
        if (value != null) {
            return value;
        }
        return node.getDefaultResolver().resolve(player, player == null ? null : player.getUUID(), context);
    }

    @Override
    public <T> T getOfflinePermission(UUID player, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
        T value = resolve(player, null, node, context);
        if (value != null) {
            return value;
        }
        return node.getDefaultResolver().resolve(null, player, context);
    }

    @SuppressWarnings("unchecked")
    private <T> T resolve(UUID uuid, ServerPlayer player, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
        if (uuid == null) {
            return null;
        }
        if (node.getType() == PermissionTypes.BOOLEAN) {
            Boolean result = this.resolver.resolveBoolean(uuid, player, node.getNodeName(), context);
            return result == null ? null : (T) result;
        }
        if (node.getType() == PermissionTypes.STRING) {
            String result = this.resolver.resolveMeta(uuid, node.getNodeName());
            return result == null ? null : (T) result;
        }
        if (node.getType() == PermissionTypes.INTEGER) {
            String result = this.resolver.resolveMeta(uuid, node.getNodeName());
            if (result == null) {
                return null;
            }
            try {
                return (T) Integer.valueOf(Integer.parseInt(result));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
