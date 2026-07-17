package com.nextnodes.plugin;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.util.ArrayList;

/** Writes rank changes to the mod's Mongo (users collection) and publishes a sync event. */
public final class RankMongo implements AutoCloseable {
    private static final String COL_USERS = "users";
    private static final String COL_RANKS = "ranks";
    private static final String COL_SYNC = "sync_events";

    private final MongoClient client;
    private final MongoDatabase db;
    private final boolean onlineMode;

    public RankMongo(String uri, String database, boolean onlineMode) {
        this.client = MongoClients.create(uri);
        this.db = client.getDatabase(database);
        this.onlineMode = onlineMode;
        ensureSyncCollection();
    }

    /** Resolves the player identifier to the mod's {@code _id}. Prefers the real _id of an existing
     *  user doc matched by name (case-insensitive) so a capitalization mismatch never creates an
     *  orphan document; falls back to deriving the offline UUID from the name (offline server) or
     *  using the given premium UUID as-is (onlineMode) when the player has no doc yet. */
    private String resolve(String player) {
        Document existing = db.getCollection(COL_USERS)
                .find(Filters.regex("name", "^" + java.util.regex.Pattern.quote(player.trim()) + "$", "i"))
                .first();
        if (existing != null) {
            Object id = existing.get("_id");
            if (id instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return onlineMode ? UuidUtil.normalize(player) : OfflineUuid.of(player);
    }

    /** Adds a rank to the user's ranks list (creates the user doc if missing). Mirrors /nn rank add. */
    public void grant(String player, String rawRank, Long expiresAt) {
        String uuid = resolve(player);
        String rank = RankNames.normalize(rawRank);
        if (rank.isEmpty()) throw new IllegalArgumentException("rango vacío");
        // Rechaza rangos inexistentes (como /nn rank add), para no meter un rango basura por un typo.
        if (db.getCollection(COL_RANKS).find(Filters.eq("_id", rank)).first() == null) {
            throw new IllegalArgumentException("el rango '" + rank + "' no existe");
        }
        java.util.List<org.bson.conversions.Bson> updates = new ArrayList<>(java.util.List.of(
                Updates.addToSet("ranks", rank),
                Updates.setOnInsert("name", ""),
                Updates.setOnInsert("tag", ""),
                Updates.setOnInsert("primaryRank", ""),
                Updates.setOnInsert("permissions", new ArrayList<Document>()),
                Updates.setOnInsert("meta", new Document()),
                Updates.setOnInsert("lastSeen", 0L),
                Updates.setOnInsert("online", false)));
        // Con días -> fija la expiración; sin días -> la limpia (rango permanente / renovado).
        updates.add(expiresAt != null
                ? Updates.set("rankExpiries." + rank, expiresAt)
                : Updates.unset("rankExpiries." + rank));
        db.getCollection(COL_USERS).updateOne(
                Filters.eq("_id", uuid), Updates.combine(updates), new UpdateOptions().upsert(true));
        publishSync(uuid);
    }

    /** Removes a rank from the user's ranks list (and clears primaryRank if it was that). Mirrors /nn rank remove. */
    public void revoke(String player, String rawRank) {
        String uuid = resolve(player);
        String rank = RankNames.normalize(rawRank);
        if (rank.isEmpty()) throw new IllegalArgumentException("rango vacío");
        MongoCollection<Document> users = db.getCollection(COL_USERS);
        users.updateOne(Filters.eq("_id", uuid), Updates.combine(
                Updates.pull("ranks", rank),
                Updates.unset("rankExpiries." + rank)));
        users.updateOne(Filters.and(Filters.eq("_id", uuid), Filters.eq("primaryRank", rank)),
                Updates.set("primaryRank", ""));
        publishSync(uuid);
    }

    private void publishSync(String uuid) {
        db.getCollection(COL_SYNC).insertOne(SyncEvents.userEvent(uuid, System.currentTimeMillis()));
    }

    /** The mod's tailable cursor requires a capped sync_events collection; create it identically if absent. */
    private void ensureSyncCollection() {
        try {
            boolean exists = false;
            for (String name : db.listCollectionNames()) {
                if (name.equals(COL_SYNC)) { exists = true; break; }
            }
            if (!exists) {
                db.createCollection(COL_SYNC, new CreateCollectionOptions()
                        .capped(true).sizeInBytes(1_048_576L).maxDocuments(10_000L));
            }
        } catch (Exception ignored) {
            // Otro proceso pudo crearla; seguro de ignorar.
        }
    }

    /** Expone la base compartida para que otros lectores (p. ej. PlayerLimitMongo) reusen esta conexión. */
    MongoDatabase database() {
        return db;
    }

    @Override
    public void close() {
        try { client.close(); } catch (Exception ignored) {}
    }
}
