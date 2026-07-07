package com.nextnodes.plugin;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OfflineUuidTest {
    @Test void deterministicVersion3() {
        String a = OfflineUuid.of("Camaroncin2");
        String b = OfflineUuid.of("Camaroncin2");
        assertEquals(a, b); // mismo nombre -> mismo UUID
        assertEquals('3', a.charAt(14)); // versión 3 = UUID offline
        assertTrue(a.matches("[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    @Test void differsByUsername() {
        assertNotEquals(OfflineUuid.of("Alice"), OfflineUuid.of("Bob"));
    }

    @Test void blankThrows() {
        assertThrows(IllegalArgumentException.class, () -> OfflineUuid.of(""));
        assertThrows(IllegalArgumentException.class, () -> OfflineUuid.of(null));
    }
}
