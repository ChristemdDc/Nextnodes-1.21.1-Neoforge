package com.nextnodes.permissions.ban;

/** Read view of a player's known IPs (from player_ips). */
public final class PlayerIpEntry {
    public String uuid;
    public String name;
    public String lastIp;

    public PlayerIpEntry(String uuid, String name, String lastIp) {
        this.uuid = uuid; this.name = name; this.lastIp = lastIp;
    }
}
