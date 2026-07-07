package com.nextnodes.tebex;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyncEventsTest {
    @Test void userEventShape() {
        Document d = SyncEvents.userEvent("069a79f4-44e9-4726-a5be-fca90e38aaf5", 1_700_000_000_000L);
        assertEquals("velocity-tebex", d.getString("origin"));
        assertEquals("user", d.getString("type"));
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", d.getString("key"));
        assertEquals(1_700_000_000_000L, d.getLong("ts"));
    }
}
