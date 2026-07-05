package com.nextnodes.permissions.ban;

import java.util.Locale;

/**
 * Parses ban durations like "1h", "7d", "perm" into an absolute expiry timestamp.
 *
 * <p>Intentionally a single-unit (+ "perm") parser, distinct from
 * {@code NextNodesCommands.parseDuration} per the spec — do not merge them.
 */
public final class BanDuration {
    private BanDuration() {}

    /**
     * @return the epoch-ms expiry, or {@code null} for a permanent ban ("perm", empty, or null).
     * @throws IllegalArgumentException if the text is non-empty but not a valid duration.
     */
    public static Long expiresAt(String input, long now) {
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("perm") || s.equals("permanent") || s.equals("permanente")) {
            return null;
        }
        if (s.length() < 2) throw new IllegalArgumentException("Duración inválida: " + input);
        char unit = s.charAt(s.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(s.substring(0, s.length() - 1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Duración inválida: " + input);
        }
        if (amount <= 0) throw new IllegalArgumentException("Duración inválida: " + input);
        long unitMs = switch (unit) {
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            case 'w' -> 7L * 86_400_000L;
            default -> throw new IllegalArgumentException("Unidad inválida (usa m/h/d/w): " + input);
        };
        if (amount > (Long.MAX_VALUE - now) / unitMs) {
            throw new IllegalArgumentException("Duración demasiado larga: " + input);
        }
        return now + amount * unitMs;
    }
}
