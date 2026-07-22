package com.nextnodes.permissions.integration;

import com.mojang.authlib.GameProfile;
import com.nextnodes.permissions.PermissionResolver;
import com.nextnodes.permissions.PrefixFormatter;
import com.nextnodes.permissions.TabTeamNaming;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Manages one scoreboard team per online player so Minecraft (a) orders the TAB list by rank
 * weight and (b) renders prefix/suffix/tag on the floating name above the player's head. The
 * vanilla client sorts the TAB by team name and draws the over-head name from the team, so teams
 * are the only reliable mechanism for both.
 */
public final class TabListManager {
    private final PermissionResolver resolver;
    /** El miembro de scoreboard actual de cada jugador (nombre real, o falso si está disfrazado). */
    private final java.util.Map<UUID, String> memberByPlayer = new java.util.concurrent.ConcurrentHashMap<>();

    public TabListManager(PermissionResolver resolver) {
        this.resolver = resolver;
    }

    /** Creates/updates the player's team and moves them into it. Call on join and on rank/tag change. */
    public void apply(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        assign(server.getScoreboard(), player.getUUID(), player.getScoreboardName(), player.getGameProfile().getName());
    }

    /**
     * Assigns the team from a GameProfile before the player entity exists, so the rank prefix is already
     * attached when Minecraft broadcasts the "joined the game" message (which fires before any player
     * event). A player's scoreboard key is their username (Player.getScoreboardName()).
     */
    public void apply(MinecraftServer server, GameProfile profile) {
        if (server == null || profile == null || profile.getName() == null || profile.getId() == null) {
            return;
        }
        assign(server.getScoreboard(), profile.getId(), profile.getName(), profile.getName());
    }

    private void assign(Scoreboard scoreboard, UUID uuid, String realMember, String name) {
        // Con disfraz, el miembro del equipo es el nombre FALSO (que es como el cliente conoce al jugador
        // tras la reescritura del paquete), para que el prefijo del rango falso y el orden le apliquen.
        String disguise = this.resolver.disguiseNameOf(uuid);
        String member = disguise.isBlank() ? realMember : disguise;
        // Si el miembro cambió (se puso/quitó disfraz), limpiar el miembro anterior de su equipo.
        String prevMember = this.memberByPlayer.get(uuid);
        if (prevMember != null && !prevMember.equals(member)) {
            removeMember(scoreboard, prevMember);
        }
        String desiredName = TabTeamNaming.teamName(this.resolver.resolveWeight(uuid), name, uuid.toString());
        PlayerTeam previous = scoreboard.getPlayersTeam(member);
        PlayerTeam team = scoreboard.getPlayerTeam(desiredName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(desiredName);
        }
        team.setPlayerPrefix(PrefixFormatter.format(this.resolver.resolvePrefix(uuid)));
        team.setPlayerSuffix(buildSuffix(this.resolver.resolveSuffix(uuid), this.resolver.resolveTag(uuid),
                this.resolver.resolveLabel(uuid)));
        scoreboard.addPlayerToTeam(member, team);
        this.memberByPlayer.put(uuid, member);
        if (previous != null
                && previous.getName().startsWith(TabTeamNaming.TEAM_PREFIX)
                && !previous.getName().equals(desiredName)
                && previous.getPlayers().isEmpty()) {
            scoreboard.removePlayerTeam(previous);
        }
    }

    private static void removeMember(Scoreboard scoreboard, String member) {
        PlayerTeam team = scoreboard.getPlayersTeam(member);
        if (team != null && team.getName().startsWith(TabTeamNaming.TEAM_PREFIX)) {
            scoreboard.removePlayerFromTeam(member, team);
            if (team.getPlayers().isEmpty()) {
                scoreboard.removePlayerTeam(team);
            }
        }
    }

    /** Removes the player from their managed team and deletes it if empty. Call on logout. */
    public void remove(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        String member = this.memberByPlayer.getOrDefault(player.getUUID(), player.getScoreboardName());
        removeMember(scoreboard, member);
        this.memberByPlayer.remove(player.getUUID());
    }

    /** Removes every managed team. Call on server stop. */
    public void cleanupAll(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        for (PlayerTeam team : new ArrayList<>(scoreboard.getPlayerTeams())) {
            if (team.getName().startsWith(TabTeamNaming.TEAM_PREFIX)) {
                scoreboard.removePlayerTeam(team);
            }
        }
    }

    private static Component buildSuffix(String suffix, String tag, String label) {
        MutableComponent result = Component.empty();
        Component suffixComponent = PrefixFormatter.format(suffix);
        if (!suffixComponent.getString().isBlank()) {
            result.append(Component.literal(" "));
            result.append(suffixComponent);
        }
        Component tagComponent = PrefixFormatter.format(tag);
        if (!tagComponent.getString().isBlank()) {
            result.append(Component.literal(" "));
            result.append(tagComponent);
        }
        Component labelComponent = PrefixFormatter.format(label);
        if (!labelComponent.getString().isBlank()) {
            result.append(Component.literal(" "));
            result.append(labelComponent);
        }
        return result;
    }
}
