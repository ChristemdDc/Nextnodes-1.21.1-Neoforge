package com.nextnodes.permissions.ban;

/** Loose IP-address shape validation to catch typos before they become dead bans. */
public final class IpFormat {
    private IpFormat() {}

    /** True if {@code s} looks like an IPv4 dotted-quad or an IPv6-ish literal. Rejects blanks,
     *  wrong octet counts, and out-of-range octets so a mistyped IP can't be stored as a ban
     *  that never matches anything. */
    public static boolean looksLikeIp(String s) {
        if (s == null || s.isBlank()) return false;
        if (s.indexOf(':') >= 0) return s.length() >= 2; // IPv6-ish, accept
        String[] p = s.split("\\.");
        if (p.length != 4) return false;
        for (String x : p) {
            try {
                int n = Integer.parseInt(x);
                if (n < 0 || n > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
