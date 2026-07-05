package com.nextnodes.permissions.ban;

import org.bson.Document;

/** One entry in the append-only ban_log ("registro"). */
public final class BanLogEntry {
    public static final String BAN = "BAN";
    public static final String UNBAN = "UNBAN";
    public static final String EXPIRE = "EXPIRE";
    public static final String BLOCKED_JOIN = "BLOCKED_JOIN";

    public long ts;
    public String action;
    public String type;       // account | ip
    public String target;     // uuid or ip
    public String targetName;
    public String ip;
    public String reason;
    public String issuer;

    public Document toDocument() {
        return new Document("ts", ts)
                .append("action", action)
                .append("type", type)
                .append("target", target)
                .append("targetName", targetName)
                .append("ip", ip)
                .append("reason", reason)
                .append("issuer", issuer);
    }

    public static BanLogEntry fromDocument(Document d) {
        BanLogEntry e = new BanLogEntry();
        e.ts = d.get("ts") instanceof Number n ? n.longValue() : 0L;
        e.action = d.getString("action");
        e.type = d.getString("type");
        e.target = d.getString("target");
        e.targetName = d.getString("targetName");
        e.ip = d.getString("ip");
        e.reason = d.getString("reason");
        e.issuer = d.getString("issuer");
        return e;
    }
}
