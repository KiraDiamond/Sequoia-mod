package com.seqwawa.seq.model;

import java.util.Locale;

public enum SeqBadgeTier {
    SINGLE("single"),
    BRONZE("bronze"),
    SILVER("silver"),
    GOLD("gold"),
    DIAMOND("diamond");

    private final String commandName;

    SeqBadgeTier(String commandName) {
        this.commandName = commandName;
    }

    public String commandName() {
        return commandName;
    }

    public String apiName() {
        return name();
    }

    public static SeqBadgeTier parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return SeqBadgeTier.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static SeqBadgeTier highest(SeqBadgeTier left, SeqBadgeTier right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.ordinal() >= right.ordinal() ? left : right;
    }
}
