package com.nextnodes.permissions;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

public final class PrefixFormatter {
    private PrefixFormatter() {
    }

    public static Component prefixedName(String prefix, Component name) {
        MutableComponent result = Component.empty();
        Component prefixComponent = format(prefix);
        if (!prefixComponent.getString().isBlank()) {
            result.append(prefixComponent);
        }
        result.append(name.copy().withStyle(ChatFormatting.RESET));
        return result;
    }

    public static Component composeName(String prefix, String name, String suffix, String tag, String label) {
        MutableComponent result = Component.empty();
        Component prefixComponent = format(prefix);
        if (!prefixComponent.getString().isBlank()) {
            result.append(prefixComponent);
        }
        result.append(Component.literal(name).withStyle(ChatFormatting.RESET));
        Component suffixComponent = format(suffix);
        if (!suffixComponent.getString().isBlank()) {
            result.append(Component.literal(" "));
            result.append(suffixComponent);
        }
        Component tagComponent = format(tag);
        if (!tagComponent.getString().isBlank()) {
            result.append(Component.literal(" "));
            result.append(tagComponent);
        }
        Component labelComponent = format(label);
        if (!labelComponent.getString().isBlank()) {
            result.append(Component.literal(" "));
            result.append(labelComponent);
        }
        return result;
    }

    public static Component format(String text) {
        if (text == null || text.isBlank()) {
            return Component.empty();
        }

        MutableComponent result = Component.empty();
        StringBuilder buffer = new StringBuilder();
        Style style = Style.EMPTY;
        String value = text.replace('§', '&');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '&' && i + 1 < value.length()) {
                if (value.charAt(i + 1) == '#' && i + 7 < value.length()) {
                    String hex = value.substring(i + 2, i + 8);
                    if (isHex(hex)) {
                        append(result, buffer, style);
                        style = style.withColor(TextColor.fromRgb(Integer.parseInt(hex, 16)));
                        i += 7;
                        continue;
                    }
                }
                if (Character.toLowerCase(value.charAt(i + 1)) == 'x' && i + 13 < value.length() && isAmpersandHex(value, i + 2)) {
                    append(result, buffer, style);
                    String hex = "" + value.charAt(i + 3) + value.charAt(i + 5) + value.charAt(i + 7) + value.charAt(i + 9) + value.charAt(i + 11) + value.charAt(i + 13);
                    style = style.withColor(TextColor.fromRgb(Integer.parseInt(hex, 16)));
                    i += 13;
                    continue;
                }
                append(result, buffer, style);
                ChatFormatting formatting = ChatFormatting.getByCode(value.charAt(++i));
                if (formatting == null) {
                    continue;
                }
                if (formatting == ChatFormatting.RESET) {
                    style = Style.EMPTY;
                } else if (formatting.isColor()) {
                    style = style.withColor(formatting);
                } else {
                    style = style.applyFormat(formatting);
                }
                continue;
            }
            buffer.append(character);
        }
        append(result, buffer, style);
        return result;
    }

    private static boolean isHex(String value) {
        if (value.length() != 6) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAmpersandHex(String value, int offset) {
        for (int i = 0; i < 6; i++) {
            int ampersand = offset + i * 2;
            int digit = ampersand + 1;
            if (ampersand >= value.length() || digit >= value.length() || value.charAt(ampersand) != '&' || Character.digit(value.charAt(digit), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static void append(MutableComponent target, StringBuilder buffer, Style style) {
        if (buffer.isEmpty()) {
            return;
        }
        target.append(Component.literal(buffer.toString()).setStyle(style));
        buffer.setLength(0);
    }
}
