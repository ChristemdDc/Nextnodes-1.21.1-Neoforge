package com.nextnodes.permissions.ban;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BanMatcherTest {
    private static final long NOW = 1_000_000_000_000L;

    @Test void blocksByUuid() {
        BanEntry b = BanEntry.account("a", "uuid1", "Steve", null, "x", "c", NOW, null);
        assertSame(b, BanMatcher.findBlocking(List.of(b), "uuid1", "5.5.5.5", NOW));
        assertNull(BanMatcher.findBlocking(List.of(b), "other", "5.5.5.5", NOW));
    }

    @Test void blocksByIpEvenForDifferentAccount() {
        BanEntry b = BanEntry.account("a", "uuid1", "Steve", "1.2.3.4", "x", "c", NOW, null);
        assertSame(b, BanMatcher.findBlocking(List.of(b), "ALT-uuid", "1.2.3.4", NOW));
    }

    @Test void ignoresExpiredOrInactive() {
        BanEntry expired = BanEntry.ip("a", "1.2.3.4", "x", "c", NOW, NOW - 1L);
        BanEntry inactive = BanEntry.ip("b", "1.2.3.4", "x", "c", NOW, null);
        inactive.active = false;
        assertNull(BanMatcher.findBlocking(List.of(expired, inactive), "u", "1.2.3.4", NOW));
    }
}
