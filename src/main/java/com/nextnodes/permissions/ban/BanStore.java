package com.nextnodes.permissions.ban;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.nextnodes.permissions.PermissionStore;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** MongoDB-backed ban system: bans, player IP history, and the audit log. */
public final class BanStore {
    private static final String COL_BANS = "bans";
    private static final String COL_PLAYER_IPS = "player_ips";
    private static final String COL_LOG = "ban_log";

    private final PermissionStore permissions;

    public BanStore(PermissionStore permissions) {
        this.permissions = permissions;
    }

    private MongoCollection<Document> col(String name) {
        MongoDatabase db = this.permissions.database();
        if (db == null) throw new IllegalStateException("MongoDB no inicializado");
        return db.getCollection(name);
    }

    // ---- IP history -------------------------------------------------------

    /** Records that {@code uuid}/{@code name} connected from {@code ip}. Called on login. */
    public void recordIp(String uuid, String name, String ip, long now) {
        if (uuid == null || ip == null || ip.isBlank()) return;
        try {
            Document existing = col(COL_PLAYER_IPS).find(Filters.eq("_id", uuid)).first();
            Document ipsSub = existing == null ? null : existing.get("ipsByAddr", Document.class);
            Document ipMap = ipsSub != null ? ipsSub : new Document();
            Document entry = ipMap.get(ip.replace('.', '_'), Document.class);
            if (entry == null) {
                entry = new Document("ip", ip).append("firstSeen", now).append("count", 0L);
            }
            entry.append("lastSeen", now).append("count",
                    (entry.get("count") instanceof Number c ? c.longValue() : 0L) + 1L);
            ipMap.put(ip.replace('.', '_'), entry);
            Document doc = new Document("_id", uuid)
                    .append("name", name)
                    .append("lastIp", ip)
                    .append("ipsByAddr", ipMap);
            col(COL_PLAYER_IPS).replaceOne(Filters.eq("_id", uuid), doc, new ReplaceOptions().upsert(true));
        } catch (Exception ignored) {}
    }

    /** @return the player's last known IP, or null. Accepts a UUID or (case-insensitive) name. */
    public String lastIpOf(String uuidOrName) {
        try {
            Document byId = col(COL_PLAYER_IPS).find(Filters.eq("_id", uuidOrName)).first();
            if (byId != null) return byId.getString("lastIp");
            Document byName = col(COL_PLAYER_IPS)
                    .find(Filters.regex("name", "^" + java.util.regex.Pattern.quote(uuidOrName) + "$", "i"))
                    .first();
            return byName == null ? null : byName.getString("lastIp");
        } catch (Exception ex) {
            return null;
        }
    }

    /** @return player entries (uuid/name/lastIp) that have connected from {@code ip}. */
    public List<PlayerIpEntry> accountsForIp(String ip) {
        List<PlayerIpEntry> out = new ArrayList<>();
        if (ip == null || ip.isBlank()) return out;
        try {
            Bson filter = Filters.exists("ipsByAddr." + ip.replace('.', '_'));
            for (Document d : col(COL_PLAYER_IPS).find(filter)) {
                out.add(new PlayerIpEntry(d.getString("_id"), d.getString("name"), d.getString("lastIp")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---- Enforcement ------------------------------------------------------

    /** @return an active ban blocking this (uuid, ip), or null. Queried fresh (cross-server). */
    public BanEntry findBlockingBan(String uuid, String ip, long now) {
        try {
            List<Bson> or = new ArrayList<>();
            if (uuid != null) or.add(Filters.eq("targetUuid", uuid));
            if (ip != null && !ip.isBlank()) or.add(Filters.eq("ip", ip));
            if (or.isEmpty()) return null;
            Bson filter = Filters.and(Filters.eq("active", true), Filters.or(or));
            for (Document d : col(COL_BANS).find(filter)) {
                BanEntry b = BanEntry.fromDocument(d);
                if (b.isActiveAt(now)) return b;
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    // ---- Ban / unban ------------------------------------------------------

    /** Bans an account and auto-bans its last known IP. Returns the created ban. */
    public BanEntry banAccount(String uuid, String name, String reason, String issuer, Long expiresAt, long now) {
        String ip = lastIpOf(uuid != null ? uuid : name);
        BanEntry ban = BanEntry.account(UUID.randomUUID().toString(), uuid, name, ip, reason, issuer, now, expiresAt);
        saveAndLog(ban, now);
        return ban;
    }

    /** Bans a raw IP. */
    public BanEntry banIp(String ip, String reason, String issuer, Long expiresAt, long now) {
        BanEntry ban = BanEntry.ip(UUID.randomUUID().toString(), ip, reason, issuer, now, expiresAt);
        saveAndLog(ban, now);
        return ban;
    }

    private void saveAndLog(BanEntry ban, long now) {
        col(COL_BANS).replaceOne(Filters.eq("_id", ban.id), ban.toDocument(), new ReplaceOptions().upsert(true));
        log(BanLogEntry.BAN, ban, now);
        this.permissions.publishSyncEvent("ban", ban.id);
    }

    /** Lifts all active bans matching a UUID, name, or IP. @return number lifted. */
    public int unban(String targetUuidNameOrIp, String by, long now) {
        int count = 0;
        try {
            Bson filter = Filters.and(Filters.eq("active", true), Filters.or(
                    Filters.eq("targetUuid", targetUuidNameOrIp),
                    Filters.regex("targetName", "^" + java.util.regex.Pattern.quote(targetUuidNameOrIp) + "$", "i"),
                    Filters.eq("ip", targetUuidNameOrIp)));
            for (Document d : col(COL_BANS).find(filter)) {
                BanEntry b = BanEntry.fromDocument(d);
                col(COL_BANS).updateOne(Filters.eq("_id", b.id), Updates.combine(
                        Updates.set("active", false),
                        Updates.set("unbannedAt", now),
                        Updates.set("unbannedBy", by),
                        Updates.set("endReason", "unban")));
                log(BanLogEntry.UNBAN, b, now);
                count++;
            }
            if (count > 0) this.permissions.publishSyncEvent("ban", targetUuidNameOrIp);
        } catch (Exception ignored) {}
        return count;
    }

    /** Lifts a single ban by its id (used by the web DELETE). @return true if lifted. */
    public boolean unbanById(String id, String by, long now) {
        try {
            Document d = col(COL_BANS).find(Filters.and(Filters.eq("_id", id), Filters.eq("active", true))).first();
            if (d == null) return false;
            BanEntry b = BanEntry.fromDocument(d);
            col(COL_BANS).updateOne(Filters.eq("_id", id), Updates.combine(
                    Updates.set("active", false),
                    Updates.set("unbannedAt", now),
                    Updates.set("unbannedBy", by),
                    Updates.set("endReason", "unban")));
            log(BanLogEntry.UNBAN, b, now);
            this.permissions.publishSyncEvent("ban", id);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    // ---- Queries for UI -----------------------------------------------------

    /** Active bans (marks + logs any that have expired since last checked). */
    public List<BanEntry> listActive(long now) {
        List<BanEntry> out = new ArrayList<>();
        try {
            for (Document d : col(COL_BANS).find(Filters.eq("active", true)).sort(Sorts.descending("createdAt"))) {
                BanEntry b = BanEntry.fromDocument(d);
                if (b.expiresAt != null && b.expiresAt <= now) {
                    col(COL_BANS).updateOne(Filters.eq("_id", b.id), Updates.combine(
                            Updates.set("active", false), Updates.set("endReason", "expired")));
                    log(BanLogEntry.EXPIRE, b, now);
                    continue;
                }
                out.add(b);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Last {@code limit} log entries, most recent first. */
    public List<BanLogEntry> listLog(int limit) {
        List<BanLogEntry> out = new ArrayList<>();
        try {
            for (Document d : col(COL_LOG).find().sort(Sorts.descending("ts")).limit(limit)) {
                out.add(BanLogEntry.fromDocument(d));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Records a BLOCKED_JOIN attempt (called from enforcement). */
    public void logBlockedJoin(BanEntry ban, String uuid, String name, String ip, long now) {
        BanLogEntry e = new BanLogEntry();
        e.ts = now; e.action = BanLogEntry.BLOCKED_JOIN; e.type = ban.type;
        e.target = uuid != null ? uuid : ip; e.targetName = name; e.ip = ip;
        e.reason = ban.reason; e.issuer = ban.issuer;
        try { col(COL_LOG).insertOne(e.toDocument()); } catch (Exception ignored) {}
    }

    private void log(String action, BanEntry ban, long now) {
        BanLogEntry e = new BanLogEntry();
        e.ts = now; e.action = action; e.type = ban.type;
        e.target = ban.targetUuid != null ? ban.targetUuid : ban.ip;
        e.targetName = ban.targetName; e.ip = ban.ip; e.reason = ban.reason; e.issuer = ban.issuer;
        try { col(COL_LOG).insertOne(e.toDocument()); } catch (Exception ignored) {}
    }
}
