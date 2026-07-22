package com.nextnodes.permissions;

import com.nextnodes.permissions.PermissionModels.UserEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DisguiseNameTest {
    private UserEntry disguised() { UserEntry u = new UserEntry(); u.disguiseName = "Herobrine"; return u; }

    @Test void disguisedShownToOthers() {
        assertEquals("Herobrine", DisguiseName.shownName(disguised(), false));
    }

    @Test void selfKeepsRealName() {
        assertNull(DisguiseName.shownName(disguised(), true));
    }

    @Test void noDisguiseReturnsNull() {
        assertNull(DisguiseName.shownName(new UserEntry(), false));
    }

    @Test void blankDisguiseReturnsNull() {
        UserEntry u = new UserEntry(); u.disguiseName = "   ";
        assertNull(DisguiseName.shownName(u, false));
    }

    @Test void nullTargetReturnsNull() {
        assertNull(DisguiseName.shownName(null, false));
    }
}
