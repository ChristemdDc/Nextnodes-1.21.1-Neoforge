package com.nextnodes.tebex;

import org.bson.Document;

/** Builds the sync_events document the mod's tailable listener reacts to. */
public final class SyncEvents {
    public static final String ORIGIN = "velocity-tebex";
    private SyncEvents() {}

    public static Document userEvent(String uuid, long ts) {
        return new Document("origin", ORIGIN)
                .append("type", "user")
                .append("key", uuid)
                .append("ts", ts);
    }
}
