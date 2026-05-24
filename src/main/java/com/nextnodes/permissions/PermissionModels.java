package com.nextnodes.permissions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PermissionModels {
    private PermissionModels() {
    }

    public static final class PermissionData {
        public int schemaVersion = 1;
        public String defaultRank = "default";
        public Map<String, Rank> ranks = new LinkedHashMap<>();
        public Map<String, UserEntry> users = new LinkedHashMap<>();
        public TabSettings tabSettings = new TabSettings();

        public void ensureDefaults() {
            if (this.ranks == null) {
                this.ranks = new LinkedHashMap<>();
            }
            if (this.users == null) {
                this.users = new LinkedHashMap<>();
            }
            if (this.defaultRank == null || this.defaultRank.isBlank()) {
                this.defaultRank = "default";
            }
            if (this.tabSettings == null) {
                this.tabSettings = new TabSettings();
            }
            this.defaultRank = normalizeName(this.defaultRank);
            this.ranks.computeIfAbsent(this.defaultRank, key -> {
                Rank rank = new Rank();
                rank.name = key;
                rank.displayName = "Default";
                rank.weight = 0;
                return rank;
            });
            this.ranks.values().forEach(Rank::sanitize);
            this.users.values().forEach(UserEntry::sanitize);
        }
    }

    public static final class TabSettings {
        public boolean showPing = true;
        public boolean showHead = true;
    }

    public static final class Rank {
        public String name = "";
        public String displayName = "";
        public String prefix = "";
        public int weight = 0;
        public List<String> parents = new ArrayList<>();
        public List<PermissionRule> permissions = new ArrayList<>();
        public Map<String, String> meta = new LinkedHashMap<>();

        public void sanitize() {
            this.name = normalizeName(this.name);
            if (this.displayName == null) {
                this.displayName = this.name;
            }
            if (this.prefix == null) {
                this.prefix = "";
            }
            if (this.parents == null) {
                this.parents = new ArrayList<>();
            }
            this.parents.replaceAll(PermissionModels::normalizeName);
            if (this.permissions == null) {
                this.permissions = new ArrayList<>();
            }
            this.permissions.forEach(PermissionRule::sanitize);
            if (this.meta == null) {
                this.meta = new LinkedHashMap<>();
            }
        }
    }

    public static final class UserEntry {
        public String uuid = "";
        public String name = "";
        public String primaryRank = "";
        public List<String> ranks = new ArrayList<>();
        public List<PermissionRule> permissions = new ArrayList<>();
        public Map<String, String> meta = new LinkedHashMap<>();
        public long lastSeen = 0L;
        public boolean online = false;

        public void sanitize() {
            if (this.uuid == null) {
                this.uuid = "";
            }
            if (this.name == null) {
                this.name = "";
            }
            this.primaryRank = normalizeName(this.primaryRank);
            if (this.ranks == null) {
                this.ranks = new ArrayList<>();
            }
            this.ranks.replaceAll(PermissionModels::normalizeName);
            if (this.permissions == null) {
                this.permissions = new ArrayList<>();
            }
            this.permissions.forEach(PermissionRule::sanitize);
            if (this.meta == null) {
                this.meta = new LinkedHashMap<>();
            }
        }
    }

    public static final class PermissionRule {
        public String node = "";
        public boolean value = true;
        public String mode = "literal";
        public Long expiresAt = null;
        public Map<String, String> contexts = new LinkedHashMap<>();

        public void sanitize() {
            if (this.node == null) {
                this.node = "";
            }
            this.node = this.node.trim().toLowerCase();
            if (this.mode == null || this.mode.isBlank()) {
                this.mode = inferMode(this.node);
            }
            this.mode = this.mode.trim().toLowerCase();
            if (this.contexts == null) {
                this.contexts = new LinkedHashMap<>();
            }
            Map<String, String> normalized = new LinkedHashMap<>();
            this.contexts.forEach((key, value) -> {
                if (key != null && value != null && !key.isBlank()) {
                    normalized.put(key.trim().toLowerCase(), value.trim().toLowerCase());
                }
            });
            this.contexts = normalized;
        }
    }

    public static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    public static String inferMode(String node) {
        if (node == null) {
            return "literal";
        }
        if (node.startsWith("regex:")) {
            return "regex";
        }
        if (node.equals("*") || node.endsWith(".*")) {
            return "wildcard";
        }
        return "literal";
    }
}
