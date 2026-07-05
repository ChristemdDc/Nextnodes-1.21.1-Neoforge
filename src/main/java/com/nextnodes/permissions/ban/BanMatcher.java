package com.nextnodes.permissions.ban;

import java.util.Collection;

/** Decides whether a connecting (uuid, ip) is blocked by any of the given bans. */
public final class BanMatcher {
    private BanMatcher() {}

    /** @return the first active ban that blocks this connection, or null if none. */
    public static BanEntry findBlocking(Collection<BanEntry> bans, String uuid, String ip, long now) {
        if (bans == null) return null;
        for (BanEntry b : bans) {
            if (!b.isActiveAt(now)) continue;
            if (uuid != null && uuid.equals(b.targetUuid)) return b;
            if (ip != null && !ip.isBlank() && ip.equals(b.ip)) return b;
        }
        return null;
    }
}
