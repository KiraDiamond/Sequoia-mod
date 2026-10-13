package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class ServerQueueDetectorTest {
    private static final Component TITLE = Component.literal("Queueing for WC1");
    private static final Component SUBTITLE = Component.literal("You are in position 12");

    @Test
    void requiresBothQueuePhrasesAndAnActiveTitle() {
        assertTrue(ServerQueueDetector.matchesQueueTitle(TITLE, SUBTITLE, 1, true));
        assertFalse(ServerQueueDetector.matchesQueueTitle(TITLE, SUBTITLE, 0, true));
        assertFalse(ServerQueueDetector.matchesQueueTitle(TITLE, SUBTITLE, -1, true));
        assertFalse(ServerQueueDetector.matchesQueueTitle(null, SUBTITLE, 20, true));
        assertFalse(ServerQueueDetector.matchesQueueTitle(TITLE, null, 20, true));
        assertFalse(ServerQueueDetector.matchesQueueTitle(null, null, 0, true));
        assertFalse(ServerQueueDetector.matchesQueueTitle(Component.literal("Welcome"), SUBTITLE, 20, true));
        assertFalse(ServerQueueDetector.matchesQueueTitle(TITLE, Component.literal("Loading"), 20, true));
        assertFalse(ServerQueueDetector.matchesQueueTitle(Component.literal("queueing for WC1"), SUBTITLE, 20, true));
        assertFalse(ServerQueueDetector.matchesQueueTitle(TITLE, Component.literal("you are in position 12"), 20, true));
        assertTrue(ServerQueueDetector.matchesQueueTitle(Component.literal(" Queueing for WC1"), SUBTITLE, 20, true));
    }

    @Test
    void findsQueuePhrasesAfterLiteralColorCodesAndOtherLeadingText() {
        assertTrue(ServerQueueDetector.matchesQueueTitle(
                Component.literal("§6§lQueueing for WC1"),
                Component.literal("§eYou are in position 12"), 20, true));
        assertTrue(ServerQueueDetector.matchesQueueTitle(
                Component.literal("[Server] Queueing for WC1"),
                Component.literal("[Queue] You are in position 12"), 20, true));
    }

    @Test
    void disconnectedOrNonMainServersCannotMatchEvenWithQueueText() {
        assertFalse(ServerQueueDetector.matchesQueueTitle(TITLE, SUBTITLE, 20, false));
        assertTrue(ServerQueueDetector.matchesQueueTitle(TITLE, SUBTITLE, 20, true));
    }

    @Test
    void readsPlainTextAcrossStyledComponentFragments() {
        Component title = Component.literal("Queueing ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("for WC1").withStyle(ChatFormatting.BOLD));
        Component subtitle = Component.literal("You are in ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("position 12"));
        assertTrue(ServerQueueDetector.matchesQueueTitle(title, subtitle, 20, true));
    }

    @Test
    void replacementClearingAndExpiryDoNotRetainQueueState() {
        assertTrue(ServerQueueDetector.matchesQueueTitle(TITLE, SUBTITLE, 20, true));
        assertFalse(ServerQueueDetector.matchesQueueTitle(Component.literal("Entering world"), SUBTITLE, 20, true));
        assertFalse(ServerQueueDetector.matchesQueueTitle(null, null, 0, true));
        assertFalse(ServerQueueDetector.matchesQueueTitle(TITLE, SUBTITLE, 0, true));
    }
}
