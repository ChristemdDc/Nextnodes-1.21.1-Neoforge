package com.nextnodes.permissions;

import com.nextnodes.permissions.PermissionModels.PermissionData;
import com.nextnodes.permissions.PermissionModels.PermissionRule;
import com.nextnodes.permissions.PermissionModels.Rank;
import com.nextnodes.permissions.PermissionModels.UserEntry;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

public final class PermissionResolver {
    private final PermissionStore store;
    private final ConcurrentHashMap<String, Boolean> booleanCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Pattern> regexCache = new ConcurrentHashMap<>();

    public PermissionResolver(PermissionStore store) {
        this.store = store;
        this.store.addChangeListener(this::invalidate);
    }

    public void invalidate() {
        this.booleanCache.clear();
        this.regexCache.clear();
    }

    public Boolean resolveBoolean(UUID uuid, ServerPlayer player, String node, PermissionDynamicContext<?>... dynamicContexts) {
        Map<String, String> contexts = contexts(player, dynamicContexts);
        String normalizedNode = normalizeNode(node);
        String cacheKey = uuid + "|" + normalizedNode + "|" + stableContextKey(contexts);
        return this.booleanCache.computeIfAbsent(cacheKey, key -> resolveBooleanUncached(uuid, normalizedNode, contexts));
    }

    public String resolveMeta(UUID uuid, String key) {
        PermissionData data = this.store.snapshot();
        UserEntry user = data.users.get(uuid.toString());
        if (user == null) {
            return null;
        }
        if (user.meta.containsKey(key)) {
            return user.meta.get(key);
        }
        for (Rank rank : rankOrder(data, user)) {
            String value = rank.meta.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public String resolvePrefix(UUID uuid) {
        PermissionData data = this.store.snapshot();
        UserEntry user = data.users.get(uuid.toString());
        if (user == null) {
            Rank defaultRank = data.ranks.get(data.defaultRank);
            return defaultRank == null ? "" : defaultRank.prefix;
        }
        for (Rank rank : rankOrder(data, user)) {
            if (rank.prefix != null && !rank.prefix.isBlank()) {
                return rank.prefix;
            }
        }
        return "";
    }

    public String resolveSuffix(UUID uuid) {
        PermissionData data = this.store.snapshot();
        UserEntry user = data.users.get(uuid.toString());
        if (user == null) {
            Rank defaultRank = data.ranks.get(data.defaultRank);
            return defaultRank == null ? "" : defaultRank.suffix;
        }
        for (Rank rank : rankOrder(data, user)) {
            if (rank.suffix != null && !rank.suffix.isBlank()) {
                return rank.suffix;
            }
        }
        return "";
    }

    public String resolveTag(UUID uuid) {
        PermissionData data = this.store.snapshot();
        UserEntry user = data.users.get(uuid.toString());
        return user == null || user.tag == null ? "" : user.tag;
    }

    public int resolveWeight(UUID uuid) {
        PermissionData data = this.store.snapshot();
        UserEntry user = data.users.get(uuid.toString());
        if (user == null) {
            Rank defaultRank = data.ranks.get(data.defaultRank);
            return defaultRank == null ? 0 : defaultRank.weight;
        }
        List<Rank> order = rankOrder(data, user);
        // Sort by the weight of the rank that provides the DISPLAYED prefix, so the TAB order matches
        // the rank shown next to the name. Mirrors resolvePrefix(): a player whose visible prefix is
        // [ADMIN] — even if it is inherited from a parent or comes from a non-primary rank — sorts at
        // admin's level instead of at the weight of some lower root rank.
        for (Rank rank : order) {
            if (rank.prefix != null && !rank.prefix.isBlank()) {
                return rank.weight;
            }
        }
        return order.isEmpty() ? 0 : order.get(0).weight;
    }

    private Boolean resolveBooleanUncached(UUID uuid, String node, Map<String, String> contexts) {
        PermissionData data = this.store.snapshot();
        UserEntry user = data.users.get(uuid.toString());
        if (user == null) {
            return null;
        }
        Boolean direct = resolveFromRules(user.permissions, node, contexts);
        if (direct != null) {
            return direct;
        }
        for (Rank rank : rankOrder(data, user)) {
            Boolean result = resolveFromRules(rank.permissions, node, contexts);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private Boolean resolveFromRules(List<PermissionRule> rules, String node, Map<String, String> contexts) {
        Boolean exact = findRule(rules, node, contexts, "literal");
        if (exact != null) {
            return exact;
        }
        Boolean wildcard = findRule(rules, node, contexts, "wildcard");
        if (wildcard != null) {
            return wildcard;
        }
        return findRule(rules, node, contexts, "regex");
    }

    private Boolean findRule(List<PermissionRule> rules, String node, Map<String, String> contexts, String mode) {
        long now = System.currentTimeMillis();
        for (int i = rules.size() - 1; i >= 0; i--) {
            PermissionRule rule = rules.get(i);
            if (!mode.equals(rule.mode) || rule.node.isBlank()) {
                continue;
            }
            if (rule.expiresAt != null && rule.expiresAt > 0L && rule.expiresAt <= now) {
                continue;
            }
            if (!contextsMatch(rule.contexts, contexts)) {
                continue;
            }
            if (matches(rule, node)) {
                return rule.value;
            }
        }
        return null;
    }

    private boolean matches(PermissionRule rule, String node) {
        return switch (rule.mode) {
            case "wildcard" -> wildcardMatches(rule.node, node);
            case "regex" -> regexMatches(rule.node, node);
            default -> rule.node.equals(node);
        };
    }

    private static boolean wildcardMatches(String pattern, String node) {
        if (pattern.equals("*")) {
            return true;
        }
        if (!pattern.endsWith(".*")) {
            return pattern.equals(node);
        }
        String prefix = pattern.substring(0, pattern.length() - 2);
        return node.startsWith(prefix + ".");
    }

    private boolean regexMatches(String pattern, String node) {
        Pattern compiled = this.regexCache.computeIfAbsent(pattern, key -> {
            String regex = key.startsWith("regex:") ? key.substring("regex:".length()) : key;
            try {
                return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException ignored) {
                return Pattern.compile("^$");
            }
        });
        return compiled.matcher(node).matches();
    }

    private static boolean contextsMatch(Map<String, String> ruleContexts, Map<String, String> queryContexts) {
        for (Map.Entry<String, String> entry : ruleContexts.entrySet()) {
            if (!entry.getValue().equals(queryContexts.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static List<Rank> rankOrder(PermissionData data, UserEntry user) {
        List<Rank> roots = new ArrayList<>();

        // primaryRank earns stable-sort priority only when it is NOT the default rank.
        // Many users have primaryRank="default" even when they hold non-default ranks,
        // so giving "default" an unconditional head-start would hide higher-weight ranks.
        boolean primaryIsDefault = user.primaryRank.isBlank() || user.primaryRank.equals(data.defaultRank);
        if (!primaryIsDefault) {
            Rank primary = data.ranks.get(user.primaryRank);
            if (primary != null) {
                roots.add(primary);
            }
        }

        // Collect remaining non-default ranks from the user's rank list.
        for (String rankName : user.ranks) {
            if (rankName.equals(data.defaultRank)) continue;
            Rank rank = data.ranks.get(rankName);
            if (rank != null && roots.stream().noneMatch(existing -> existing.name.equals(rank.name))) {
                roots.add(rank);
            }
        }

        // Sort non-default roots by weight descending (primaryRank is first, wins ties).
        roots.sort(Comparator.comparingInt((Rank rank) -> rank.weight).reversed());

        // Always append the default rank at the end so its permissions / prefix act as a fallback.
        Rank defaultRank = data.ranks.get(data.defaultRank);
        if (defaultRank != null && roots.stream().noneMatch(r -> r.name.equals(defaultRank.name))) {
            roots.add(defaultRank);
        }

        List<Rank> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Rank rank : roots) {
            addRankWithParents(data, rank, ordered, seen);
        }
        return ordered;
    }

    private static void addRankWithParents(PermissionData data, Rank rank, List<Rank> ordered, Set<String> seen) {
        if (!seen.add(rank.name)) {
            return;
        }
        ordered.add(rank);
        List<Rank> parents = new ArrayList<>();
        for (String parentName : rank.parents) {
            Rank parent = data.ranks.get(parentName);
            if (parent != null) {
                parents.add(parent);
            }
        }
        parents.sort(Comparator.comparingInt((Rank parent) -> parent.weight).reversed());
        for (Rank parent : parents) {
            addRankWithParents(data, parent, ordered, seen);
        }
    }

    private static Map<String, String> contexts(ServerPlayer player, PermissionDynamicContext<?>... dynamicContexts) {
        Map<String, String> contexts = new LinkedHashMap<>();
        if (player != null) {
            contexts.put("world", player.serverLevel().dimension().location().toString().toLowerCase(Locale.ROOT));
            contexts.put("dimension", player.serverLevel().dimension().location().toString().toLowerCase(Locale.ROOT));
        }
        for (PermissionDynamicContext<?> dynamicContext : dynamicContexts) {
            contexts.put(dynamicContext.getDynamic().name().toLowerCase(Locale.ROOT), dynamicContext.getSerializedValue().toLowerCase(Locale.ROOT));
        }
        return contexts;
    }

    private static String normalizeNode(String node) {
        return node == null ? "" : node.trim().toLowerCase(Locale.ROOT);
    }

    private static String stableContextKey(Map<String, String> contexts) {
        if (contexts.isEmpty()) return "";
        return contexts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }
}
