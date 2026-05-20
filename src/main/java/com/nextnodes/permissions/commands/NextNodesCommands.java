package com.nextnodes.permissions.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.nextnodes.permissions.NextNodesPermissions;
import com.nextnodes.permissions.PermissionModels;
import com.nextnodes.permissions.PermissionModels.PermissionData;
import com.nextnodes.permissions.PermissionModels.Rank;
import com.nextnodes.permissions.PermissionModels.UserEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

public final class NextNodesCommands {
    private final NextNodesPermissions mod;

    public NextNodesCommands(NextNodesPermissions mod) {
        this.mod = mod;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("nextnodes")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("reload")
                        .executes(context -> {
                            try {
                                this.mod.reload();
                                context.getSource().sendSuccess(() -> Component.literal("NextNodes Permissions recargado desde SQLite."), true);
                                return 1;
                            } catch (IOException ex) {
                                context.getSource().sendFailure(Component.literal("No se pudo recargar: " + ex.getMessage()));
                                return 0;
                            }
                        }))
                .then(Commands.literal("check")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("permission", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            ServerPlayer player = EntityArgument.getPlayer(context, "player");
                                            String permission = StringArgumentType.getString(context, "permission");
                                            Boolean result = this.mod.resolver().resolveBoolean(player.getUUID(), player, permission);
                                            String text = result == null ? "undefined" : result.toString();
                                            context.getSource().sendSuccess(() -> Component.literal(player.getGameProfile().getName() + " -> " + permission + " = " + text), false);
                                            return result != null && result ? 1 : 0;
                                        })))));

        event.getDispatcher().register(Commands.literal("nn")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("open")
                        .then(Commands.literal("web")
                                .executes(context -> {
                                    try {
                                        if (this.mod.isWebPanelRunning()) {
                                            context.getSource().sendSuccess(() -> styledMessage(
                                                    "El panel web ya esta activo.",
                                                    this.mod.webUrl(),
                                                    null
                                            ), false);
                                            return 1;
                                        }
                                        String password = this.mod.startWebPanel();
                                        String url = this.mod.webUrl();
                                        context.getSource().sendSuccess(() -> styledMessage(
                                                "Panel web iniciado (15 minutos).",
                                                url,
                                                password
                                        ), false);
                                        return 1;
                                    } catch (IOException ex) {
                                        context.getSource().sendFailure(Component.literal("No se pudo iniciar el panel: " + ex.getMessage()));
                                        return 0;
                                    }
                                })))
                .then(Commands.literal("close")
                        .then(Commands.literal("web")
                                .executes(context -> {
                                    if (!this.mod.isWebPanelRunning()) {
                                        context.getSource().sendFailure(Component.literal("El panel web no esta activo."));
                                        return 0;
                                    }
                                    this.mod.stopWebPanel();
                                    context.getSource().sendSuccess(() -> Component.literal("Panel web detenido.").withStyle(ChatFormatting.RED), false);
                                    return 1;
                                })))
                .then(Commands.literal("rank")
                        .then(Commands.literal("list")
                                .executes(context -> {
                                    PermissionData data = this.mod.store().snapshot();
                                    if (data.ranks.isEmpty()) {
                                        context.getSource().sendSuccess(() -> Component.literal("No hay rangos definidos.").withStyle(ChatFormatting.GRAY), false);
                                        return 0;
                                    }
                                    MutableComponent msg = Component.literal("Rangos (" + data.ranks.size() + ")").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
                                    for (Rank r : data.ranks.values()) {
                                        msg.append(Component.literal("\n  "));
                                        msg.append(Component.literal(r.name).withStyle(ChatFormatting.WHITE));
                                        if (!r.displayName.equals(r.name)) {
                                            msg.append(Component.literal(" (" + r.displayName + ")").withStyle(ChatFormatting.GRAY));
                                        }
                                        msg.append(Component.literal(" peso:" + r.weight).withStyle(ChatFormatting.DARK_AQUA));
                                        if (!r.parents.isEmpty()) {
                                            msg.append(Component.literal(" hereda:" + String.join(",", r.parents)).withStyle(ChatFormatting.DARK_GRAY));
                                        }
                                    }
                                    context.getSource().sendSuccess(() -> msg, false);
                                    return data.ranks.size();
                                }))
                        .then(Commands.literal("info")
                                .then(Commands.argument("jugador", StringArgumentType.word())
                                        .suggests(playerSuggestions())
                                        .executes(context -> {
                                            String input = StringArgumentType.getString(context, "jugador");
                                            UserEntry user = findUser(input, context.getSource().getServer());
                                            if (user == null) {
                                                context.getSource().sendFailure(Component.literal("Jugador no encontrado: " + input));
                                                return 0;
                                            }
                                            String display = user.name.isBlank() ? user.uuid : user.name;
                                            MutableComponent msg = Component.literal(display).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
                                            msg.append(Component.literal("\n  Rango principal: ").withStyle(ChatFormatting.GRAY));
                                            msg.append(Component.literal(user.primaryRank.isBlank() ? "(ninguno)" : user.primaryRank).withStyle(ChatFormatting.YELLOW));
                                            msg.append(Component.literal("\n  Rangos: ").withStyle(ChatFormatting.GRAY));
                                            msg.append(Component.literal(user.ranks.isEmpty() ? "(ninguno)" : String.join(", ", user.ranks)).withStyle(ChatFormatting.WHITE));
                                            context.getSource().sendSuccess(() -> msg, false);
                                            return 1;
                                        })))
                        .then(Commands.literal("add")
                                .then(Commands.argument("jugador", StringArgumentType.word())
                                        .suggests(playerSuggestions())
                                        .then(Commands.argument("rango", StringArgumentType.word())
                                                .suggests(rankSuggestions())
                                                .executes(context -> {
                                                    String input = StringArgumentType.getString(context, "jugador");
                                                    String rankName = StringArgumentType.getString(context, "rango");
                                                    return cmdRankAdd(context.getSource(), input, rankName);
                                                }))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("jugador", StringArgumentType.word())
                                        .suggests(playerSuggestions())
                                        .then(Commands.argument("rango", StringArgumentType.word())
                                                .suggests(rankSuggestions())
                                                .executes(context -> {
                                                    String input = StringArgumentType.getString(context, "jugador");
                                                    String rankName = StringArgumentType.getString(context, "rango");
                                                    return cmdRankRemove(context.getSource(), input, rankName);
                                                }))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("jugador", StringArgumentType.word())
                                        .suggests(playerSuggestions())
                                        .then(Commands.argument("rango", StringArgumentType.word())
                                                .suggests(rankSuggestions())
                                                .executes(context -> {
                                                    String input = StringArgumentType.getString(context, "jugador");
                                                    String rankName = StringArgumentType.getString(context, "rango");
                                                    return cmdRankSet(context.getSource(), input, rankName);
                                                }))))));
    }

    private int cmdRankAdd(CommandSourceStack source, String input, String rankName) {
        PermissionData data = this.mod.store().snapshot();
        String normalized = PermissionModels.normalizeName(rankName);
        if (!data.ranks.containsKey(normalized)) {
            source.sendFailure(Component.literal("El rango '" + normalized + "' no existe. Usa /nn rank list para ver los disponibles."));
            return 0;
        }
        UserEntry user = findUser(input, source.getServer());
        if (user == null) {
            source.sendFailure(Component.literal("Jugador no encontrado: " + input));
            return 0;
        }
        if (user.ranks.contains(normalized)) {
            String display = user.name.isBlank() ? user.uuid : user.name;
            source.sendFailure(Component.literal(display + " ya tiene el rango '" + normalized + "'."));
            return 0;
        }
        user.ranks.add(normalized);
        if (user.primaryRank.isBlank()) {
            user.primaryRank = normalized;
        }
        try {
            this.mod.store().saveUser(user);
            String display = user.name.isBlank() ? user.uuid : user.name;
            source.sendSuccess(() -> Component.literal("Rango '" + normalized + "' añadido a " + display + ".").withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Error al guardar: " + ex.getMessage()));
            return 0;
        }
    }

    private int cmdRankRemove(CommandSourceStack source, String input, String rankName) {
        String normalized = PermissionModels.normalizeName(rankName);
        UserEntry user = findUser(input, source.getServer());
        if (user == null) {
            source.sendFailure(Component.literal("Jugador no encontrado: " + input));
            return 0;
        }
        if (!user.ranks.contains(normalized)) {
            String display = user.name.isBlank() ? user.uuid : user.name;
            source.sendFailure(Component.literal(display + " no tiene el rango '" + normalized + "'."));
            return 0;
        }
        user.ranks.remove(normalized);
        if (normalized.equals(user.primaryRank)) {
            String fallback = this.mod.store().snapshot().defaultRank;
            user.primaryRank = user.ranks.isEmpty() ? fallback : user.ranks.get(0);
        }
        try {
            this.mod.store().saveUser(user);
            String display = user.name.isBlank() ? user.uuid : user.name;
            source.sendSuccess(() -> Component.literal("Rango '" + normalized + "' eliminado de " + display + ".").withStyle(ChatFormatting.RED), true);
            return 1;
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Error al guardar: " + ex.getMessage()));
            return 0;
        }
    }

    private int cmdRankSet(CommandSourceStack source, String input, String rankName) {
        PermissionData data = this.mod.store().snapshot();
        String normalized = PermissionModels.normalizeName(rankName);
        if (!data.ranks.containsKey(normalized)) {
            source.sendFailure(Component.literal("El rango '" + normalized + "' no existe. Usa /nn rank list para ver los disponibles."));
            return 0;
        }
        UserEntry user = findUser(input, source.getServer());
        if (user == null) {
            source.sendFailure(Component.literal("Jugador no encontrado: " + input));
            return 0;
        }
        user.primaryRank = normalized;
        if (!user.ranks.contains(normalized)) {
            user.ranks.add(normalized);
        }
        try {
            this.mod.store().saveUser(user);
            String display = user.name.isBlank() ? user.uuid : user.name;
            source.sendSuccess(() -> Component.literal("Rango principal de " + display + " establecido a '" + normalized + "'.").withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Error al guardar: " + ex.getMessage()));
            return 0;
        }
    }

    private UserEntry findUser(String input, MinecraftServer server) {
        PermissionData data = this.mod.store().snapshot();
        UUID parsedUuid = tryParseUuid(input);
        if (parsedUuid != null) {
            UserEntry found = data.users.get(parsedUuid.toString());
            if (found != null) return copyUser(found);
        }
        if (server != null) {
            ServerPlayer online = server.getPlayerList().getPlayerByName(input);
            if (online != null) {
                UserEntry found = data.users.get(online.getUUID().toString());
                if (found != null) return copyUser(found);
                UserEntry fresh = new UserEntry();
                fresh.uuid = online.getUUID().toString();
                fresh.name = online.getGameProfile().getName();
                fresh.primaryRank = data.defaultRank;
                fresh.ranks = new ArrayList<>(List.of(data.defaultRank));
                return fresh;
            }
        }
        return data.users.values().stream()
                .filter(u -> u.name.equalsIgnoreCase(input))
                .findFirst()
                .map(NextNodesCommands::copyUser)
                .orElse(null);
    }

    private static UserEntry copyUser(UserEntry src) {
        UserEntry copy = new UserEntry();
        copy.uuid = src.uuid;
        copy.name = src.name;
        copy.primaryRank = src.primaryRank;
        copy.ranks = new ArrayList<>(src.ranks);
        copy.permissions = src.permissions;
        copy.meta = new LinkedHashMap<>(src.meta);
        copy.lastSeen = src.lastSeen;
        copy.online = src.online;
        return copy;
    }

    private static UUID tryParseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private SuggestionProvider<CommandSourceStack> rankSuggestions() {
        return (context, builder) -> {
            this.mod.store().snapshot().ranks.keySet().forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> playerSuggestions() {
        return (context, builder) -> {
            MinecraftServer server = context.getSource().getServer();
            if (server != null) {
                server.getPlayerList().getPlayers().forEach(p -> builder.suggest(p.getGameProfile().getName()));
            }
            this.mod.store().snapshot().users.values().forEach(u -> {
                if (!u.name.isBlank()) builder.suggest(u.name);
                else if (!u.uuid.isBlank()) builder.suggest(u.uuid);
            });
            return builder.buildFuture();
        };
    }

    private static Component styledMessage(String header, String url, String password) {
        MutableComponent msg = Component.empty();
        msg.append(Component.literal("━━━ NextNodes ━━━").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        msg.append(Component.literal("\n"));
        msg.append(Component.literal(header).withStyle(ChatFormatting.GREEN));
        msg.append(Component.literal("\n"));
        msg.append(Component.literal("URL: ").withStyle(ChatFormatting.GRAY));
        msg.append(Component.literal(url).withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Clic para abrir")))));
        if (password != null) {
            msg.append(Component.literal("\n"));
            msg.append(Component.literal("Clave: ").withStyle(ChatFormatting.GRAY));
            msg.append(Component.literal(password).withStyle(Style.EMPTY
                    .withColor(ChatFormatting.YELLOW)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, password))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Clic para copiar")))));
        }
        msg.append(Component.literal("\n"));
        msg.append(Component.literal("━━━━━━━━━━━━━━━━━").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        return msg;
    }
}
