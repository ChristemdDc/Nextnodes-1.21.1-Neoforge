package com.nextnodes.permissions.ban;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BanEntryTest {
    private static final long NOW = 1_000_000_000_000L;

    @Test void permanentActiveWhileActiveFlagTrue() {
        BanEntry b = BanEntry.account("id1", "uuid1", "Steve", "1.2.3.4", "spam", "consola", NOW, null);
        assertTrue(b.isActiveAt(NOW));
        assertTrue(b.isActiveAt(NOW + 999_999_999L));
        b.active = false;
        assertFalse(b.isActiveAt(NOW));
    }

    @Test void temporaryExpires() {
        BanEntry b = BanEntry.account("id2", "uuid2", "Alex", null, "cheats", "consola", NOW, NOW + 1000L);
        assertTrue(b.isActiveAt(NOW));
        assertTrue(b.isActiveAt(NOW + 999L));
        assertFalse(b.isActiveAt(NOW + 1000L));
        assertFalse(b.isActiveAt(NOW + 5000L));
    }

    @Test void roundTripsThroughDocument() {
        BanEntry b = BanEntry.ip("id3", "9.9.9.9", "botnet", "web", NOW, NOW + 5000L);
        BanEntry back = BanEntry.fromDocument(b.toDocument());
        assertEquals("id3", back.id);
        assertEquals("ip", back.type);
        assertEquals("9.9.9.9", back.ip);
        assertEquals("botnet", back.reason);
        assertEquals(Long.valueOf(NOW + 5000L), back.expiresAt);
        assertTrue(back.active);
    }
}
