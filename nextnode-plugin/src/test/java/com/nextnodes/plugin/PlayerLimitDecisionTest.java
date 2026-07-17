package com.nextnodes.plugin;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerLimitDecisionTest {
    @Test void disabledAlwaysAllows() {
        assertTrue(PlayerLimitDecision.shouldAllow(false, false, 999, 20));
    }

    @Test void bypassAlwaysAllowsEvenWhenFull() {
        assertTrue(PlayerLimitDecision.shouldAllow(true, true, 20, 20));
    }

    @Test void allowsUnderLimit() {
        assertTrue(PlayerLimitDecision.shouldAllow(true, false, 19, 20));
    }

    @Test void deniesAtLimit() {
        assertFalse(PlayerLimitDecision.shouldAllow(true, false, 20, 20));
    }

    @Test void deniesOverLimit() {
        assertFalse(PlayerLimitDecision.shouldAllow(true, false, 25, 20));
    }
}
