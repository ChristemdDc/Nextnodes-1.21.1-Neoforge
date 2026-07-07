package com.nextnodes.plugin;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RankNamesTest {
    @Test void trimsAndLowercases() {
        assertEquals("vip", RankNames.normalize("  VIP "));
        assertEquals("diamond", RankNames.normalize("Diamond"));
    }
    @Test void nullBecomesEmpty() {
        assertEquals("", RankNames.normalize(null));
    }
}
