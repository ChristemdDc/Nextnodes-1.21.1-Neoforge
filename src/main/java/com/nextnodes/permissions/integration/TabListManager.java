package com.nextnodes.permissions.integration;

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

    public TabListManager(PermissionResolver resolver) {
        this.resolver = resolver;
    }

    /** Creates/updates the player's team and moves them into it. Call on join and on rank/tag change. */
    public void apply(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        UUID uuid = player.getUUID();
        String desiredName = TabTeamNaming.teamName(
                this.resolver.resolveWeight(uuid),
                player.getGameProfile().getName(),
                player.getStringUUID());

        PlayerTeam previous = scoreboard.getPlayersTeam(player.getScoreboardName());

        PlayerTeam team = scoreboard.getPlayerTeam(desiredName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(desiredName);
        }
        team.setPlayerPrefix(PrefixFormatter.format(this.resolver.resolvePrefix(uuid)));
        team.setPlayerSuffix(buildSuffix(this.resolver.resolveSuffix(uuid), this.resolver.resolveTag(uuid)));

        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);

        if (previous != null
                && previous.getName().startsWith(TabTeamNaming.TEAM_PREFIX)
                && !previous.getName().equals(desiredName)
                && previous.getPlayers().isEmpty()) {
            scoreboard.removePlayerTeam(previous);
        }
    }

    /** Removes the player from their managed team and deletes it if empty. Call on logout. */
    public void remove(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam current = scoreboard.getPlayersTeam(player.getScoreboardName());
        if (current != null && current.getName().startsWith(TabTeamNaming.TEAM_PREFIX)) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), current);
            if (current.getPlayers().isEmpty()) {
                scoreboard.removePlayerTeam(current);
            }
        }
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

    private static Component buildSuffix(String suffix, String tag) {
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
        return result;
    }
}
