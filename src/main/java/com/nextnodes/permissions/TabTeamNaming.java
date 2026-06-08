package com.nextnodes.permissions;

import java.util.Locale;

/**
 * Pure helper that builds the internal scoreboard-team name used to order the TAB list.
 * Vanilla sorts the TAB by (gamemode, team-name, profile-name); by encoding the rank weight
 * (descending) and then the player name into the team name, players sort by rank and then
 * alphabetically. Free of Minecraft types so it can be unit-tested in isolation.
 */
public final class TabTeamNaming {
    public static final String TEAM_PREFIX = "nn_";

    private TabTeamNaming() {
    }

    public static String teamName(int weight, String playerName, String uuid) {
        long key = (long) Integer.MAX_VALUE - (long) weight; // higher weight -> smaller key -> sorts first
        String weightKey = String.format(Locale.ROOT, "%010d", key);
        return TEAM_PREFIX + weightKey + "_" + sanitizeName(playerName) + "_" + sanitizeUuid(uuid);
    }

    private static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        return lower.length() > 16 ? lower.substring(0, 16) : lower;
    }

    private static String sanitizeUuid(String uuid) {
        if (uuid == null) {
            return "00000000";
        }
        String compact = uuid.replace("-", "");
        return compact.length() > 8 ? compact.substring(0, 8) : compact;
    }
}
