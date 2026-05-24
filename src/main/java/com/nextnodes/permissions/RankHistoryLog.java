package com.nextnodes.permissions;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes rank assignment/removal events to the {@code rank_history} MongoDB collection.
 * Failures are silently swallowed so they never interrupt normal mod flow.
 */
public final class RankHistoryLog {
    private static final String COL = "rank_history";

    private final PermissionStore store;

    RankHistoryLog(PermissionStore store) {
        this.store = store;
    }

    /**
     * Records a rank change event.
     *
     * @param playerUuid UUID string of the affected player
     * @param playerName display name of the affected player
     * @param action     "add", "remove", or "set-primary"
     * @param rankName   name of the rank involved
     * @param actorId    UUID string of the admin who made the change, or {@code null} for console/web
     * @param actorName  display name of the admin
     */
    public void record(String playerUuid, String playerName,
                       String action, String rankName,
                       String actorId, String actorName) {
        try {
            this.store.database().getCollection(COL).insertOne(
                    new Document("_id",        UUID.randomUUID().toString())
                            .append("timestamp",  System.currentTimeMillis())
                            .append("playerUuid", playerUuid != null ? playerUuid : "")
                            .append("playerName", playerName != null ? playerName : "")
                            .append("action",     action     != null ? action     : "")
                            .append("rankName",   rankName   != null ? rankName   : "")
                            .append("actorId",    actorId    != null ? actorId    : "console")
                            .append("actorName",  actorName  != null ? actorName  : "Console"));
        } catch (Exception ignored) {
            // History failures must never break the normal flow
        }
    }

    /**
     * Returns the rank history for a specific player, newest first.
     */
    public List<Document> forPlayer(String uuid, int limit) {
        try {
            List<Document> list = new ArrayList<>();
            for (Document doc : this.store.database()
                    .getCollection(COL)
                    .find(Filters.eq("playerUuid", uuid))
                    .sort(Sorts.descending("timestamp"))
                    .limit(limit)) {
                list.add(doc);
            }
            return list;
        } catch (Exception ex) {
            return List.of();
        }
    }
}
