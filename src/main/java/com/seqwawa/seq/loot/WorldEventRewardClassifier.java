package com.seqwawa.seq.loot;

import java.util.List;
import java.util.Locale;

/** Conservative vanilla classification; raw names/lore survive when an item is unknown. */
public final class WorldEventRewardClassifier {
    private WorldEventRewardClassifier() {}

    public static String category(String name, List<String> lore, boolean potionItem) {
        return category(name, lore, potionItem, name);
    }

    public static String category(String name, List<String> lore, boolean potionItem, String rawName) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("liquid emerald") || lower.equals("liquid emeralds")) return "liquid_emerald";
        if (lower.equals("emerald") || lower.equals("emeralds")) return "emerald";
        if (lower.equals("emerald block") || lower.equals("emerald blocks")) return "emerald_block";
        boolean levelRange = lore.stream().anyMatch(line -> line.matches(".*\\d+-\\d+ Level Range.*"));
        if (potionItem && lower.startsWith("unidentified ")) return levelRange ? "unidentified_box" : "other";
        if (lore.stream().anyMatch(line -> line.matches("(?i).*crafting ingredient.*"))) return "ingredient";
        // Modern tooltips render the ingredient badge as glyphs, which normalization removes.
        // Require both structural lines so descriptions mentioning ingredients do not match.
        boolean craftingLevel = lore.stream().anyMatch(line -> line.matches("(?i)[ \\t]*(?:[0-9]+[ \\t]+)?Crafting Level[ \\t]*"));
        boolean recipeUsage = lore.stream().anyMatch(line -> line.matches("(?i)[ \\t]*Can be used in recipes for[ \\t]*"));
        if (craftingLevel && recipeUsage) return "ingredient";
        // Named unidentified gear uses this prefix in Wynncraft's name, even without an English lore marker.
        // GearBox uses the same icon but was handled above by potion backing + generic name + level range.
        if (rawName.contains("\uE008󐀂")) return "unidentified_item";
        if (lore.stream().anyMatch(line -> line.matches("(?i).*unidentified.*"))
                || lower.startsWith("unidentified ")) return "unidentified_item";
        if (lower.contains("potion")) return "potion";
        return "other";
    }
}
