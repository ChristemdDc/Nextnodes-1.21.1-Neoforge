package com.nextnodes.permissions;

import com.nextnodes.permissions.PermissionModels.Rank;
import com.nextnodes.permissions.PermissionModels.UserEntry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DisguiseResolverTest {
    private Map<String, Rank> ranks() {
        Map<String, Rank> m = new LinkedHashMap<>();
        Rank vip = new Rank(); vip.name = "vip"; m.put("vip", vip);
        return m;
    }

    @Test void noDisguiseReturnsNull() {
        UserEntry u = new UserEntry();
        assertNull(DisguiseResolver.displayRank(u, ranks()));
    }

    @Test void blankDisguiseReturnsNull() {
        UserEntry u = new UserEntry(); u.disguiseRank = "   ";
        assertNull(DisguiseResolver.displayRank(u, ranks()));
    }

    @Test void unknownDisguiseRankReturnsNull() {
        UserEntry u = new UserEntry(); u.disguiseRank = "fantasma";
        assertNull(DisguiseResolver.displayRank(u, ranks()));
    }

    @Test void existingDisguiseRankReturnsIt() {
        UserEntry u = new UserEntry(); u.disguiseRank = "vip";
        assertEquals("vip", DisguiseResolver.displayRank(u, ranks()));
    }

    @Test void nullUserReturnsNull() {
        assertNull(DisguiseResolver.displayRank(null, ranks()));
    }
}
