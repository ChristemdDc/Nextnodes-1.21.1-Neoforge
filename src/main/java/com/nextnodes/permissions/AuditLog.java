package com.nextnodes.permissions;

import com.mongodb.client.model.Sorts;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes structured audit entries to the {@code audit_log} MongoDB collection.
 * Failures are silently swallowed so they never interrupt normal mod flow.
 */
public final class AuditLog {
    private static final String COL = "audit_log";

    private final PermissionStore store;

    AuditLog(PermissionStore store) {
        this.store = store;
    }

    /**
     * Records an action in the audit log.
     *
     * @param actorId    UUID string of the player who performed the action, or {@code null} for console
     * @param actorName  display name of the actor
     * @param action     action identifier (e.g. "rank.create", "user.rank.set", "import")
     * @param targetType "rank", "user", or empty string
     * @param targetId   name/UUID of the affected entity
     * @param details    human-readable description of what changed
     */
    public void log(String actorId, String actorName,
                    String action, String targetType, String targetId, String details) {
        try {
            this.store.database().getCollection(COL).insertOne(
                    new Document("_id",        UUID.randomUUID().toString())
                            .append("timestamp",  System.currentTimeMillis())
                            .append("actorId",    actorId    != null ? actorId    : "console")
                            .append("actorName",  actorName  != null ? actorName  : "Console")
                            .append("action",     action     != null ? action     : "")
                            .append("targetType", targetType != null ? targetType : "")
                            .append("targetId",   targetId   != null ? targetId   : "")
                            .append("details",    details    != null ? details    : ""));
        } catch (Exception ignored) {
            // Audit failures must never break the normal flow
        }
    }

    /**
     * Returns the most recent {@code limit} entries, newest first.
     */
    public List<Document> recent(int limit) {
        try {
            List<Document> list = new ArrayList<>();
            for (Document doc : this.store.database()
                    .getCollection(COL)
                    .find()
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
