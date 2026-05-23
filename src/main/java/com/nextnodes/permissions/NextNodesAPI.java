package com.nextnodes.permissions;

import com.nextnodes.permissions.PermissionModels.PermissionData;
import com.nextnodes.permissions.PermissionModels.UserEntry;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Public API for NextNodes Permissions.
 * Other mods can use this class to query rank and permission data
 * without depending on internal implementation classes.
 *
 * Usage:
 *   if (NextNodesAPI.isAvailable()) {
 *       String rank = NextNodesAPI.getPlayerPrimaryRank(player.getUUID());
 *       Set<String> ranks = NextNodesAPI.getRankNames();
 *   }
 *
 * To react to rank/data changes (equivalent to LuckPerms events):
 *   NextNodesAPI.addChangeListener(() -> yourSyncMethod());
 */
public final class NextNodesAPI {

    private static volatile NextNodesPermissions instance;

    private NextNodesAPI() {}

    // --- Internal: called by NextNodesPermissions on init ---

    static void setInstance(NextNodesPermissions mod) {
        instance = mod;
    }

    // --- Availability ---

    /**
     * Returns true if NextNodes Permissions is loaded and its server services are active.
     * Always check this before calling other methods.
     */
    public static boolean isAvailable() {
        return instance != null;
    }

    // --- Rank queries ---

    /**
     * Returns the names of all defined ranks.
     * Equivalent to LuckPerms GroupManager.getLoadedGroups() names.
     */
    public static Set<String> getRankNames() {
        if (!isAvailable()) return Collections.emptySet();
        return Collections.unmodifiableSet(instance.store().snapshot().ranks.keySet());
    }

    /**
     * Returns the name of the default rank.
     */
    public static String getDefaultRank() {
        if (!isAvailable()) return "default";
        return instance.store().snapshot().defaultRank;
    }

    // --- Player queries ---

    /**
     * Returns the primary rank name of the player, or the default rank if none is set.
     * Equivalent to LuckPerms User.getPrimaryGroup().
     */
    public static String getPlayerPrimaryRank(UUID uuid) {
        if (!isAvailable()) return getDefaultRank();
        PermissionData data = instance.store().snapshot();
        UserEntry user = data.users.get(uuid.toString());
        if (user == null || user.primaryRank == null || user.primaryRank.isBlank()) {
            return data.defaultRank;
        }
        return user.primaryRank;
    }

    /**
     * Returns all rank names assigned to the player, or an empty list if none.
     */
    public static List<String> getPlayerRanks(UUID uuid) {
        if (!isAvailable()) return Collections.emptyList();
        UserEntry user = instance.store().snapshot().users.get(uuid.toString());
        if (user == null) return Collections.emptyList();
        return Collections.unmodifiableList(user.ranks);
    }

    /**
     * Returns true if the player has the given rank assigned.
     */
    public static boolean playerHasRank(UUID uuid, String rankName) {
        if (!isAvailable()) return false;
        UserEntry user = instance.store().snapshot().users.get(uuid.toString());
        if (user == null) return false;
        return user.ranks.contains(rankName) || rankName.equals(user.primaryRank);
    }

    // --- Permission and meta queries ---

    /**
     * Resolves a boolean permission node for a player.
     * Returns null if not explicitly set (caller should apply their own default).
     */
    public static Boolean hasPermission(UUID uuid, ServerPlayer player, String node) {
        if (!isAvailable()) return null;
        return instance.resolver().resolveBoolean(uuid, player, node);
    }

    /**
     * Returns a meta value for a player by key, checking the player's own meta
     * and then their rank hierarchy. Returns null if not found.
     * Equivalent to using LuckPerms CachedMetaData.getMetaValue().
     */
    public static String getMeta(UUID uuid, String key) {
        if (!isAvailable()) return null;
        return instance.resolver().resolveMeta(uuid, key);
    }

    /**
     * Returns the display prefix for the player, resolved through their rank hierarchy.
     */
    public static String getPrefix(UUID uuid) {
        if (!isAvailable()) return "";
        return instance.resolver().resolvePrefix(uuid);
    }

    // --- Change listeners ---

    /**
     * Registers a listener that fires whenever any rank or user data changes.
     * Use this to react to rank assignments, rank edits, or user changes.
     * Equivalent to subscribing to LuckPerms GroupDataRecalculateEvent + UserDataRecalculateEvent.
     *
     * @param listener called on the server thread after any data mutation
     */
    public static void addChangeListener(Runnable listener) {
        if (!isAvailable()) return;
        instance.store().addChangeListener(listener);
    }

    /**
     * Registers a listener that fires specifically when a player's online status changes
     * (join/leave). Useful for syncing per-player state on login.
     */
    public static void addPlayerStatusListener(Runnable listener) {
        if (!isAvailable()) return;
        instance.store().addOnlineChangeListener(listener);
    }
}
