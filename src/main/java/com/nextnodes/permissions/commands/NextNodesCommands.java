package com.nextnodes.permissions.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.nextnodes.permissions.AuditLog;
import com.nextnodes.permissions.NextNodesPermissions;
import com.nextnodes.permissions.PermissionModels;
import com.nextnodes.permissions.PermissionModels.PermissionData;
import com.nextnodes.permissions.PermissionModels.PermissionRule;
import com.nextnodes.permissions.PermissionModels.Rank;
import com.nextnodes.permissions.PermissionModels.UserEntry;
import com.nextnodes.permissions.RankHistoryLog;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public final class NextNodesCommands {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final NextNodesPermissions mod;

    public NextNodesCommands(NextNodesPermissions mod) {
        this.mod = mod;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {

        // /nextnodes reload | check
        event.getDispatcher().register(Commands.literal("nextnodes")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("reload")
                        .executes(context -> {
                            try {
                                this.mod.reload();
                                context.getSource().sendSuccess(
                                        () -> Component.literal("NextNodes Permissions recargado desde MongoDB."), true);
                                return 1;
                            } catch (IOException ex) {
                                context.getSource().sendFailure(
                                        Component.literal("No se pudo recargar: " + ex.getMessage()));
                                return 0;
                            }
                        }))
                .then(Commands.literal("check")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("permission", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            ServerPlayer player = EntityArgument.getPlayer(context, "player");
                                            String permission = StringArgumentType.getString(context, "permission");
                                            Boolean result = this.mod.resolver().resolveBoolean(
                                                    player.getUUID(), player, permission);
                                            String text = result == null ? "undefined" : result.toString();
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal(player.getGameProfile().getName()
                                                            + " -> " + permission + " = " + text), false);
                                            return result != null && result ? 1 : 0;
                                        })))));

        // /nn ...
        event.getDispatcher().register(Commands.literal("nn")
                .requires(source -> source.hasPermission(4))

                // --- web ---
                .then(Commands.literal("open")
                        .then(Commands.literal("web")
                                .executes(context -> {
                                    try {
                                        if (this.mod.isWebPanelRunning()) {
                                            context.getSource().sendSuccess(
                                                    () -> styledMessage("El panel web ya esta activo.", this.mod.webUrl(), null), false);
                                            return 1;
                                        }
                                        String password = this.mod.startWebPanel();
                                        String url = this.mod.webUrl();
                                        context.getSource().sendSuccess(
                                                () -> styledMessage("Panel web iniciado (15 minutos).", url, password), false);
                                        return 1;
                                    } catch (IOException ex) {
                                        context.getSource().sendFailure(
                                                Component.literal("No se pudo iniciar el panel: " + ex.getMessage()));
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
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Panel web detenido.").withStyle(ChatFormatting.RED), false);
                                    return 1;
                                })))
                .then(Commands.literal("stop")
                        .then(Commands.literal("web")
                                .executes(context -> {
                                    if (!this.mod.isWebPanelRunning()) {
                                        context.getSource().sendFailure(Component.literal("El panel web no esta activo."));
                                        return 0;
                                    }
                                    this.mod.stopWebPanel();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Panel web detenido.").withStyle(ChatFormatting.RED), false);
                                    return 1;
                                })))

                // --- rank ---
                .then(Commands.literal("rank")
                        .then(Commands.literal("list")
                                .executes(context -> {
                                    PermissionData data = this.mod.store().snapshot();
                                    if (data.ranks.isEmpty()) {
                                        context.getSource().sendSuccess(
                                                () -> Component.literal("No hay rangos definidos.").withStyle(ChatFormatting.GRAY), false);
                                        return 0;
                                    }
                                    MutableComponent msg = Component.literal("Rangos (" + data.ranks.size() + ")")
                                            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
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
                                                .suggests(userRankSuggestions())
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
                                                }))))
                        // /nn rank commands <rankName> allow-all | block-all | reset-all
                        .then(Commands.literal("commands")
                                .then(Commands.argument("rango", StringArgumentType.word())
                                        .suggests(rankSuggestions())
                                        .then(Commands.literal("allow-all")
                                                .executes(context -> cmdRankCommands(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "rango"), true, false)))
                                        .then(Commands.literal("block-all")
                                                .executes(context -> cmdRankCommands(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "rango"), false, false)))
                                        .then(Commands.literal("reset-all")
                                                .executes(context -> cmdRankCommands(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "rango"), true, true)))))
                        // /nn rank weight <rango> <peso>
                        .then(Commands.literal("weight")
                                .then(Commands.argument("rango", StringArgumentType.word()).suggests(rankSuggestions())
                                        .then(Commands.argument("peso", IntegerArgumentType.integer())
                                                .executes(context -> cmdRankWeight(context.getSource(),
                                                        StringArgumentType.getString(context, "rango"),
                                                        IntegerArgumentType.getInteger(context, "peso"))))))
                        // /nn rank prefix <rango> [texto...]   (sin texto = limpiar)
                        .then(Commands.literal("prefix")
                                .then(Commands.argument("rango", StringArgumentType.word()).suggests(rankSuggestions())
                                        .executes(context -> cmdRankDisplay(context.getSource(),
                                                StringArgumentType.getString(context, "rango"), true, ""))
                                        .then(Commands.argument("texto", StringArgumentType.greedyString())
                                                .executes(context -> cmdRankDisplay(context.getSource(),
                                                        StringArgumentType.getString(context, "rango"), true,
                                                        StringArgumentType.getString(context, "texto"))))))
                        // /nn rank suffix <rango> [texto...]   (sin texto = limpiar)
                        .then(Commands.literal("suffix")
                                .then(Commands.argument("rango", StringArgumentType.word()).suggests(rankSuggestions())
                                        .executes(context -> cmdRankDisplay(context.getSource(),
                                                StringArgumentType.getString(context, "rango"), false, ""))
                                        .then(Commands.argument("texto", StringArgumentType.greedyString())
                                                .executes(context -> cmdRankDisplay(context.getSource(),
                                                        StringArgumentType.getString(context, "rango"), false,
                                                        StringArgumentType.getString(context, "texto")))))))

                // --- tag (por jugador) ---
                .then(Commands.literal("tag")
                        .then(Commands.argument("jugador", StringArgumentType.word()).suggests(playerSuggestions())
                                .executes(context -> cmdTag(context.getSource(),
                                        StringArgumentType.getString(context, "jugador"), ""))
                                .then(Commands.argument("texto", StringArgumentType.greedyString())
                                        .executes(context -> cmdTag(context.getSource(),
                                                StringArgumentType.getString(context, "jugador"),
                                                StringArgumentType.getString(context, "texto"))))))

                // --- perm ---
                .then(Commands.literal("perm")
                        .then(Commands.literal("add")
                                .then(Commands.argument("jugador", StringArgumentType.word())
                                        .suggests(playerSuggestions())
                                        .then(Commands.argument("nodo", StringArgumentType.word())
                                                .then(Commands.argument("valor", StringArgumentType.word())
                                                        .suggests((ctx, b) -> {
                                                            b.suggest("allow"); b.suggest("deny");
                                                            return b.buildFuture();
                                                        })
                                                        // permanent
                                                        .executes(context -> cmdPermAdd(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "jugador"),
                                                                StringArgumentType.getString(context, "nodo"),
                                                                StringArgumentType.getString(context, "valor"),
                                                                null))
                                                        // temporary: /nn perm add <player> <node> allow 1h
                                                        .then(Commands.argument("duracion", StringArgumentType.word())
                                                                .executes(context -> cmdPermAdd(
                                                                        context.getSource(),
                                                                        StringArgumentType.getString(context, "jugador"),
                                                                        StringArgumentType.getString(context, "nodo"),
                                                                        StringArgumentType.getString(context, "valor"),
                                                                        StringArgumentType.getString(context, "duracion"))))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("jugador", StringArgumentType.word())
                                        .suggests(playerSuggestions())
                                        .then(Commands.argument("nodo", StringArgumentType.word())
                                                .executes(context -> cmdPermRemove(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "jugador"),
                                                        StringArgumentType.getString(context, "nodo")))))))

                // --- export / import ---
                .then(Commands.literal("export")
                        .executes(context -> cmdExport(context.getSource())))
                .then(Commands.literal("import")
                        .executes(context -> cmdImport(context.getSource())))

                // --- baneos ---
                .then(Commands.literal("ban")
                        .then(Commands.argument("jugador", StringArgumentType.word()).suggests(playerSuggestions())
                                .executes(ctx -> cmdBan(ctx.getSource(), StringArgumentType.getString(ctx, "jugador"), "perm", ""))
                                .then(Commands.argument("duracion", StringArgumentType.word())
                                        .executes(ctx -> cmdBan(ctx.getSource(), StringArgumentType.getString(ctx, "jugador"),
                                                StringArgumentType.getString(ctx, "duracion"), ""))
                                        .then(Commands.argument("razon", StringArgumentType.greedyString())
                                                .executes(ctx -> cmdBan(ctx.getSource(), StringArgumentType.getString(ctx, "jugador"),
                                                        StringArgumentType.getString(ctx, "duracion"),
                                                        StringArgumentType.getString(ctx, "razon")))))))
                .then(Commands.literal("ban-ip")
                        .then(Commands.argument("ip", StringArgumentType.word())
                                .executes(ctx -> cmdBanIp(ctx.getSource(), StringArgumentType.getString(ctx, "ip"), "perm", ""))
                                .then(Commands.argument("duracion", StringArgumentType.word())
                                        .executes(ctx -> cmdBanIp(ctx.getSource(), StringArgumentType.getString(ctx, "ip"),
                                                StringArgumentType.getString(ctx, "duracion"), ""))
                                        .then(Commands.argument("razon", StringArgumentType.greedyString())
                                                .executes(ctx -> cmdBanIp(ctx.getSource(), StringArgumentType.getString(ctx, "ip"),
                                                        StringArgumentType.getString(ctx, "duracion"),
                                                        StringArgumentType.getString(ctx, "razon")))))))
                .then(Commands.literal("unban")
                        .then(Commands.argument("objetivo", StringArgumentType.word())
                                .executes(ctx -> cmdUnban(ctx.getSource(), StringArgumentType.getString(ctx, "objetivo")))))
                .then(Commands.literal("bans")
                        .executes(ctx -> cmdBans(ctx.getSource())))
                .then(Commands.literal("alts")
                        .then(Commands.argument("objetivo", StringArgumentType.word())
                                .executes(ctx -> cmdAlts(ctx.getSource(), StringArgumentType.getString(ctx, "objetivo"))))));
    }

    // -------------------------------------------------------------------------
    // Rank sub-commands
    // -------------------------------------------------------------------------

    private int cmdRankAdd(CommandSourceStack source, String input, String rankName) {
        PermissionData data = this.mod.store().snapshot();
        String normalized = PermissionModels.normalizeName(rankName);
        if (!data.ranks.containsKey(normalized)) {
            source.sendFailure(Component.literal("El rango '" + normalized + "' no existe. Usa /nn rank list."));
            return 0;
        }
        UserEntry user = findUser(input, source.getServer());
        if (user == null) {
            source.sendFailure(Component.literal("Jugador no encontrado: " + input));
            return 0;
        }
        if (user.ranks.contains(normalized)) {
            String display = displayName(user);
            source.sendFailure(Component.literal(display + " ya tiene el rango '" + normalized + "'."));
            return 0;
        }
        user.ranks.add(normalized);
        if (user.primaryRank.isBlank()) user.primaryRank = normalized;
        try {
            this.mod.store().saveUser(user);
            String display = displayName(user);
            source.sendSuccess(() -> Component.literal("Rango '" + normalized + "' añadido a " + display + ".").withStyle(ChatFormatting.GREEN), true);
            logAudit(source, "user.rank.add", "user", user.uuid, "rank=" + normalized);
            this.mod.rankHistoryLog().record(user.uuid, user.name, "add", normalized, actorId(source), actorName(source));
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
            source.sendFailure(Component.literal(displayName(user) + " no tiene el rango '" + normalized + "'."));
            return 0;
        }
        user.ranks.remove(normalized);
        if (normalized.equals(user.primaryRank)) {
            String fallback = this.mod.store().snapshot().defaultRank;
            user.primaryRank = user.ranks.isEmpty() ? fallback : user.ranks.get(0);
        }
        try {
            this.mod.store().saveUser(user);
            String display = displayName(user);
            source.sendSuccess(() -> Component.literal("Rango '" + normalized + "' eliminado de " + display + ".").withStyle(ChatFormatting.RED), true);
            logAudit(source, "user.rank.remove", "user", user.uuid, "rank=" + normalized);
            this.mod.rankHistoryLog().record(user.uuid, user.name, "remove", normalized, actorId(source), actorName(source));
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
            source.sendFailure(Component.literal("El rango '" + normalized + "' no existe. Usa /nn rank list."));
            return 0;
        }
        UserEntry user = findUser(input, source.getServer());
        if (user == null) {
            source.sendFailure(Component.literal("Jugador no encontrado: " + input));
            return 0;
        }
        user.primaryRank = normalized;
        if (!user.ranks.contains(normalized)) user.ranks.add(normalized);
        try {
            this.mod.store().saveUser(user);
            String display = displayName(user);
            source.sendSuccess(() -> Component.literal("Rango principal de " + display + " establecido a '" + normalized + "'.").withStyle(ChatFormatting.GREEN), true);
            logAudit(source, "user.rank.set-primary", "user", user.uuid, "rank=" + normalized);
            this.mod.rankHistoryLog().record(user.uuid, user.name, "set-primary", normalized, actorId(source), actorName(source));
            return 1;
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Error al guardar: " + ex.getMessage()));
            return 0;
        }
    }

    /**
     * @param allowAll  true=allow wildcard, false=deny wildcard (ignored when reset=true)
     * @param reset     true=remove all command.* rules instead of adding
     */
    private int cmdRankCommands(CommandSourceStack source, String rankName, boolean allowAll, boolean reset) {
        String normalized = PermissionModels.normalizeName(rankName);
        PermissionData data = this.mod.store().snapshot();
        if (!data.ranks.containsKey(normalized)) {
            source.sendFailure(Component.literal("El rango '" + normalized + "' no existe."));
            return 0;
        }
        Rank rank = data.ranks.get(normalized);
        // Remove existing command.* wildcard rules
        rank.permissions.removeIf(r -> r.node.equals("command.*") || r.node.equals("*"));
        if (!reset) {
            PermissionRule rule = new PermissionRule();
            rule.node  = "command.*";
            rule.value = allowAll;
            rule.mode  = "wildcard";
            rank.permissions.add(0, rule); // prepend so it takes priority
        }
        try {
            this.mod.store().saveRank(rank);
            String action = reset ? "Permisos de comandos reseteados" : (allowAll ? "Todos los comandos PERMITIDOS" : "Todos los comandos BLOQUEADOS");
            source.sendSuccess(() -> Component.literal("[" + normalized + "] " + action).withStyle(reset ? ChatFormatting.YELLOW : allowAll ? ChatFormatting.GREEN : ChatFormatting.RED), true);
            logAudit(source, "rank.commands." + (reset ? "reset" : allowAll ? "allow-all" : "block-all"), "rank", normalized, "");
            return 1;
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Error al guardar el rango: " + ex.getMessage()));
            return 0;
        }
    }

    private int cmdRankWeight(CommandSourceStack source, String rankName, int weight) {
        String normalized = PermissionModels.normalizeName(rankName);
        Rank rank = this.mod.store().snapshot().ranks.get(normalized);
        if (rank == null) {
            source.sendFailure(Component.literal("El rango '" + normalized + "' no existe. Usa /nn rank list."));
            return 0;
        }
        rank.weight = weight;
        try {
            this.mod.store().saveRank(rank);
            source.sendSuccess(() -> Component.literal("Peso de '" + normalized + "' establecido a " + weight + ".").withStyle(ChatFormatting.GREEN), true);
            logAudit(source, "rank.weight", "rank", normalized, "weight=" + weight);
            return 1;
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Error al guardar: " + ex.getMessage()));
            return 0;
        }
    }

    private int cmdRankDisplay(CommandSourceStack source, String rankName, boolean isPrefix, String text) {
        String normalized = PermissionModels.normalizeName(rankName);
        Rank rank = this.mod.store().snapshot().ranks.get(normalized);
        if (rank == null) {
            source.sendFailure(Component.literal("El rango '" + normalized + "' no existe. Usa /nn rank list."));
            return 0;
        }
        String value = text;
        if (isPrefix && !value.isEmpty() && !value.endsWith(" ")) {
            value = value + " "; // prefixes need a trailing space before the name
        }
        if (isPrefix) {
            rank.prefix = value;
        } else {
            rank.suffix = value;
        }
        final String label = isPrefix ? "Prefijo" : "Sufijo";
        final String shown = value.isBlank() ? "(vacío)" : value;
        try {
            this.mod.store().saveRank(rank);
            source.sendSuccess(() -> Component.literal(label + " de '" + normalized + "' establecido a: " + shown).withStyle(ChatFormatting.GREEN), true);
            logAudit(source, "rank." + (isPrefix ? "prefix" : "suffix"), "rank", normalized, "value=" + value);
            return 1;
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Error al guardar: " + ex.getMessage()));
            return 0;
        }
    }

    private int cmdTag(CommandSourceStack source, String input, String tag) {
        UserEntry user = findUser(input, source.getServer());
        if (user == null) {
            source.sendFailure(Component.literal("Jugador no encontrado: " + input));
            return 0;
        }
        user.tag = tag;
        final String display = displayName(user);
        final String shown = tag.isBlank() ? "(vacío)" : tag;
        try {
            this.mod.store().saveUser(user);
            source.sendSuccess(() -> Component.literal("Tag de " + display + " establecido a: " + shown).withStyle(ChatFormatting.GREEN), true);
            logAudit(source, "user.tag", "user", user.uuid, "tag=" + tag);
            return 1;
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Error al guardar: " + ex.getMessage()));
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Permission sub-commands
    // -------------------------------------------------------------------------

    private int cmdPermAdd(CommandSourceStack source, String input, String node, String valor, String duracion) {
        boolean allow = !valor.equalsIgnoreCase("deny");
        Long expiresAt = null;
        if (duracion != null && !duracion.isBlank()) {
            long ms = parseDuration(duracion);
            if (ms <= 0) {
                source.sendFailure(Component.literal("Duración inválida: '" + duracion + "'. Ejemplos: 30m 2h 1d"));
                return 0;
            }
            expiresAt = System.currentTimeMillis() + ms;
        }
        UserEntry user = findUser(input, source.getServer());
        if (user == null) {
            source.sendFailure(Component.literal("Jugador no encontrado: " + input));
            return 0;
        }
        PermissionRule rule = new PermissionRule();
        rule.node      = node;
        rule.value     = allow;
        rule.mode      = PermissionModels.inferMode(node);
        rule.expiresAt = expiresAt;
        user.permissions.add(rule);
        try {
            this.mod.store().saveUser(user);
            String display = displayName(user);
            String expiry = expiresAt == null ? "permanente" : "expira en " + duracion;
            source.sendSuccess(() -> Component.literal(
                    "[" + display + "] " + node + " = " + (allow ? "§aSI" : "§cNO") + " §r(" + expiry + ")").withStyle(ChatFormatting.WHITE), true);
            logAudit(source, "user.perm.add", "user", user.uuid, "node=" + node + " value=" + allow + (expiresAt != null ? " duration=" + duracion : ""));
            return 1;
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Error al guardar: " + ex.getMessage()));
            return 0;
        }
    }

    private int cmdPermRemove(CommandSourceStack source, String input, String node) {
        UserEntry user = findUser(input, source.getServer());
        if (user == null) {
            source.sendFailure(Component.literal("Jugador no encontrado: " + input));
            return 0;
        }
        int before = user.permissions.size();
        user.permissions.removeIf(r -> r.node.equalsIgnoreCase(node));
        int removed = before - user.permissions.size();
        if (removed == 0) {
            source.sendFailure(Component.literal(displayName(user) + " no tiene ninguna regla para '" + node + "'."));
            return 0;
        }
        try {
            this.mod.store().saveUser(user);
            source.sendSuccess(() -> Component.literal(removed + " regla(s) de '" + node + "' eliminadas de " + displayName(user) + ".").withStyle(ChatFormatting.YELLOW), true);
            logAudit(source, "user.perm.remove", "user", user.uuid, "node=" + node + " count=" + removed);
            return removed;
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Error al guardar: " + ex.getMessage()));
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Export / Import
    // -------------------------------------------------------------------------

    private int cmdExport(CommandSourceStack source) {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("nextnodes-backups");
            Files.createDirectories(dir);
            String stamp = STAMP.format(LocalDateTime.now());
            Path file = dir.resolve("nextnodes-export-" + stamp + ".json");
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(this.mod.store().snapshotJson());
            }
            String filePath = file.toAbsolutePath().toString();
            source.sendSuccess(() -> Component.literal("Exportado a: " + filePath).withStyle(ChatFormatting.GREEN), true);
            logAudit(source, "export", "", "", "file=" + filePath);
            return 1;
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Error al exportar: " + ex.getMessage()));
            return 0;
        }
    }

    private int cmdImport(CommandSourceStack source) {
        Path file = FMLPaths.CONFIGDIR.get().resolve("nextnodes-import.json");
        if (!Files.exists(file)) {
            source.sendFailure(Component.literal("Archivo no encontrado: " + file.toAbsolutePath() + ". Coloca el JSON ahí antes de importar."));
            return 0;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            PermissionData pd = new GsonBuilder().create().fromJson(json, PermissionData.class);
            if (pd == null) {
                source.sendFailure(Component.literal("El archivo no contiene un JSON válido."));
                return 0;
            }
            this.mod.store().importAll(pd);
            this.mod.reload();
            source.sendSuccess(() -> Component.literal("Importación completada. " + pd.ranks.size() + " rangos, " + pd.users.size() + " usuarios.").withStyle(ChatFormatting.GREEN), true);
            logAudit(source, "import", "", "", "ranks=" + pd.ranks.size() + " users=" + pd.users.size());
            return 1;
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Error al importar: " + ex.getMessage()));
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Baneos
    // -------------------------------------------------------------------------

    private int cmdBan(CommandSourceStack source, String player, String duration, String reason) {
        var bans = this.mod.banStore();
        if (bans == null) { source.sendFailure(Component.literal("Baneos no disponibles.")); return 0; }
        long now = System.currentTimeMillis();
        Long expiresAt;
        try { expiresAt = com.nextnodes.permissions.ban.BanDuration.expiresAt(duration, now); }
        catch (IllegalArgumentException ex) { source.sendFailure(Component.literal(ex.getMessage())); return 0; }
        // Resolver UUID por nombre entre los conocidos (jugadores online o el store de perfiles).
        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(player);
        String uuid = online != null ? online.getUUID().toString() : null;
        String issuer = source.getTextName();
        var ban = bans.banAccount(uuid, player, reason.isBlank() ? "Sin razón" : reason, issuer, expiresAt, now);
        if (online != null) online.connection.disconnect(NextNodesPermissions.banScreen(ban));
        final String ipMsg;
        if (ban.ip != null) ipMsg = " (+ IP " + ban.ip + ")";
        else if (uuid == null) ipMsg = " (sin IP conocida — no bloqueará hasta que tenga una IP registrada)";
        else ipMsg = " (sin IP conocida)";
        source.sendSuccess(() -> Component.literal("Baneado " + player + ipMsg + ".").withStyle(ChatFormatting.RED), true);
        return 1;
    }

    private int cmdBanIp(CommandSourceStack source, String ip, String duration, String reason) {
        var bans = this.mod.banStore();
        if (bans == null) { source.sendFailure(Component.literal("Baneos no disponibles.")); return 0; }
        if (!looksLikeIp(ip)) { source.sendFailure(Component.literal("IP inválida: " + ip)); return 0; }
        long now = System.currentTimeMillis();
        Long expiresAt;
        try { expiresAt = com.nextnodes.permissions.ban.BanDuration.expiresAt(duration, now); }
        catch (IllegalArgumentException ex) { source.sendFailure(Component.literal(ex.getMessage())); return 0; }
        bans.banIp(ip, reason.isBlank() ? "Sin razón" : reason, source.getTextName(), expiresAt, now);
        this.mod.enforceOnAllOnline();
        source.sendSuccess(() -> Component.literal("IP baneada: " + ip).withStyle(ChatFormatting.RED), true);
        return 1;
    }

    private int cmdUnban(CommandSourceStack source, String target) {
        var bans = this.mod.banStore();
        if (bans == null) { source.sendFailure(Component.literal("Baneos no disponibles.")); return 0; }
        int n = bans.unban(target, source.getTextName(), System.currentTimeMillis());
        if (n == 0) { source.sendFailure(Component.literal("No había baneos activos para: " + target)); return 0; }
        source.sendSuccess(() -> Component.literal("Desbaneado: " + target + " (" + n + ")").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private int cmdBans(CommandSourceStack source) {
        var bans = this.mod.banStore();
        if (bans == null) { source.sendFailure(Component.literal("Baneos no disponibles.")); return 0; }
        var active = bans.listActive(System.currentTimeMillis());
        if (active.isEmpty()) { source.sendSuccess(() -> Component.literal("No hay baneos activos."), false); return 1; }
        MutableComponent msg = Component.literal("Baneos activos: " + active.size()).withStyle(ChatFormatting.AQUA);
        for (var b : active) {
            String who = "ip".equals(b.type) ? "IP " + b.ip : b.targetName + (b.ip != null ? " (" + b.ip + ")" : "");
            String exp = b.expiresAt == null ? "permanente" : "hasta " + formatTs(b.expiresAt);
            msg.append(Component.literal("\n• " + who + " — " + (b.reason == null ? "" : b.reason) + " [" + exp + "]"));
        }
        final MutableComponent out = msg;
        source.sendSuccess(() -> out, false);
        return 1;
    }

    private int cmdAlts(CommandSourceStack source, String target) {
        var bans = this.mod.banStore();
        if (bans == null) { source.sendFailure(Component.literal("Baneos no disponibles.")); return 0; }
        String ip = bans.lastIpOf(target);
        if (ip == null) { source.sendFailure(Component.literal("Sin IP conocida para: " + target)); return 0; }
        var accounts = bans.accountsForIp(ip);
        MutableComponent msg = Component.literal("Cuentas desde " + ip + ": " + accounts.size()).withStyle(ChatFormatting.AQUA);
        for (var a : accounts) msg.append(Component.literal("\n• " + a.name));
        final MutableComponent out = msg;
        source.sendSuccess(() -> out, false);
        return 1;
    }

    private static String formatTs(long epochMs) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault()));
    }

    private static boolean looksLikeIp(String s) {
        if (s == null || s.isBlank()) return false;
        if (s.indexOf(':') >= 0) return s.length() >= 2; // IPv6-ish, accept
        String[] p = s.split("\\.");
        if (p.length != 4) return false;
        for (String x : p) {
            try { int n = Integer.parseInt(x); if (n < 0 || n > 255) return false; }
            catch (NumberFormatException e) { return false; }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
        copy.uuid        = src.uuid;
        copy.name        = src.name;
        copy.primaryRank = src.primaryRank;
        copy.ranks       = new ArrayList<>(src.ranks);
        copy.permissions = new ArrayList<>(src.permissions);
        copy.meta        = new LinkedHashMap<>(src.meta);
        copy.lastSeen    = src.lastSeen;
        copy.online      = src.online;
        return copy;
    }

    private static String displayName(UserEntry u) {
        return u.name.isBlank() ? u.uuid : u.name;
    }

    private static UUID tryParseUuid(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) { return null; }
    }

    /**
     * Parses duration strings like "30m", "2h", "1d", "1h30m".
     * @return milliseconds, or -1 if invalid
     */
    static long parseDuration(String s) {
        if (s == null || s.isBlank()) return -1;
        s = s.trim().toLowerCase();
        long total = 0;
        int i = 0;
        while (i < s.length()) {
            int start = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i == start || i >= s.length()) return -1;
            long num;
            try { num = Long.parseLong(s.substring(start, i)); } catch (NumberFormatException ex) { return -1; }
            long multiplier = switch (s.charAt(i++)) {
                case 's' -> 1_000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 604_800_000L;
                default  -> -1L;
            };
            if (multiplier < 0) return -1;
            total += num * multiplier;
        }
        return total > 0 ? total : -1;
    }

    private void logAudit(CommandSourceStack source, String action, String targetType, String targetId, String details) {
        this.mod.auditLog().log(actorId(source), actorName(source), action, targetType, targetId, details);
    }

    private static String actorId(CommandSourceStack source) {
        try {
            ServerPlayer p = source.getPlayerOrException();
            return p.getUUID().toString();
        } catch (Exception ex) {
            return "console";
        }
    }

    private static String actorName(CommandSourceStack source) {
        return source.getTextName();
    }

    private SuggestionProvider<CommandSourceStack> rankSuggestions() {
        return (context, builder) ->
                SharedSuggestionProvider.suggest(this.mod.store().snapshot().ranks.keySet(), builder);
    }

    private SuggestionProvider<CommandSourceStack> playerSuggestions() {
        return (context, builder) -> {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            MinecraftServer server = context.getSource().getServer();
            if (server != null) {
                server.getPlayerList().getPlayers().forEach(p -> names.add(p.getGameProfile().getName()));
            }
            this.mod.store().snapshot().users.values().forEach(u -> {
                if (!u.name.isBlank()) {
                    names.add(u.name);
                } else if (!u.uuid.isBlank()) {
                    names.add(u.uuid);
                }
            });
            return SharedSuggestionProvider.suggest(names, builder);
        };
    }

    /**
     * Suggests only the ranks the targeted player currently has (read from the already-typed
     * "jugador" argument), so removing a rank is just a tab-complete. Falls back to all ranks
     * when the player can't be resolved yet.
     */
    private SuggestionProvider<CommandSourceStack> userRankSuggestions() {
        return (context, builder) -> {
            try {
                String input = StringArgumentType.getString(context, "jugador");
                UserEntry user = findUser(input, context.getSource().getServer());
                if (user != null && !user.ranks.isEmpty()) {
                    return SharedSuggestionProvider.suggest(user.ranks, builder);
                }
            } catch (IllegalArgumentException ignored) {
                // "jugador" not parsed yet; fall through to all ranks.
            }
            return SharedSuggestionProvider.suggest(this.mod.store().snapshot().ranks.keySet(), builder);
        };
    }

    private static Component styledMessage(String header, String url, String password) {
        MutableComponent msg = Component.empty();
        msg.append(Component.literal("━━━ NextNodes ━━━").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        msg.append(Component.literal("\n"));
        msg.append(Component.literal(header).withStyle(ChatFormatting.GREEN));
        msg.append(Component.literal("\n"));
        String clickUrl = (password != null && !password.isBlank()) ? url + "?key=" + password : url;
        msg.append(Component.literal("URL: ").withStyle(ChatFormatting.GRAY));
        msg.append(Component.literal(url).withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, clickUrl))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Clic para abrir (auto-login)")))));
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