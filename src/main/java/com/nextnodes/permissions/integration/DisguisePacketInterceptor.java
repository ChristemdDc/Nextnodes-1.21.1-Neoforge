package com.nextnodes.permissions.integration;

import com.mojang.authlib.GameProfile;
import com.nextnodes.permissions.DisguiseName;
import com.nextnodes.permissions.PermissionModels.UserEntry;
import com.nextnodes.permissions.PermissionStore;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Netty outbound handler (one per connection) that rewrites the {@code GameProfile} name of disguised
 * players in {@link ClientboundPlayerInfoUpdatePacket} ADD_PLAYER packets, so their over-head nametag
 * shows the disguise name on this viewer's client. The disguised player is never rewritten for their
 * own connection. Pinned to Minecraft 1.21.1 (field name {@code entries}).
 */
public final class DisguisePacketInterceptor extends ChannelOutboundHandlerAdapter {
    public static final String HANDLER_NAME = "nextnodes_disguise";

    /** Reflection into the packet's private {@code entries} list — null if unavailable (then no rewrite). */
    private static final Field ENTRIES_FIELD = resolveEntriesField();

    private final UUID viewerUuid;
    private final PermissionStore store;

    public DisguisePacketInterceptor(UUID viewerUuid, PermissionStore store) {
        this.viewerUuid = viewerUuid;
        this.store = store;
    }

    private static Field resolveEntriesField() {
        try {
            Field f = ClientboundPlayerInfoUpdatePacket.class.getDeclaredField("entries");
            f.setAccessible(true);
            return f;
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        Object out = msg;
        try {
            if (ENTRIES_FIELD != null
                    && msg instanceof ClientboundPlayerInfoUpdatePacket packet
                    && packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                out = maybeRewrite(packet);
            }
        } catch (Exception ignored) {
            out = msg; // ante cualquier fallo, se envía el paquete original (degradado seguro)
        }
        super.write(ctx, out, promise);
    }

    private Object maybeRewrite(ClientboundPlayerInfoUpdatePacket packet) throws Exception {
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = packet.entries();
        List<ClientboundPlayerInfoUpdatePacket.Entry> rewritten = null;
        for (int i = 0; i < entries.size(); i++) {
            ClientboundPlayerInfoUpdatePacket.Entry e = entries.get(i);
            String fake = disguiseFor(e.profileId());
            if (fake == null) {
                if (rewritten != null) rewritten.add(e);
                continue;
            }
            if (rewritten == null) {
                rewritten = new ArrayList<>(entries.subList(0, i));
            }
            GameProfile original = e.profile();
            GameProfile fakeProfile = new GameProfile(original.getId(), fake);
            fakeProfile.getProperties().putAll(original.getProperties()); // conserva la skin
            rewritten.add(new ClientboundPlayerInfoUpdatePacket.Entry(
                    e.profileId(), fakeProfile, e.listed(), e.latency(), e.gameMode(),
                    e.displayName(), e.chatSession()));
        }
        if (rewritten == null) {
            return packet; // ningún jugador disfrazado en este paquete
        }
        // Paquete nuevo con las mismas acciones y entradas reescritas (sin mutar el compartido).
        ClientboundPlayerInfoUpdatePacket copy =
                new ClientboundPlayerInfoUpdatePacket(packet.actions(), List.of());
        ENTRIES_FIELD.set(copy, rewritten);
        return copy;
    }

    /** @return the disguise name for {@code target}, or null (real name) — never rewrites the viewer's own entry. */
    private String disguiseFor(UUID target) {
        if (target.equals(this.viewerUuid)) {
            return null;
        }
        UserEntry user = this.store.snapshot().users.get(target.toString());
        return DisguiseName.shownName(user, false);
    }
}
