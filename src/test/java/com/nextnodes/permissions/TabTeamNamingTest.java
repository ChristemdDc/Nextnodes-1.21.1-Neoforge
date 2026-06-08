package com.nextnodes.permissions;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TabTeamNamingTest {
    @Test
    void higherWeightSortsFirst() {
        String admin = TabTeamNaming.teamName(100, "alice", "11111111-1111-1111-1111-111111111111");
        String member = TabTeamNaming.teamName(0, "alice", "11111111-1111-1111-1111-111111111111");
        assertTrue(admin.compareTo(member) < 0, "higher weight must sort before lower weight");
    }

    @Test
    void sameWeightSortsAlphabeticallyByName() {
        String alice = TabTeamNaming.teamName(50, "alice", "aaaaaaaa-0000-0000-0000-000000000000");
        String bob = TabTeamNaming.teamName(50, "bob", "bbbbbbbb-0000-0000-0000-000000000000");
        assertTrue(alice.compareTo(bob) < 0, "same weight must sort alphabetically by name");
    }

    @Test
    void zeroWeightSortsBeforeNegativeWeight() {
        String zero = TabTeamNaming.teamName(0, "alice", "11111111-1111-1111-1111-111111111111");
        String negative = TabTeamNaming.teamName(-100, "alice", "11111111-1111-1111-1111-111111111111");
        assertTrue(zero.compareTo(negative) < 0, "weight 0 must sort before negative weight");
    }

    @Test
    void differentPlayersGetUniqueTeamNames() {
        String a = TabTeamNaming.teamName(50, "alice", "aaaaaaaa-0000-0000-0000-000000000000");
        String b = TabTeamNaming.teamName(50, "alice", "bbbbbbbb-0000-0000-0000-000000000000");
        assertNotEquals(a, b, "distinct UUIDs must yield distinct team names");
    }

    @Test
    void nameIsSanitizedAndLowercased() {
        String name = TabTeamNaming.teamName(0, "Bad Name!", "11111111-1111-1111-1111-111111111111");
        assertTrue(name.startsWith("nn_"), "team name must start with nn_");
        assertTrue(name.contains("badname"), "name must be lowercased and stripped of invalid chars");
    }
}
