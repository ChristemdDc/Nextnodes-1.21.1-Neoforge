package com.nextnodes.plugin;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DaysTest {
    @Test void parsesDays() {
        assertEquals(86_400_000L, Days.toMillis("1"));
        assertEquals(30L * 86_400_000L, Days.toMillis(" 30 "));
    }

    @Test void rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> Days.toMillis("0"));
        assertThrows(IllegalArgumentException.class, () -> Days.toMillis("-5"));
        assertThrows(IllegalArgumentException.class, () -> Days.toMillis("abc"));
        assertThrows(IllegalArgumentException.class, () -> Days.toMillis("99999999"));
    }
}
