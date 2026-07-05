package com.nextnodes.permissions.ban;

import org.bson.Document;

/** One ban record. Type "account" (targetUuid set, ip = auto-banned last IP) or "ip" (ip set). */
public final class BanEntry {
    public static final String TYPE_ACCOUNT = "account";
    public static final String TYPE_IP = "ip";

    public String id;
    public String type;
    public String targetUuid; // account bans
    public String targetName; // account bans (display)
    public String ip;         // ip bans = the IP; account bans = auto-banned last IP (nullable)
    public String reason;
    public String issuer;
    public long createdAt;
    public Long expiresAt;    // null = permanent
    public boolean active = true;
    public Long unbannedAt;
    public String unbannedBy;
    public String endReason;  // "unban" | "expired" | null

    public static BanEntry account(String id, String uuid, String name, String ip,
                                   String reason, String issuer, long now, Long expiresAt) {
        BanEntry b = new BanEntry();
        b.id = id; b.type = TYPE_ACCOUNT; b.targetUuid = uuid; b.targetName = name; b.ip = ip;
        b.reason = reason; b.issuer = issuer; b.createdAt = now; b.expiresAt = expiresAt; b.active = true;
        return b;
    }

    public static BanEntry ip(String id, String ip, String reason, String issuer, long now, Long expiresAt) {
        BanEntry b = new BanEntry();
        b.id = id; b.type = TYPE_IP; b.ip = ip;
        b.reason = reason; b.issuer = issuer; b.createdAt = now; b.expiresAt = expiresAt; b.active = true;
        return b;
    }

    public boolean isActiveAt(long now) {
        return this.active && (this.expiresAt == null || this.expiresAt > now);
    }

    public Document toDocument() {
        Document d = new Document("_id", id)
                .append("type", type)
                .append("targetUuid", targetUuid)
                .append("targetName", targetName)
                .append("ip", ip)
                .append("reason", reason)
                .append("issuer", issuer)
                .append("createdAt", createdAt)
                .append("active", active);
        if (expiresAt != null) d.append("expiresAt", expiresAt);
        if (unbannedAt != null) d.append("unbannedAt", unbannedAt);
        if (unbannedBy != null) d.append("unbannedBy", unbannedBy);
        if (endReason != null) d.append("endReason", endReason);
        return d;
    }

    public static BanEntry fromDocument(Document d) {
        BanEntry b = new BanEntry();
        b.id = d.getString("_id");
        b.type = d.getString("type");
        b.targetUuid = d.getString("targetUuid");
        b.targetName = d.getString("targetName");
        b.ip = d.getString("ip");
        b.reason = d.getString("reason");
        b.issuer = d.getString("issuer");
        b.createdAt = d.get("createdAt") instanceof Number n ? n.longValue() : 0L;
        // toDocument() always writes "active", so a missing key defaulting to false is not
        // reachable via our own writers; the null-safe read only guards foreign/legacy docs.
        b.active = Boolean.TRUE.equals(d.getBoolean("active"));
        b.expiresAt = d.get("expiresAt") instanceof Number e ? e.longValue() : null;
        b.unbannedAt = d.get("unbannedAt") instanceof Number u ? u.longValue() : null;
        b.unbannedBy = d.getString("unbannedBy");
        b.endReason = d.getString("endReason");
        return b;
    }
}
