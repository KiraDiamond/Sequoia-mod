package com.seqwawa.seq.model;

import java.util.List;
import java.util.Locale;

public enum SeqTier {
    BRONZE("Bronze"),
    SILVER("Silver"),
    GOLD("Gold"),
    PLATINUM("Platinum"),
    DIAMOND("Diamond"),
    OBSIDIAN("Obsidian"),
    MYTHRIL("Mythril");

    private static final List<SeqTier> ORDERED = List.of(values());

    private final String label;
    SeqTier(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static List<SeqTier> ordered() {
        return ORDERED;
    }

    public static SeqTier fromKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
