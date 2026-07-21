package com.nextnodes.permissions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mongodb.CursorType;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.nextnodes.permissions.PermissionModels.PermissionData;
import com.nextnodes.permissions.PermissionModels.PermissionRule;
import com.nextnodes.permissions.PermissionModels.Rank;
import com.nextnodes.permissions.PermissionModels.UserEntry;
import org.bson.Document;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class PermissionStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final String COL_RANKS    = "ranks";
    private static final String COL_USERS    = "users";
    private static final String COL_SETTINGS = "settings";
    private static final String COL_SYNC = "sync_events";

    private static final String KEY_DEFAULT_RANK   = "defaultRank";
    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_API_KEY        = "apiKey";
    private static final String KEY_TAB_SETTINGS   = "tabSettings";
    private static final String KEY_LIMIT_SETTINGS = "limitSettings";

    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final CopyOnWriteArrayList<Runnable>           listeners             = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable>           onlineListeners       = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<UserEntry>> userSavedListeners   = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable>           tabSettingsListeners  = new CopyOnWriteArrayList<>();

    private final String connectionUri;
    private final String databaseName;

    private MongoClient   mongoClient;
    private MongoDatabase database;

    private PermissionData          data           = new PermissionData();
    private volatile PermissionData cachedSnapshot;
    private final String serverId = UUID.randomUUID().toString();
    private volatile boolean syncClosed = false;
    private volatile long lastEventTs = 0L;
    private Thread syncThread;

    public PermissionStore(String connectionUri, String databaseName) {
        this.connectionUri = connectionUri;
        this.databaseName  = databaseName;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void load() throws IOException {
        this.syncClosed = false;
        this.lock.writeLock().lock();
        try {
            closeClient();
            this.mongoClient = MongoClients.create(this.connectionUri);
            this.database    = this.mongoClient.getDatabase(this.databaseName);
            this.data        = readAll();
            this.data.ensureDefaults();
            writeDefaultsIfMissing();
            ensureSyncCollection();
            this.cachedSnapshot = null;
        } catch (Exception ex) {
            throw new IOException("Unable to load MongoDB permission store at " + this.connectionUri, ex);
        } finally {
            this.lock.writeLock().unlock();
        }
        startSyncListener();
    }

    public void close() {
        this.syncClosed = true;
        if (this.syncThread != null) {
            this.syncThread.interrupt();
            this.syncThread = null;
        }
        this.lock.writeLock().lock();
        try {
            closeClient();
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    private void closeClient() {
        if (this.mongoClient != null) {
            try { this.mongoClient.close(); } catch (Exception ignored) {}
            this.mongoClient = null;
            this.database    = null;
        }
    }

    // -------------------------------------------------------------------------
    // Snapshots (read-only views used by web panel and permission resolver)
    // -------------------------------------------------------------------------

    public PermissionData snapshot() {
        PermissionData cached = this.cachedSnapshot;
        if (cached != null) {
            return cached;
        }
        this.lock.readLock().lock();
        try {
            cached = GSON.fromJson(GSON.toJson(this.data), PermissionData.class);
            this.cachedSnapshot = cached;
            return cached;
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public String snapshotJson() {
        this.lock.readLock().lock();
        try {
            return GSON.toJson(this.data);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Rank mutations
    // -------------------------------------------------------------------------

    public void saveRank(Rank rank) throws IOException {
        Objects.requireNonNull(rank, "rank");
        this.lock.writeLock().lock();
        try {
            rank.sanitize();
            if (rank.name.isBlank()) {
                throw new IllegalArgumentException("rank name is required");
            }
            try {
                col(COL_RANKS).replaceOne(Filters.eq("_id", rank.name), rankToDoc(rank), UPSERT);
                this.data.ranks.put(rank.name, rank);
                this.cachedSnapshot = null;
            } catch (Exception ex) {
                throw new IOException("Unable to save rank", ex);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
        publishEvent("rank", rank.name);
    }

    public void deleteRank(String name) throws IOException {
        String normalized = PermissionModels.normalizeName(name);
        this.lock.writeLock().lock();
        try {
            if (normalized.equals(this.data.defaultRank)) {
                throw new IllegalArgumentException("default rank cannot be deleted");
            }
            try {
                // Remove main document
                col(COL_RANKS).deleteOne(Filters.eq("_id", normalized));

                // Remove from in-memory map first so cascade does not re-insert it
                this.data.ranks.remove(normalized);

                // Cascade: remove from other ranks parent lists
                MongoCollection<Document> ranksCol = col(COL_RANKS);
                for (Rank rank : this.data.ranks.values()) {
                    if (rank.parents.removeIf(p -> p.equals(normalized))) {
                        ranksCol.replaceOne(Filters.eq("_id", rank.name), rankToDoc(rank), UPSERT);
                    }
                }

                // Cascade: remove from users rank lists / reset primary rank
                MongoCollection<Document> usersCol = col(COL_USERS);
                for (UserEntry user : this.data.users.values()) {
                    boolean changed = user.ranks.removeIf(r -> r.equals(normalized));
                    if (user.primaryRank.equals(normalized)) {
                        user.primaryRank = this.data.defaultRank;
                        changed = true;
                    }
                    if (changed) {
                        usersCol.replaceOne(Filters.eq("_id", user.uuid), userToDoc(user), UPSERT);
                    }
                }

                this.cachedSnapshot = null;
            } catch (Exception ex) {
                // Reload to keep in-memory state consistent with the database
                try {
                    this.data = readAll();
                    this.data.ensureDefaults();
                } catch (Exception ignored) {}
                this.cachedSnapshot = null;
                throw new IOException("Unable to delete rank", ex);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
        publishEvent("rank", normalized);
    }

    // -------------------------------------------------------------------------
    // User mutations
    // -------------------------------------------------------------------------

    public void saveUser(UserEntry user) throws IOException {
        Objects.requireNonNull(user, "user");
        this.lock.writeLock().lock();
        try {
            user.sanitize();
            UUID.fromString(user.uuid); // validate UUID format
            if (user.primaryRank.isBlank()) {
                user.primaryRank = this.data.defaultRank;
            }
            if (!user.ranks.contains(user.primaryRank)) {
                user.ranks.add(user.primaryRank);
            }
            try {
                col(COL_USERS).replaceOne(Filters.eq("_id", user.uuid), userToDoc(user), UPSERT);
                this.data.users.put(user.uuid, user);
                this.cachedSnapshot = null;
            } catch (Exception ex) {
                throw new IOException("Unable to save user", ex);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
        fireUserSaved(user);
        publishEvent("user", user.uuid);
    }

    /** Removes the given ranks (and their expiry) from a user, clearing primaryRank if it was one.
     *  Used by the rank-expiry sweep. Fires change + sync only if something was actually removed. */
    public void expireRanks(String uuid, java.util.Collection<String> ranks) throws IOException {
        boolean changed = false;
        this.lock.writeLock().lock();
        try {
            UserEntry user = this.data.users.get(uuid);
            if (user == null) {
                return;
            }
            for (String rank : ranks) {
                if (user.ranks.remove(rank)) changed = true;
                if (user.rankExpiries != null && user.rankExpiries.remove(rank) != null) changed = true;
                if (rank.equals(user.primaryRank)) { user.primaryRank = ""; changed = true; }
            }
            if (!changed) {
                return;
            }
            user.sanitize();
            if (user.primaryRank.isBlank()) user.primaryRank = this.data.defaultRank;
            if (!user.ranks.contains(user.primaryRank)) user.ranks.add(user.primaryRank);
            col(COL_USERS).replaceOne(Filters.eq("_id", uuid), userToDoc(user), UPSERT);
            this.cachedSnapshot = null;
        } catch (Exception ex) {
            throw new IOException("Unable to expire ranks", ex);
        } finally {
            this.lock.writeLock().unlock();
        }
        if (changed) {
            fireChanged();
            publishEvent("user", uuid);
        }
    }

    public void deleteUser(String uuid) throws IOException {
        this.lock.writeLock().lock();
        try {
            try {
                col(COL_USERS).deleteOne(Filters.eq("_id", uuid));
                this.data.users.remove(uuid);
                this.cachedSnapshot = null;
            } catch (Exception ex) {
                throw new IOException("Unable to delete user", ex);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
        publishEvent("user", uuid);
    }

    public void touchPlayer(UUID uuid, String name, boolean online) throws IOException {
        UserEntry user;
        this.lock.writeLock().lock();
        try {
            if (this.database == null) return; // store already closed
            user = this.data.users.computeIfAbsent(uuid.toString(), key -> {
                UserEntry created = new UserEntry();
                created.uuid = key;
                created.primaryRank = this.data.defaultRank;
                created.ranks.add(this.data.defaultRank);
                return created;
            });
            user.name     = name == null ? user.name : name;
            user.online   = online;
            user.lastSeen = Instant.now().toEpochMilli();
            user.sanitize();
            if (user.primaryRank.isBlank()) {
                user.primaryRank = this.data.defaultRank;
            }
            if (!user.ranks.contains(user.primaryRank)) {
                user.ranks.add(user.primaryRank);
            }
            try {
                col(COL_USERS).replaceOne(Filters.eq("_id", user.uuid), userToDoc(user), UPSERT);
                this.cachedSnapshot = null;
            } catch (Exception ex) {
                throw new IOException("Unable to update player", ex);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
        fireUserSaved(user);
    }

    public void setOnline(UUID uuid, boolean online) throws IOException {
        this.lock.writeLock().lock();
        try {
            if (this.database == null) return; // store already closed (e.g. server stopping)
            UserEntry user = this.data.users.get(uuid.toString());
            if (user == null) return;
            user.online   = online;
            user.lastSeen = Instant.now().toEpochMilli();
            try {
                col(COL_USERS).replaceOne(Filters.eq("_id", user.uuid), userToDoc(user), UPSERT);
                this.cachedSnapshot = null;
            } catch (Exception ex) {
                throw new IOException("Unable to update player online state", ex);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        fireOnlineChanged();
    }

    // -------------------------------------------------------------------------
    // Maintenance
    // -------------------------------------------------------------------------

    public void cleanExpiredRules() throws IOException {
        this.lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (Rank rank : this.data.ranks.values()) {
                if (rank.permissions.removeIf(r -> r.expiresAt != null && r.expiresAt > 0 && r.expiresAt <= now)) {
                    col(COL_RANKS).replaceOne(Filters.eq("_id", rank.name), rankToDoc(rank), UPSERT);
                    changed = true;
                }
            }
            for (UserEntry user : this.data.users.values()) {
                if (user.permissions.removeIf(r -> r.expiresAt != null && r.expiresAt > 0 && r.expiresAt <= now)) {
                    col(COL_USERS).replaceOne(Filters.eq("_id", user.uuid), userToDoc(user), UPSERT);
                    changed = true;
                }
            }
            if (changed) {
                this.cachedSnapshot = null;
            }
        } catch (Exception ex) {
            throw new IOException("Unable to clean expired rules", ex);
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Listener registration
    // -------------------------------------------------------------------------

    public void addChangeListener(Runnable listener) {
        this.listeners.add(listener);
    }

    public void addOnlineChangeListener(Runnable listener) {
        this.onlineListeners.add(listener);
    }

    public void addUserSavedListener(Consumer<UserEntry> listener) {
        this.userSavedListeners.add(listener);
    }

    public void addTabSettingsListener(Runnable listener) {
        this.tabSettingsListeners.add(listener);
    }

    // -------------------------------------------------------------------------
    // Export / Import
    // -------------------------------------------------------------------------

    /**
     * Replaces ALL data in the database with the provided snapshot.
     * Existing ranks, users, and settings are dropped and rebuilt.
     */
    public void importAll(PermissionData pd) throws IOException {
        this.lock.writeLock().lock();
        try {
            pd.ensureDefaults();
            col(COL_RANKS).drop();
            col(COL_USERS).drop();
            col(COL_SETTINGS).drop();
            col(COL_SETTINGS).insertOne(new Document("_id", KEY_DEFAULT_RANK).append("value", pd.defaultRank));
            col(COL_SETTINGS).insertOne(new Document("_id", KEY_SCHEMA_VERSION).append("value", pd.schemaVersion));
            // Preserve the API key across imports
            String existingApiKey = this.data != null ? getApiKeyInternal() : null;
            if (existingApiKey != null) {
                col(COL_SETTINGS).insertOne(new Document("_id", KEY_API_KEY).append("value", existingApiKey));
            }
            for (Rank rank : pd.ranks.values()) col(COL_RANKS).insertOne(rankToDoc(rank));
            for (UserEntry user : pd.users.values()) col(COL_USERS).insertOne(userToDoc(user));
            this.data = pd;
            this.cachedSnapshot = null;
        } catch (Exception ex) {
            try { this.data = readAll(); this.data.ensureDefaults(); } catch (Exception ignored) {}
            this.cachedSnapshot = null;
            throw new IOException("Unable to import data", ex);
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
        publishEvent("import", "");
    }

    // -------------------------------------------------------------------------
    // API Key
    // -------------------------------------------------------------------------

    public String getApiKey() {
        this.lock.readLock().lock();
        try {
            return getApiKeyInternal();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public void setApiKey(String key) {
        this.lock.writeLock().lock();
        try {
            col(COL_SETTINGS).replaceOne(
                    Filters.eq("_id", KEY_API_KEY),
                    new Document("_id", KEY_API_KEY).append("value", key),
                    UPSERT);
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    private String getApiKeyInternal() {
        try {
            Document doc = col(COL_SETTINGS).find(Filters.eq("_id", KEY_API_KEY)).first();
            return doc != null ? doc.getString("value") : null;
        } catch (Exception ex) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Internal: MongoDB I/O
    // -------------------------------------------------------------------------

    private void ensureSyncCollection() {
        try {
            boolean exists = false;
            for (String name : this.database.listCollectionNames()) {
                if (name.equals(COL_SYNC)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                this.database.createCollection(COL_SYNC,
                        new CreateCollectionOptions().capped(true).sizeInBytes(1_048_576L).maxDocuments(10_000L));
            }
            // Seed one document so the tailable cursor always has a valid anchor point.
            col(COL_SYNC).insertOne(new Document("origin", this.serverId)
                    .append("seed", true)
                    .append("ts", System.currentTimeMillis()));
        } catch (Exception ignored) {
            // Another server may have created it concurrently, or it already exists; safe to ignore.
        }
    }

    private void publishEvent(String type, String key) {
        try {
            if (this.database == null) {
                return;
            }
            col(COL_SYNC).insertOne(new Document("origin", this.serverId)
                    .append("type", type)
                    .append("key", key)
                    .append("ts", System.currentTimeMillis()));
        } catch (Exception ignored) {
        }
    }

    private void startSyncListener() {
        if (this.syncThread != null) {
            return;
        }
        this.lastEventTs = System.currentTimeMillis();
        this.syncThread = new Thread(this::runSyncListener, "nextnodes-sync");
        this.syncThread.setDaemon(true);
        this.syncThread.start();
    }

    private void runSyncListener() {
        while (!this.syncClosed) {
            try (MongoCursor<Document> cursor = col(COL_SYNC)
                    .find(Filters.gt("ts", this.lastEventTs))
                    .cursorType(CursorType.TailableAwait)
                    .noCursorTimeout(true)
                    .iterator()) {
                while (!this.syncClosed && cursor.hasNext()) {
                    Document event = cursor.next();
                    this.lastEventTs = Math.max(this.lastEventTs, longFrom(event.get("ts"), this.lastEventTs));
                    if (this.serverId.equals(event.getString("origin"))) {
                        continue; // ignore our own writes
                    }
                    if (Boolean.TRUE.equals(event.getBoolean("seed"))) {
                        continue;
                    }
                    reloadFromExternalChange();
                }
            } catch (Exception ex) {
                if (this.syncClosed) {
                    return;
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    private void reloadFromExternalChange() {
        this.lock.writeLock().lock();
        try {
            if (this.database == null) {
                return;
            }
            PermissionData fresh = readAll();
            fresh.ensureDefaults();
            this.data = fresh;
            this.cachedSnapshot = null;
        } catch (Exception ignored) {
            return;
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
    }

    private MongoCollection<Document> col(String name) {
        return this.database.getCollection(name);
    }

    /** Publishes a sync event so other servers reload. Public wrapper over the private publishEvent. */
    public void publishSyncEvent(String type, String key) {
        publishEvent(type, key);
    }

    private PermissionData readAll() {
        PermissionData pd = new PermissionData();

        // Settings
        Document defaultRankDoc = col(COL_SETTINGS).find(Filters.eq("_id", KEY_DEFAULT_RANK)).first();
        if (defaultRankDoc != null && defaultRankDoc.getString("value") != null) {
            pd.defaultRank = defaultRankDoc.getString("value");
        }
        Document schemaDoc = col(COL_SETTINGS).find(Filters.eq("_id", KEY_SCHEMA_VERSION)).first();
        if (schemaDoc != null) {
            Object val = schemaDoc.get("value");
            if (val instanceof Number n) pd.schemaVersion = n.intValue();
        }

        // Ranks
        for (Document doc : col(COL_RANKS).find()) {
            Rank rank = docToRank(doc);
            pd.ranks.put(rank.name, rank);
        }

        // Users
        for (Document doc : col(COL_USERS).find()) {
            UserEntry user = docToUser(doc);
            pd.users.put(user.uuid, user);
        }

        // Tab settings
        Document tabSettingsDoc = col(COL_SETTINGS).find(Filters.eq("_id", KEY_TAB_SETTINGS)).first();
        if (tabSettingsDoc != null) {
            String json = tabSettingsDoc.getString("value");
            if (json != null) {
                try {
                    PermissionModels.TabSettings ts = GSON.fromJson(json, PermissionModels.TabSettings.class);
                    if (ts != null) pd.tabSettings = ts;
                } catch (Exception ignored) {}
            }
        }

        // Player-limit settings
        Document limitSettingsDoc = col(COL_SETTINGS).find(Filters.eq("_id", KEY_LIMIT_SETTINGS)).first();
        if (limitSettingsDoc != null) {
            PermissionModels.LimitSettings ls = new PermissionModels.LimitSettings();
            ls.enabled = Boolean.TRUE.equals(limitSettingsDoc.getBoolean("enabled", false));
            ls.max = intFrom(limitSettingsDoc.get("max"), 20);
            String km = limitSettingsDoc.getString("kickMessage");
            if (km != null && !km.isBlank()) ls.kickMessage = km;
            pd.limitSettings = ls;
        }

        return pd;
    }

    // -------------------------------------------------------------------------
    // Tab settings
    // -------------------------------------------------------------------------

    public PermissionModels.TabSettings getTabSettings() {
        this.lock.readLock().lock();
        try {
            return this.data.tabSettings != null ? this.data.tabSettings : new PermissionModels.TabSettings();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public void saveTabSettings(PermissionModels.TabSettings settings) throws IOException {
        Objects.requireNonNull(settings, "settings");
        this.lock.writeLock().lock();
        try {
            String json = GSON.toJson(settings);
            col(COL_SETTINGS).replaceOne(
                    Filters.eq("_id", KEY_TAB_SETTINGS),
                    new Document("_id", KEY_TAB_SETTINGS).append("value", json),
                    UPSERT);
            this.data.tabSettings = settings;
            this.cachedSnapshot = null;
        } catch (Exception ex) {
            throw new IOException("Unable to save tab settings", ex);
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
        fireTabSettingsChanged();
        publishEvent("settings", "tab");
    }

    public PermissionModels.LimitSettings getLimitSettings() {
        this.lock.readLock().lock();
        try {
            return this.data.limitSettings != null ? this.data.limitSettings : new PermissionModels.LimitSettings();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public void saveLimitSettings(PermissionModels.LimitSettings settings) throws IOException {
        Objects.requireNonNull(settings, "settings");
        settings.sanitize();
        this.lock.writeLock().lock();
        try {
            col(COL_SETTINGS).replaceOne(
                    Filters.eq("_id", KEY_LIMIT_SETTINGS),
                    new Document("_id", KEY_LIMIT_SETTINGS)
                            .append("enabled", settings.enabled)
                            .append("max", settings.max)
                            .append("kickMessage", settings.kickMessage),
                    UPSERT);
            this.data.limitSettings = settings;
            this.cachedSnapshot = null;
        } catch (Exception ex) {
            throw new IOException("Unable to save limit settings", ex);
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
        publishEvent("settings", "limit");
    }

    private void writeDefaultsIfMissing() {
        col(COL_SETTINGS).replaceOne(
                Filters.eq("_id", KEY_DEFAULT_RANK),
                new Document("_id", KEY_DEFAULT_RANK).append("value", this.data.defaultRank),
                UPSERT);
        col(COL_SETTINGS).replaceOne(
                Filters.eq("_id", KEY_SCHEMA_VERSION),
                new Document("_id", KEY_SCHEMA_VERSION).append("value", this.data.schemaVersion),
                UPSERT);
        // Persist ranks created by ensureDefaults (e.g. the default rank on first boot)
        for (Rank rank : this.data.ranks.values()) {
            Document existing = col(COL_RANKS).find(Filters.eq("_id", rank.name)).first();
            if (existing == null) {
                col(COL_RANKS).insertOne(rankToDoc(rank));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal: Document <-> Model mapping
    // -------------------------------------------------------------------------

    private static Document rankToDoc(Rank rank) {
        List<Document> perms = new ArrayList<>(rank.permissions.size());
        for (PermissionRule rule : rank.permissions) perms.add(ruleToDoc(rule));
        return new Document("_id", rank.name)
                .append("displayName", rank.displayName)
                .append("prefix",      rank.prefix)
                .append("suffix",      rank.suffix)
                .append("weight",      rank.weight)
                .append("parents",     rank.parents)
                .append("permissions", perms)
                .append("meta",        new Document(rank.meta))
                .append("bypassPlayerLimit", rank.bypassPlayerLimit);
    }

    private static Rank docToRank(Document doc) {
        Rank rank = new Rank();
        rank.name        = doc.getString("_id");
        rank.displayName = doc.getString("displayName");
        rank.prefix      = doc.getString("prefix");
        rank.suffix      = doc.getString("suffix");
        rank.weight      = intFrom(doc.get("weight"), 0);
        rank.parents     = doc.getList("parents", String.class, new ArrayList<>());
        rank.bypassPlayerLimit = Boolean.TRUE.equals(doc.getBoolean("bypassPlayerLimit", false));

        Document meta = doc.get("meta", Document.class);
        if (meta != null) {
            rank.meta = new LinkedHashMap<>();
            meta.forEach((k, v) -> rank.meta.put(k, v != null ? v.toString() : null));
        }

        List<Document> perms = doc.getList("permissions", Document.class, new ArrayList<>());
        rank.permissions = new ArrayList<>(perms.size());
        for (Document p : perms) rank.permissions.add(docToRule(p));
        return rank;
    }

    private static Document userToDoc(UserEntry user) {
        List<Document> perms = new ArrayList<>(user.permissions.size());
        for (PermissionRule rule : user.permissions) perms.add(ruleToDoc(rule));
        return new Document("_id", user.uuid)
                .append("name",        user.name)
                .append("tag",         user.tag)
                .append("primaryRank", user.primaryRank)
                .append("ranks",       user.ranks)
                .append("permissions", perms)
                .append("meta",        new Document(user.meta))
                .append("rankExpiries", new Document(user.rankExpiries))
                .append("lastSeen",    user.lastSeen)
                .append("online",      user.online)
                .append("disguiseName", user.disguiseName)
                .append("disguiseRank", user.disguiseRank);
    }

    private static UserEntry docToUser(Document doc) {
        UserEntry user = new UserEntry();
        user.uuid        = doc.getString("_id");
        user.name        = doc.getString("name");
        user.tag         = doc.getString("tag");
        user.primaryRank = doc.getString("primaryRank");
        user.ranks       = doc.getList("ranks", String.class, new ArrayList<>());
        user.lastSeen    = longFrom(doc.get("lastSeen"), 0L);
        user.online      = Boolean.TRUE.equals(doc.getBoolean("online"));
        user.disguiseName = doc.getString("disguiseName");
        user.disguiseRank = doc.getString("disguiseRank");

        Document meta = doc.get("meta", Document.class);
        if (meta != null) {
            user.meta = new LinkedHashMap<>();
            meta.forEach((k, v) -> user.meta.put(k, v != null ? v.toString() : null));
        }

        Document rankExpiries = doc.get("rankExpiries", Document.class);
        if (rankExpiries != null) {
            user.rankExpiries = new LinkedHashMap<>();
            rankExpiries.forEach((k, v) -> {
                if (v instanceof Number n) user.rankExpiries.put(k, n.longValue());
            });
        }

        List<Document> perms = doc.getList("permissions", Document.class, new ArrayList<>());
        user.permissions = new ArrayList<>(perms.size());
        for (Document p : perms) user.permissions.add(docToRule(p));
        return user;
    }

    private static Document ruleToDoc(PermissionRule rule) {
        Document doc = new Document("node",     rule.node)
                .append("value",    rule.value)
                .append("mode",     rule.mode)
                .append("contexts", new Document(rule.contexts));
        if (rule.expiresAt != null) doc.append("expiresAt", rule.expiresAt);
        return doc;
    }

    private static PermissionRule docToRule(Document doc) {
        PermissionRule rule = new PermissionRule();
        rule.node      = doc.getString("node");
        rule.value     = Boolean.TRUE.equals(doc.getBoolean("value"));
        rule.mode      = doc.getString("mode");
        rule.expiresAt = longFrom(doc.get("expiresAt"), Long.MIN_VALUE);
        if (rule.expiresAt == Long.MIN_VALUE) rule.expiresAt = null;

        Document ctx = doc.get("contexts", Document.class);
        if (ctx != null) {
            rule.contexts = new LinkedHashMap<>();
            ctx.forEach((k, v) -> rule.contexts.put(k, v != null ? v.toString() : null));
        }
        return rule;
    }

    // -------------------------------------------------------------------------
    // Internal: type-safe BSON number helpers
    // -------------------------------------------------------------------------

    private static int intFrom(Object value, int def) {
        if (value instanceof Number n) return n.intValue();
        return def;
    }

    private static long longFrom(Object value, long def) {
        if (value instanceof Number n) return n.longValue();
        return def;
    }

    // -------------------------------------------------------------------------
    // Internal: event broadcasting
    // -------------------------------------------------------------------------

    private void fireChanged() {
        for (Runnable l : this.listeners) l.run();
    }

    private void fireOnlineChanged() {
        for (Runnable l : this.onlineListeners) l.run();
    }

    private void fireUserSaved(UserEntry user) {
        for (Consumer<UserEntry> l : this.userSavedListeners) l.accept(user);
    }

    private void fireTabSettingsChanged() {
        for (Runnable l : this.tabSettingsListeners) l.run();
    }

    // -------------------------------------------------------------------------
    // Used by AuditLog, RankHistoryLog (same package) and BanStore (com.nextnodes.permissions.ban)
    // -------------------------------------------------------------------------

    /** Exposes the live MongoDB handle for sibling stores (e.g. BanStore). May be null before load(). */
    public MongoDatabase database() {
        return this.database;
    }
}