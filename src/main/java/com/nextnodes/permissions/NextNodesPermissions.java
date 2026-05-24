package com.nextnodes.permissions;

import com.nextnodes.permissions.commands.NextNodesCommands;
import com.nextnodes.permissions.integration.PermissionHandlerEvents;
import com.nextnodes.permissions.web.WebPanelServer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.neoforged.api.distmarker.Dist;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.server.command.CommandHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Mod(NextNodesPermissions.MOD_ID)
public final class NextNodesPermissions {
    public static final String MOD_ID = "nextnodes_permissions";
    private static final Logger LOGGER = LoggerFactory.getLogger(NextNodesPermissions.class);

    private static final Set<String> HIDDEN_COMMANDS = Set.of();
    private static final String PING_OBJECTIVE = "nn_ping";
    /** Font ResourceLocation for the player-head glyph custom font. */
    private static final ResourceLocation HEADS_FONT = ResourceLocation.fromNamespaceAndPath("nextnodes", "heads");
    /** Fixed UUID that identifies our resource pack (same ID → client replaces existing pack). */
    private static final UUID RESOURCE_PACK_UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final ExecutorService DB_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "nextnodes-db");
        t.setDaemon(true);
        return t;
    });

    private final PermissionStore store;
    private final PermissionResolver resolver;
    private final CommandCatalog commandCatalog;
    private final AuditLog auditLog;
    private final RankHistoryLog rankHistoryLog;
    /** Manages per-player head glyphs and the dynamic resource pack. */
    private final PlayerHeadFont headFont = new PlayerHeadFont();
    /** Tracks each online player's primaryRank to detect changes and notify them. */
    private final Map<UUID, String> prevPrimaryRanks = new ConcurrentHashMap<>();
    private MinecraftServer server;
    private WebPanelServer webPanel;
    private ScheduledExecutorService pingScheduler;
    private com.sun.net.httpserver.HttpServer resourcePackHttpServer;

    public NextNodesPermissions() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            this.store = null;
            this.resolver = null;
            this.commandCatalog = null;
            this.auditLog = null;
            this.rankHistoryLog = null;
            LOGGER.info("NextNodes Permissions is installed on the client; server-only services are disabled.");
            return;
        }

        String mongoUri = System.getProperty("nextnodes.mongodb.uri", "mongodb://localhost:27017");
        String mongoDb  = System.getProperty("nextnodes.mongodb.database", "nextnodes_permissions");
        this.store = new PermissionStore(mongoUri, mongoDb);
        this.resolver = new PermissionResolver(this.store);
        this.commandCatalog = new CommandCatalog();
        this.auditLog = new AuditLog(this.store);
        this.rankHistoryLog = new RankHistoryLog(this.store);
        this.store.addChangeListener(this::refreshOnlinePlayers);
        this.store.addOnlineChangeListener(this::refreshOnlinePlayerNames);
        this.store.addUserSavedListener(this::onUserSaved);
        this.store.addTabSettingsListener(this::tickPingScoreboard);
        this.pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nextnodes-ping");
            t.setDaemon(true);
            return t;
        });
        this.pingScheduler.scheduleAtFixedRate(this::tickPingScoreboard, 3, 2, TimeUnit.SECONDS);
        try {
            this.store.load();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load NextNodes permissions database", ex);
        }

        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(new PermissionHandlerEvents(this));
        NeoForge.EVENT_BUS.register(new NextNodesCommands(this));

        NextNodesAPI.setInstance(this);
    }

    public PermissionStore store() {
        if (this.store == null) {
            throw new IllegalStateException("NextNodes Permissions store is only available on a dedicated server.");
        }
        return this.store;
    }

    public PermissionResolver resolver() {
        if (this.resolver == null) {
            throw new IllegalStateException("NextNodes Permissions resolver is only available on a dedicated server.");
        }
        return this.resolver;
    }

    public CommandCatalog commandCatalog() {
        if (this.commandCatalog == null) {
            throw new IllegalStateException("NextNodes command catalog is only available on a dedicated server.");
        }
        return this.commandCatalog;
    }

    public AuditLog auditLog() {
        return this.auditLog;
    }

    public RankHistoryLog rankHistoryLog() {
        return this.rankHistoryLog;
    }

    public boolean isWebPanelRunning() {
        return this.webPanel != null && this.webPanel.isRunning();
    }

    public String webUrl() {
        return this.webPanel == null || !this.webPanel.isRunning() ? null : this.webPanel.url();
    }

    public String startWebPanel() throws IOException {
        if (this.webPanel == null) {
            throw new IllegalStateException("Web panel not initialized");
        }
        String password = this.webPanel.start();
        LOGGER.info("NextNodes Permissions web panel started at {}", this.webPanel.url());
        return password;
    }

    public void stopWebPanel() {
        if (this.webPanel != null) {
            this.webPanel.stop();
            LOGGER.info("NextNodes Permissions web panel stopped");
        }
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        this.server = event.getServer();
        int port = Integer.getInteger("nextnodes.web.port", 25900);
        this.webPanel = new WebPanelServer(this.store, port, this.commandCatalog::snapshot);
        this.webPanel.setServerIp(event.getServer().getLocalIp());
        this.webPanel.setAuditLog(this.auditLog);
        this.webPanel.setRankHistoryLog(this.rankHistoryLog);
        this.webPanel.setOnSessionExpired(() -> {
            LOGGER.info("NextNodes Permissions web panel session expired");
        });

        // Start lightweight HTTP server just for the resource-pack download
        // (does not require the web panel to be logged in)
        int rpPort = Integer.getInteger("nextnodes.rp.port", 25901);
        try {
            this.resourcePackHttpServer = com.sun.net.httpserver.HttpServer.create(
                    new InetSocketAddress(rpPort), 0);
            this.resourcePackHttpServer.createContext("/resource-pack.zip", exchange -> {
                byte[] pack = this.headFont.packBytes();
                if (pack == null) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, pack.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(pack);
                }
                exchange.close();
            });
            this.resourcePackHttpServer.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "nextnodes-rp");
                t.setDaemon(true);
                return t;
            }));
            this.resourcePackHttpServer.start();
            LOGGER.info("NextNodes resource-pack server listening on port {}", rpPort);
        } catch (IOException e) {
            LOGGER.warn("Could not start resource-pack HTTP server on port {}: {}", rpPort, e.getMessage());
        }
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (this.pingScheduler != null) {
            this.pingScheduler.shutdownNow();
            this.pingScheduler = null;
        }
        // Remove ping objective on shutdown
        if (this.server != null) {
            Scoreboard sb = this.server.getScoreboard();
            Objective obj = sb.getObjective(PING_OBJECTIVE);
            if (obj != null) {
                sb.setDisplayObjective(DisplaySlot.LIST, null);
                sb.removeObjective(obj);
            }
        }
        if (this.resourcePackHttpServer != null) {
            this.resourcePackHttpServer.stop(0);
            this.resourcePackHttpServer = null;
        }
        if (this.webPanel != null) {
            this.webPanel.close();
            this.webPanel = null;
        }
        this.store.close();
        this.server = null;
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        UUID uuid = event.getEntity().getUUID();
        String name = event.getEntity().getGameProfile().getName();
        // Capture the profile reference before going off-thread
        com.mojang.authlib.GameProfile profile = event.getEntity().getGameProfile();
        MinecraftServer currentServer = this.server;
        DB_EXECUTOR.execute(() -> {
            try {
                this.store.touchPlayer(uuid, name, true);
            } catch (IOException ex) {
                LOGGER.error("Unable to update player permission profile", ex);
            }

            // Fetch skin and add to the resource pack if this player is new.
            // On offline-mode/proxy servers the GameProfile may lack textures;
            // we ask Mojang's session server to fill them in.
            boolean packChanged = false;
            if (!this.headFont.hasFace(uuid)) {
                com.mojang.authlib.GameProfile fullProfile = profile;
                if (currentServer != null && !profile.getProperties().containsKey("textures")) {
                    try {
                        com.mojang.authlib.yggdrasil.ProfileResult result =
                                currentServer.getSessionService().fetchProfile(uuid, false);
                        if (result != null) {
                            fullProfile = result.profile();
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Could not fetch Mojang profile for {}: {}", name, e.getMessage());
                    }
                }
                final com.mojang.authlib.GameProfile fetchedProfile = fullProfile;
                // Inject fetched skin into the player's live GameProfile on server thread
                if (fetchedProfile != profile) {
                    if (currentServer != null) {
                        currentServer.execute(() -> {
                            ServerPlayer p = currentServer.getPlayerList().getPlayer(uuid);
                            if (p != null) p.getGameProfile().getProperties().putAll(fetchedProfile.getProperties());
                        });
                    }
                }
                packChanged = this.headFont.updateFaceFromProfile(uuid, fetchedProfile);
            }

            final boolean newFaceAdded = packChanged;
            if (currentServer != null) {
                currentServer.execute(() -> {
                    ServerPlayer player = currentServer.getPlayerList().getPlayer(uuid);
                    if (player != null) {
                        refreshPlayer(player);
                        if (newFaceAdded) {
                            // New face added → resend the pack to ALL connected players
                            sendResourcePackToAll(currentServer);
                        } else {
                            // Returning player → send the current pack just to them
                            sendResourcePackToPlayer(player);
                        }
                    }
                });
            }
        });
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        this.prevPrimaryRanks.remove(uuid);
        DB_EXECUTOR.execute(() -> {
            try {
                this.store.setOnline(uuid, false);
            } catch (IOException ex) {
                LOGGER.error("Unable to update player permission profile", ex);
            }
        });
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        this.commandCatalog.refresh(event.getDispatcher());
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onNameFormat(PlayerEvent.NameFormat event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            event.setDisplayname(PrefixFormatter.prefixedName(this.resolver.resolvePrefix(player.getUUID()), event.getUsername()));
        }
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onTabListName(PlayerEvent.TabListNameFormat event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID uuid = player.getUUID();
            Component prefixComp = PrefixFormatter.format(this.resolver.resolvePrefix(uuid));
            Component nameComp = Component.literal(player.getGameProfile().getName())
                    .withStyle(ChatFormatting.RESET);

            MutableComponent result = Component.empty();
            if (!prefixComp.getString().isBlank()) {
                result.append(prefixComp);
            }
            // Append head glyph between prefix and name if the face has been fetched
            if (this.headFont.hasFace(uuid)) {
                char headChar = this.headFont.getOrAssignChar(uuid);
                result.append(Component.literal(String.valueOf(headChar))
                        .withStyle(s -> s.withFont(HEADS_FONT)));
            }
            result.append(nameComp);
            event.setDisplayName(result);
        }
    }

    /**
     * Creates/removes the "nn_ping" scoreboard objective shown at DisplaySlot.LIST
     * (right side of each tab entry, right before the signal bars) and updates
     * each player's score with their current latency using a colored FixedFormat.
     * Called every 2 s by the scheduler and immediately on tab settings change.
     */
    private void tickPingScoreboard() {
        MinecraftServer srv = this.server;
        if (srv == null) return;
        srv.execute(() -> {
            PermissionModels.TabSettings settings = this.store.getTabSettings();
            Scoreboard sb = srv.getScoreboard();
            Objective obj = sb.getObjective(PING_OBJECTIVE);

            if (!settings.showPing) {
                if (obj != null) {
                    sb.setDisplayObjective(DisplaySlot.LIST, null);
                    sb.removeObjective(obj);
                }
                return;
            }

            if (obj == null) {
                obj = sb.addObjective(
                        PING_OBJECTIVE,
                        ObjectiveCriteria.DUMMY,
                        Component.empty(),
                        ObjectiveCriteria.RenderType.INTEGER,
                        false,
                        null
                );
                sb.setDisplayObjective(DisplaySlot.LIST, obj);
            }

            for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
                if (player.connection == null) continue;
                int latency = player.connection.latency();
                ChatFormatting pingColor = latency < 100 ? ChatFormatting.GREEN
                        : latency < 200 ? ChatFormatting.YELLOW
                        : ChatFormatting.RED;
                Component pingComp = Component.literal(latency + "ms").withStyle(pingColor);
                ScoreAccess score = sb.getOrCreatePlayerScore(player, obj);
                score.set(latency);
                score.numberFormatOverride(new FixedFormat(pingComp));
            }
        });
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        event.getPlayer().refreshDisplayName();
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onCommand(CommandEvent event) {
        ParseResults<CommandSourceStack> originalParse = event.getParseResults();
        CommandSourceStack source = originalParse.getContext().getSource();
        if (!source.isPlayer()) {
            return;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ignored) {
            return;
        }

        String rawCommand = originalParse.getReader().getString();
        ParseResults<CommandSourceStack> elevatedParse = source.getServer().getCommands().getDispatcher().parse(rawCommand, source.withPermission(4));
        CommandContextBuilder<CommandSourceStack> context = elevatedParse.getContext();
        StringBuilder path = new StringBuilder();
        String root = "";
        for (ParsedCommandNode<CommandSourceStack> node : context.getNodes()) {
            if (node.getNode().getName() == null || node.getNode().getName().isBlank()) {
                continue;
            }
            if (!path.isEmpty()) {
                path.append('.');
            }
            String name = node.getNode().getName().toLowerCase(java.util.Locale.ROOT);
            if (root.isBlank()) {
                root = name;
            }
            path.append(name);
        }
        if (root.isBlank()) {
            return;
        }

        Boolean decision = firstDefined(
                this.resolver.resolveBoolean(player.getUUID(), player, "command." + path),
                this.resolver.resolveBoolean(player.getUUID(), player, "command." + root),
                this.resolver.resolveBoolean(player.getUUID(), player, "minecraft.command." + root)
        );
        if (decision != null && !decision) {
            event.setCanceled(true);
            source.sendFailure(Component.literal("No tienes permiso para usar /" + root + "."));
        } else if (decision != null) {
            event.setParseResults(elevatedParse);
        }
    }

    public void reload() throws IOException {
        this.store.load();
        this.resolver.invalidate();
        refreshOnlinePlayers();
    }

    /**
     * Called by PermissionStore after every saveUser / touchPlayer.
     * Notifies the online player if their primaryRank changed.
     */
    private void onUserSaved(PermissionModels.UserEntry user) {
        MinecraftServer currentServer = this.server;
        if (currentServer == null) return;
        currentServer.execute(() -> {
            try {
                UUID uuid = UUID.fromString(user.uuid);
                ServerPlayer player = currentServer.getPlayerList().getPlayer(uuid);
                // prevPrimaryRanks.put returns the OLD value (or null on first registration)
                String prev = this.prevPrimaryRanks.put(uuid, user.primaryRank);
                if (player != null && prev != null && !prev.equals(user.primaryRank)) {
                    PermissionModels.PermissionData snap = this.store.snapshot();
                    PermissionModels.Rank rank = snap.ranks.get(user.primaryRank);
                    String displayName = (rank != null && !rank.displayName.isBlank()) ? rank.displayName : user.primaryRank;
                    player.sendSystemMessage(Component.literal(
                            "§6[NextNodes] §aTu rango ha cambiado a: §f" + displayName));
                }
            } catch (Exception ignored) {}
        });
    }

    private static Boolean firstDefined(Boolean... values) {
        for (Boolean value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void refreshOnlinePlayers() {
        MinecraftServer currentServer = this.server;
        if (currentServer == null) {
            return;
        }
        currentServer.execute(() -> currentServer.getPlayerList().getPlayers().forEach(this::refreshPlayer));
    }

    private void refreshOnlinePlayerNames() {
        MinecraftServer currentServer = this.server;
        if (currentServer == null) {
            return;
        }
        currentServer.execute(() -> currentServer.getPlayerList().getPlayers().forEach(this::refreshPlayerName));
    }

    // ── Resource-pack helpers ────────────────────────────────────────────────

    private void sendResourcePackToPlayer(ServerPlayer player) {
        String url = resourcePackUrl();
        String sha1 = this.headFont.packSha1();
        if (url == null || sha1.isEmpty() || this.headFont.packBytes() == null) return;
        if (player.connection == null) return;
        player.connection.send(new ClientboundResourcePackPushPacket(
                RESOURCE_PACK_UUID, url, sha1, false, Optional.empty()));
    }

    private void sendResourcePackToAll(MinecraftServer srv) {
        String url = resourcePackUrl();
        String sha1 = this.headFont.packSha1();
        if (url == null || sha1.isEmpty() || this.headFont.packBytes() == null) return;
        ClientboundResourcePackPushPacket packet = new ClientboundResourcePackPushPacket(
                RESOURCE_PACK_UUID, url, sha1, false, Optional.empty());
        for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
            if (player.connection != null) player.connection.send(packet);
        }
    }

    private String resourcePackUrl() {
        if (this.resourcePackHttpServer == null) return null;
        String host = System.getProperty("nextnodes.rp.host",
                (this.server != null ? this.server.getLocalIp() : null));
        if (host == null || host.isBlank()) return null;
        int port = Integer.getInteger("nextnodes.rp.port", 25901);
        return "http://" + host + ":" + port + "/resource-pack.zip";
    }

    private void refreshPlayer(ServerPlayer player) {
        refreshPlayerName(player);
        sendFilteredCommands(player);
    }

    private void refreshPlayerName(ServerPlayer player) {
        player.refreshDisplayName();
        player.refreshTabListName();
    }

    private void sendFilteredCommands(ServerPlayer player) {
        MinecraftServer currentServer = player.getServer();
        if (currentServer == null || player.connection == null) {
            return;
        }

        CommandDispatcher<CommandSourceStack> dispatcher = currentServer.getCommands().getDispatcher();
        CommandSourceStack normalSource = player.createCommandSourceStack();
        CommandSourceStack elevatedSource = normalSource.withPermission(4);
        Map<CommandNode<CommandSourceStack>, CommandNode<SharedSuggestionProvider>> nodeMap = new HashMap<>();
        RootCommandNode<SharedSuggestionProvider> root = new RootCommandNode<>();
        RootCommandNode<CommandSourceStack> normalRoot = new RootCommandNode<>();
        RootCommandNode<CommandSourceStack> elevatedRoot = new RootCommandNode<>();

        for (CommandNode<CommandSourceStack> child : dispatcher.getRoot().getChildren()) {
            String name = child.getName().toLowerCase(Locale.ROOT);
            if (HIDDEN_COMMANDS.contains(name)) {
                continue;
            }
            Boolean decision = commandDecision(player, name);
            if (decision != null && !decision) {
                continue;
            }
            if (Boolean.TRUE.equals(decision)) {
                elevatedRoot.addChild(child);
            } else {
                normalRoot.addChild(child);
            }
        }

        mergeCommandNodes(normalRoot, root, nodeMap, normalSource);
        mergeCommandNodes(elevatedRoot, root, nodeMap, elevatedSource);
        player.connection.send(new ClientboundCommandsPacket(root));
    }

    @SuppressWarnings("unchecked")
    private static void mergeCommandNodes(
            CommandNode<CommandSourceStack> sourceNode,
            CommandNode<SharedSuggestionProvider> targetNode,
            Map<CommandNode<CommandSourceStack>, CommandNode<SharedSuggestionProvider>> nodeMap,
            CommandSourceStack permissionSource
    ) {
        CommandHelper.mergeCommandNode(
                sourceNode,
                targetNode,
                nodeMap,
                permissionSource,
                context -> 0,
                suggestion -> SuggestionProviders.safelySwap((SuggestionProvider<SharedSuggestionProvider>) (SuggestionProvider<?>) suggestion)
        );
    }

    private Boolean commandDecision(ServerPlayer player, String path) {
        String root = path;
        int separator = path.indexOf('.');
        if (separator > 0) {
            root = path.substring(0, separator);
        }
        return firstDefined(
                this.resolver.resolveBoolean(player.getUUID(), player, "command." + path),
                path.equals(root) ? null : this.resolver.resolveBoolean(player.getUUID(), player, "command." + root),
                this.resolver.resolveBoolean(player.getUUID(), player, "minecraft.command." + root)
        );
    }

}
