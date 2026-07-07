package com.nextnodes.plugin;

/** Parses a positive number of days into milliseconds (for the optional expiry arg). */
public final class Days {
    private Days() {}

    private static final long MAX_DAYS = 36500L; // ~100 años, tope de seguridad

    public static long toMillis(String raw) {
        long days;
        try {
            days = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("días inválidos: " + raw);
        }
        if (days <= 0) {
            throw new IllegalArgumentException("los días deben ser > 0: " + raw);
        }
        if (days > MAX_DAYS) {
            throw new IllegalArgumentException("demasiados días (máx " + MAX_DAYS + "): " + raw);
        }
        return days * 86_400_000L;
    }
}
