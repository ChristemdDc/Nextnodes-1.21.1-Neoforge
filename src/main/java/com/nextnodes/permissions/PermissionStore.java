package com.nextnodes.permissions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.nextnodes.permissions.PermissionModels.PermissionData;
import com.nextnodes.permissions.PermissionModels.PermissionRule;
import com.nextnodes.permissions.PermissionModels.Rank;
import com.nextnodes.permissions.PermissionModels.UserEntry;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class PermissionStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type STRING_LIST = new TypeToken<List<String>>() {
    }.getType();
    private static final Type STRING_MAP = new TypeToken<Map<String, String>>() {
    }.getType();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> onlineListeners = new CopyOnWriteArrayList<>();
    private final Path file;
    private PermissionData data = new PermissionData();
    private volatile PermissionData cachedSnapshot;
    private Connection persistentConnection;

    public PermissionStore(Path file) {
        this.file = file;
    }

    public void load() throws IOException {
        this.lock.writeLock().lock();
        try {
            Files.createDirectories(this.file.getParent());
            try {
                Class.forName("org.sqlite.JDBC");
                closePersistentConnection();
                this.persistentConnection = openNewConnection();
                migrate(this.persistentConnection);
                this.data = readAll(this.persistentConnection);
                this.data.ensureDefaults();
                writeDefaultsIfMissing(this.persistentConnection);
                this.cachedSnapshot = null;
            } catch (ClassNotFoundException | SQLException ex) {
                throw new IOException("Unable to load SQLite permission store", ex);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public void close() {
        this.lock.writeLock().lock();
        try {
            closePersistentConnection();
        } finally {
            this.lock.writeLock().unlock();
        }
    }

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

    public void saveRank(Rank rank) throws IOException {
        Objects.requireNonNull(rank, "rank");
        this.lock.writeLock().lock();
        try {
            rank.sanitize();
            if (rank.name.isBlank()) {
                throw new IllegalArgumentException("rank name is required");
            }
            Connection connection = getConnection();
            try {
                connection.setAutoCommit(false);
                writeRank(connection, rank);
                connection.commit();
                this.data.ranks.put(rank.name, rank);
                this.cachedSnapshot = null;
            } catch (SQLException ex) {
                rollback(connection);
                throw new IOException("Unable to save rank", ex);
            } finally {
                safeAutoCommit(connection);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
    }

    public void deleteRank(String name) throws IOException {
        String normalized = PermissionModels.normalizeName(name);
        this.lock.writeLock().lock();
        try {
            if (normalized.equals(this.data.defaultRank)) {
                throw new IllegalArgumentException("default rank cannot be deleted");
            }
            Connection connection = getConnection();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement deleteRank = connection.prepareStatement("DELETE FROM ranks WHERE name = ?")) {
                    deleteRank.setString(1, normalized);
                    deleteRank.executeUpdate();
                }
                try (PreparedStatement deletePerms = connection.prepareStatement("DELETE FROM rank_permissions WHERE rank_name = ?")) {
                    deletePerms.setString(1, normalized);
                    deletePerms.executeUpdate();
                }
                this.data.ranks.remove(normalized);
                this.data.ranks.values().forEach(rank -> rank.parents.removeIf(parent -> parent.equals(normalized)));
                this.data.users.values().forEach(user -> {
                    user.ranks.removeIf(rank -> rank.equals(normalized));
                    if (user.primaryRank.equals(normalized)) {
                        user.primaryRank = this.data.defaultRank;
                    }
                });
                rewriteAll(connection, this.data);
                connection.commit();
                this.cachedSnapshot = null;
            } catch (SQLException ex) {
                rollback(connection);
                this.data = reloadData(connection);
                throw new IOException("Unable to delete rank", ex);
            } finally {
                safeAutoCommit(connection);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
    }

    public void saveUser(UserEntry user) throws IOException {
        Objects.requireNonNull(user, "user");
        this.lock.writeLock().lock();
        try {
            user.sanitize();
            UUID.fromString(user.uuid);
            if (user.primaryRank.isBlank()) {
                user.primaryRank = this.data.defaultRank;
            }
            if (!user.ranks.contains(user.primaryRank)) {
                user.ranks.add(user.primaryRank);
            }
            Connection connection = getConnection();
            try {
                connection.setAutoCommit(false);
                writeUser(connection, user);
                connection.commit();
                this.data.users.put(user.uuid, user);
                this.cachedSnapshot = null;
            } catch (SQLException ex) {
                rollback(connection);
                throw new IOException("Unable to save user", ex);
            } finally {
                safeAutoCommit(connection);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
    }

    public void deleteUser(String uuid) throws IOException {
        this.lock.writeLock().lock();
        try {
            Connection connection = getConnection();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM user_permissions WHERE uuid = ?")) {
                    stmt.setString(1, uuid);
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM users WHERE uuid = ?")) {
                    stmt.setString(1, uuid);
                    stmt.executeUpdate();
                }
                connection.commit();
                this.data.users.remove(uuid);
                this.cachedSnapshot = null;
            } catch (SQLException ex) {
                rollback(connection);
                throw new IOException("Unable to delete user", ex);
            } finally {
                safeAutoCommit(connection);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
    }

    public void touchPlayer(UUID uuid, String name, boolean online) throws IOException {
        this.lock.writeLock().lock();
        try {
            UserEntry user = this.data.users.computeIfAbsent(uuid.toString(), key -> {
                UserEntry created = new UserEntry();
                created.uuid = key;
                created.primaryRank = this.data.defaultRank;
                created.ranks.add(this.data.defaultRank);
                return created;
            });
            user.name = name == null ? user.name : name;
            user.online = online;
            user.lastSeen = Instant.now().toEpochMilli();
            user.sanitize();
            if (user.primaryRank.isBlank()) {
                user.primaryRank = this.data.defaultRank;
            }
            if (!user.ranks.contains(user.primaryRank)) {
                user.ranks.add(user.primaryRank);
            }
            Connection connection = getConnection();
            try {
                connection.setAutoCommit(false);
                writeUser(connection, user);
                connection.commit();
                this.cachedSnapshot = null;
            } catch (SQLException ex) {
                rollback(connection);
                throw new IOException("Unable to update player", ex);
            } finally {
                safeAutoCommit(connection);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        fireChanged();
    }

    public void setOnline(UUID uuid, boolean online) throws IOException {
        this.lock.writeLock().lock();
        try {
            UserEntry user = this.data.users.get(uuid.toString());
            if (user == null) {
                return;
            }
            user.online = online;
            user.lastSeen = Instant.now().toEpochMilli();
            Connection connection = getConnection();
            try {
                connection.setAutoCommit(false);
                writeUser(connection, user);
                connection.commit();
                this.cachedSnapshot = null;
            } catch (SQLException ex) {
                rollback(connection);
                throw new IOException("Unable to update player online state", ex);
            } finally {
                safeAutoCommit(connection);
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        fireOnlineChanged();
    }

    public void cleanExpiredRules() throws IOException {
        this.lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (Rank rank : this.data.ranks.values()) {
                changed |= rank.permissions.removeIf(r -> r.expiresAt != null && r.expiresAt > 0 && r.expiresAt <= now);
            }
            for (UserEntry user : this.data.users.values()) {
                changed |= user.permissions.removeIf(r -> r.expiresAt != null && r.expiresAt > 0 && r.expiresAt <= now);
            }
            if (changed) {
                Connection connection = getConnection();
                try {
                    connection.setAutoCommit(false);
                    rewriteAll(connection, this.data);
                    connection.commit();
                    this.cachedSnapshot = null;
                } catch (SQLException ex) {
                    rollback(connection);
                    throw new IOException("Unable to clean expired rules", ex);
                } finally {
                    safeAutoCommit(connection);
                }
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public void addChangeListener(Runnable listener) {
        this.listeners.add(listener);
    }

    public void addOnlineChangeListener(Runnable listener) {
        this.onlineListeners.add(listener);
    }

    private Connection openNewConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + this.file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
        }
        return connection;
    }

    private Connection getConnection() throws IOException {
        try {
            if (this.persistentConnection == null || this.persistentConnection.isClosed()) {
                this.persistentConnection = openNewConnection();
            }
            return this.persistentConnection;
        } catch (SQLException ex) {
            throw new IOException("Unable to obtain database connection", ex);
        }
    }

    private void closePersistentConnection() {
        if (this.persistentConnection != null) {
            try {
                this.persistentConnection.close();
            } catch (SQLException ignored) {
            }
            this.persistentConnection = null;
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void safeAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }

    private PermissionData reloadData(Connection connection) {
        try {
            PermissionData fresh = readAll(connection);
            fresh.ensureDefaults();
            this.cachedSnapshot = null;
            return fresh;
        } catch (SQLException ex) {
            return this.data;
        }
    }

    private static void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS settings (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ranks (
                        name TEXT PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        prefix TEXT NOT NULL,
                        weight INTEGER NOT NULL,
                        parents_json TEXT NOT NULL,
                        meta_json TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS rank_permissions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        rank_name TEXT NOT NULL,
                        node TEXT NOT NULL,
                        value INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        expires_at INTEGER,
                        contexts_json TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        FOREIGN KEY(rank_name) REFERENCES ranks(name) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        uuid TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        primary_rank TEXT NOT NULL,
                        ranks_json TEXT NOT NULL,
                        meta_json TEXT NOT NULL,
                        last_seen INTEGER NOT NULL,
                        online INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS user_permissions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        node TEXT NOT NULL,
                        value INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        expires_at INTEGER,
                        contexts_json TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        FOREIGN KEY(uuid) REFERENCES users(uuid) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private void writeDefaultsIfMissing(Connection connection) throws SQLException {
        try (PreparedStatement setting = connection.prepareStatement("INSERT OR IGNORE INTO settings(key, value) VALUES('default_rank', ?)")) {
            setting.setString(1, this.data.defaultRank);
            setting.executeUpdate();
        }
        for (Rank rank : this.data.ranks.values()) {
            writeRank(connection, rank);
        }
    }

    private static PermissionData readAll(Connection connection) throws SQLException {
        PermissionData data = new PermissionData();
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM settings WHERE key = 'default_rank'");
             ResultSet result = statement.executeQuery()) {
            if (result.next()) {
                data.defaultRank = result.getString(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM ranks ORDER BY weight DESC, name ASC");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Rank rank = new Rank();
                rank.name = result.getString("name");
                rank.displayName = result.getString("display_name");
                rank.prefix = result.getString("prefix");
                rank.weight = result.getInt("weight");
                rank.parents = fromJson(result.getString("parents_json"), STRING_LIST, new ArrayList<>());
                rank.meta = fromJson(result.getString("meta_json"), STRING_MAP, new LinkedHashMap<>());
                rank.permissions = readRankPermissions(connection, rank.name);
                rank.sanitize();
                data.ranks.put(rank.name, rank);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM users ORDER BY online DESC, name ASC");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UserEntry user = new UserEntry();
                user.uuid = result.getString("uuid");
                user.name = result.getString("name");
                user.primaryRank = result.getString("primary_rank");
                user.ranks = fromJson(result.getString("ranks_json"), STRING_LIST, new ArrayList<>());
                user.meta = fromJson(result.getString("meta_json"), STRING_MAP, new LinkedHashMap<>());
                user.lastSeen = result.getLong("last_seen");
                user.online = result.getInt("online") != 0;
                user.permissions = readUserPermissions(connection, user.uuid);
                user.sanitize();
                data.users.put(user.uuid, user);
            }
        }
        data.ensureDefaults();
        return data;
    }

    private static List<PermissionRule> readRankPermissions(Connection connection, String rankName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM rank_permissions WHERE rank_name = ? ORDER BY position ASC, id ASC")) {
            statement.setString(1, rankName);
            return readRules(statement);
        }
    }

    private static List<PermissionRule> readUserPermissions(Connection connection, String uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM user_permissions WHERE uuid = ? ORDER BY position ASC, id ASC")) {
            statement.setString(1, uuid);
            return readRules(statement);
        }
    }

    private static List<PermissionRule> readRules(PreparedStatement statement) throws SQLException {
        List<PermissionRule> rules = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                PermissionRule rule = new PermissionRule();
                rule.node = result.getString("node");
                rule.value = result.getInt("value") != 0;
                rule.mode = result.getString("mode");
                long expiresAt = result.getLong("expires_at");
                rule.expiresAt = result.wasNull() ? null : expiresAt;
                rule.contexts = fromJson(result.getString("contexts_json"), STRING_MAP, new LinkedHashMap<>());
                rule.sanitize();
                rules.add(rule);
            }
        }
        return rules;
    }

    private static void writeRank(Connection connection, Rank rank) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ranks(name, display_name, prefix, weight, parents_json, meta_json)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET
                    display_name = excluded.display_name,
                    prefix = excluded.prefix,
                    weight = excluded.weight,
                    parents_json = excluded.parents_json,
                    meta_json = excluded.meta_json
                """)) {
            statement.setString(1, rank.name);
            statement.setString(2, rank.displayName);
            statement.setString(3, rank.prefix);
            statement.setInt(4, rank.weight);
            statement.setString(5, GSON.toJson(rank.parents));
            statement.setString(6, GSON.toJson(rank.meta));
            statement.executeUpdate();
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM rank_permissions WHERE rank_name = ?")) {
            delete.setString(1, rank.name);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rank_permissions(rank_name, node, value, mode, expires_at, contexts_json, position)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            writeRules(insert, rank.name, rank.permissions);
        }
    }

    private static void writeUser(Connection connection, UserEntry user) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users(uuid, name, primary_rank, ranks_json, meta_json, last_seen, online)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    name = excluded.name,
                    primary_rank = excluded.primary_rank,
                    ranks_json = excluded.ranks_json,
                    meta_json = excluded.meta_json,
                    last_seen = excluded.last_seen,
                    online = excluded.online
                """)) {
            statement.setString(1, user.uuid);
            statement.setString(2, user.name);
            statement.setString(3, user.primaryRank);
            statement.setString(4, GSON.toJson(user.ranks));
            statement.setString(5, GSON.toJson(user.meta));
            statement.setLong(6, user.lastSeen);
            statement.setInt(7, user.online ? 1 : 0);
            statement.executeUpdate();
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM user_permissions WHERE uuid = ?")) {
            delete.setString(1, user.uuid);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO user_permissions(uuid, node, value, mode, expires_at, contexts_json, position)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            writeRules(insert, user.uuid, user.permissions);
        }
    }

    private static void writeRules(PreparedStatement statement, String owner, List<PermissionRule> rules) throws SQLException {
        for (int i = 0; i < rules.size(); i++) {
            PermissionRule rule = rules.get(i);
            rule.sanitize();
            statement.setString(1, owner);
            statement.setString(2, rule.node);
            statement.setInt(3, rule.value ? 1 : 0);
            statement.setString(4, rule.mode);
            if (rule.expiresAt == null) {
                statement.setNull(5, java.sql.Types.INTEGER);
            } else {
                statement.setLong(5, rule.expiresAt);
            }
            statement.setString(6, GSON.toJson(rule.contexts));
            statement.setInt(7, i);
            statement.addBatch();
        }
        statement.executeBatch();
    }

    private static void rewriteAll(Connection connection, PermissionData data) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM rank_permissions");
            statement.executeUpdate("DELETE FROM user_permissions");
            statement.executeUpdate("DELETE FROM ranks");
            statement.executeUpdate("DELETE FROM users");
        }
        try (PreparedStatement setting = connection.prepareStatement("INSERT OR REPLACE INTO settings(key, value) VALUES('default_rank', ?)")) {
            setting.setString(1, data.defaultRank);
            setting.executeUpdate();
        }
        for (Rank rank : data.ranks.values()) {
            writeRank(connection, rank);
        }
        for (UserEntry user : data.users.values()) {
            writeUser(connection, user);
        }
    }

    private static <T> T fromJson(String json, Type type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        T value = GSON.fromJson(json, type);
        return value == null ? fallback : value;
    }

    private void fireChanged() {
        for (Runnable listener : this.listeners) {
            listener.run();
        }
    }

    private void fireOnlineChanged() {
        for (Runnable listener : this.onlineListeners) {
            listener.run();
        }
    }
}
