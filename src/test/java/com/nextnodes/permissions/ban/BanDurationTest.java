package com.nextnodes.permissions.ban;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BanDurationTest {
    private static final long NOW = 1_000_000_000_000L;

    @Test void permanentReturnsNull() {
        assertNull(BanDuration.expiresAt("perm", NOW));
        assertNull(BanDuration.expiresAt("", NOW));
        assertNull(BanDuration.expiresAt(null, NOW));
    }

    @Test void parsesUnits() {
        assertEquals(NOW + 3_600_000L, BanDuration.expiresAt("1h", NOW));
        assertEquals(NOW + 86_400_000L, BanDuration.expiresAt("1d", NOW));
        assertEquals(NOW + 7L * 86_400_000L, BanDuration.expiresAt("7d", NOW));
        assertEquals(NOW + 60_000L, BanDuration.expiresAt("1m", NOW));
        assertEquals(NOW + 7L * 86_400_000L, BanDuration.expiresAt("1w", NOW));
    }

    @Test void invalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> BanDuration.expiresAt("abc", NOW));
        assertThrows(IllegalArgumentException.class, () -> BanDuration.expiresAt("5", NOW));
        assertThrows(IllegalArgumentException.class, () -> BanDuration.expiresAt("0d", NOW));
    }

    @Test void rejectsAbsurdlyLongDuration() {
        assertThrows(IllegalArgumentException.class, () -> BanDuration.expiresAt("999999999999999999w", NOW));
    }
}
