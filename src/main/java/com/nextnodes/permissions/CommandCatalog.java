package com.nextnodes.permissions;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public final class CommandCatalog {
    private static final Set<String> VANILLA_COMMANDS = vanillaCommands();
    private final CopyOnWriteArrayList<CommandInfo> commands = new CopyOnWriteArrayList<>();

    public void refresh(CommandDispatcher<CommandSourceStack> dispatcher) {
        List<CommandInfo> next = new ArrayList<>();
        for (CommandNode<CommandSourceStack> child : dispatcher.getRoot().getChildren()) {
            if (child instanceof LiteralCommandNode<CommandSourceStack>) {
                String name = child.getName().toLowerCase(Locale.ROOT);
                Set<String> paths = new LinkedHashSet<>();
                collectPaths(child, name, paths, 0);
                next.add(new CommandInfo(name, commandSource(name), "command." + name, "minecraft.command." + name, List.copyOf(paths)));
            }
        }
        next.sort(Comparator.comparing(CommandInfo::name));
        this.commands.clear();
        this.commands.addAll(next);
    }

    public List<CommandInfo> snapshot() {
        return List.copyOf(this.commands);
    }

    private static void collectPaths(CommandNode<CommandSourceStack> node, String path, Set<String> paths, int depth) {
        if (depth > 4) {
            return;
        }
        paths.add(path);
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            if (child instanceof LiteralCommandNode<CommandSourceStack>) {
                collectPaths(child, path + "." + child.getName().toLowerCase(Locale.ROOT), paths, depth + 1);
            }
        }
    }

    private static String commandSource(String name) {
        return VANILLA_COMMANDS.contains(name) ? "Minecraft Vanilla" : readableName(name);
    }

    private static String readableName(String name) {
        String[] parts = name.replace('-', '_').split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.isEmpty() ? name : builder.toString();
    }

    private static Set<String> vanillaCommands() {
        return new HashSet<>(Set.of(
                "advancement", "attribute", "ban", "ban-ip", "banlist", "bossbar", "clear", "clone", "damage",
                "data", "datapack", "debug", "defaultgamemode", "deop", "difficulty", "effect", "enchant",
                "execute", "experience", "fill", "fillbiome", "forceload", "function", "gamemode", "gamerule",
                "give", "help", "item", "jfr", "kick", "kill", "list", "locate", "loot", "me", "msg", "op",
                "pardon", "pardon-ip", "particle", "perf", "place", "playsound", "random", "recipe", "reload",
                "return", "ride", "save-all", "save-off", "save-on", "say", "schedule", "scoreboard", "seed",
                "setblock", "setidletimeout", "setworldspawn", "spawnpoint", "spectate", "spreadplayers", "stop",
                "stopsound", "summon", "tag", "team", "teammsg", "teleport", "tell", "tellraw", "tick", "time",
                "title", "tm", "tp", "transfer", "trigger", "w", "weather", "whitelist", "worldborder", "xp"
        ));
    }

    public record CommandInfo(String name, String source, String permissionNode, String vanillaPermissionNode, List<String> paths) {
    }
}
