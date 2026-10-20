package com.seqwawa.seq.loot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldEventLootCaptureTest {
    private WorldEventLootCapture opened() {
        WorldEventLootCapture capture = new WorldEventLootCapture();
        capture.completed("The Test Event", "WC1", 1000);
        capture.opened(5, "Event Rewards", true, 2000);
        return capture;
    }

    private WorldEventLootSample.Reward reward(int slot, int quantity) {
        return new WorldEventLootSample.Reward(slot, "ingredient", "Event ingredient", quantity, List.of());
    }

    @Test
    void snapshotsAt250msWithoutWaitingForInteractionAndDeduplicatesReopening() {
        var capture = opened();
        capture.contents(5, List.of(reward(0, 3), reward(1, 2)), 2001);
        assertNull(capture.snapshot(2249));
        var sample = capture.snapshot(2250);
        assertTrue(sample.complete());
        assertEquals(5, sample.rewards().stream().mapToInt(WorldEventLootSample.Reward::quantity).sum());
        assertNull(capture.snapshot(2251));
        capture.opened(6, "Event Rewards", true, 2300);
        capture.contents(6, List.of(reward(0, 99)), 2301);
        assertNull(capture.snapshot(2550));
        capture.completed("The Test Event", "WC1", 6001);
        capture.opened(7, "Event Rewards", true, 6002);
        assertNull(capture.snapshot(6252));
        capture.completed("The Test Event", "WC2", 20_000);
        capture.opened(8, "Event Rewards", true, 20_001);
        capture.contents(8, List.of(), 20_002);
        assertNotEquals(sample.sampleId(), capture.snapshot(20_251).sampleId());
    }

    @Test
    void zeroSpecificDropsAndEmptyChestsRemainCompleteSamples() {
        var capture = opened();
        capture.contents(5, List.of(), 2001);
        var sample = capture.snapshot(2250);
        assertTrue(sample.complete());
        assertTrue(sample.rewards().isEmpty());
    }

    @Test
    void recentPopulationUpdatesAndReplacementsAreAcceptedUntilTheCutoff() {
        var capture = opened();
        capture.contents(5, List.of(reward(0, 5)), 2001);
        capture.slot(5, 0, null, 2100);
        capture.slot(5, 1, reward(1, 2), 2248);
        capture.slot(5, 1, reward(1, 3), 2249);
        var sample = capture.snapshot(2250);
        assertTrue(sample.complete());
        assertEquals(List.of(reward(1, 3)), sample.rewards());
    }

    @Test
    void delayedTickCannotIncludeFullContentOrSlotUpdatesAfter250ms() {
        var capture = opened();
        capture.contents(5, List.of(reward(0, 2)), 2249);
        capture.slot(5, 0, reward(0, 99), 2250);
        capture.contents(5, List.of(reward(1, 99)), 2251);
        var sample = capture.snapshot(3000);
        assertTrue(sample.complete());
        assertEquals(List.of(reward(0, 2)), sample.rewards());
        capture.slot(5, 0, reward(0, 100), 3001);
        assertNull(capture.snapshot(4000));
    }

    @Test
    void earlyClosePreservesCachedContentsAndRejectsReusedContainerPackets() {
        var capture = opened();
        capture.contents(5, List.of(reward(0, 3)), 2001);
        capture.closed(5);
        assertFalse(capture.accepts(5));
        assertNull(capture.snapshot(2100));
        capture.opened(5, "Other inventory", true, 2150);
        capture.contents(5, List.of(reward(0, 99)), 2151);
        capture.slot(5, 0, null, 2152);
        var sample = capture.snapshot(2250);
        assertTrue(sample.complete());
        assertEquals(List.of(reward(0, 3)), sample.rewards());
    }

    @Test
    void replacementOpenStopsOldUpdatesWithoutEarlyCaptureEvenWithSameContainerId() {
        var capture = opened();
        capture.contents(5, List.of(reward(0, 3)), 2001);
        capture.closed(999); // An unrelated close must not stop this observation.
        capture.slot(5, 0, reward(0, 4), 2050);
        capture.opened(5, "Event Rewards", true, 2100);
        assertNull(capture.snapshot(2100));
        capture.contents(5, List.of(reward(0, 99)), 2101);
        assertEquals(List.of(reward(0, 4)), capture.snapshot(2250).rewards());
    }

    @Test
    void missingOrLateBaselinesAreStillIncomplete() {
        var capture = opened();
        capture.slot(5, 0, reward(0, 1), 2001);
        capture.contents(5, List.of(reward(0, 99)), 2250);
        var sample = capture.snapshot(2250);
        assertFalse(sample.complete());
        assertEquals(List.of(reward(0, 1)), sample.rewards());
    }

    @Test
    void rejectsOrdinaryChestsWrongSizeUnattributedAndExpiredRewards() {
        var capture = new WorldEventLootCapture();
        capture.opened(1, "Event Rewards", true, 1000);
        assertFalse(capture.accepts(1));
        capture.completed("Known", null, 2000);
        assertFalse(capture.accepts(-1));
        capture.closed(-1);
        capture.opened(2, "Loot Chest", true, 2001);
        capture.opened(3, "Event Rewards", false, 2002);
        assertEquals(-1, capture.containerId());
        capture.opened(4, "Event Rewards", true, 400_000);
        assertFalse(capture.accepts(4));
        assertNull(capture.snapshot(400_250));
    }

    @Test
    void unrelatedCloseBeforeOpeningDoesNotDisableTheObservation() {
        var capture = new WorldEventLootCapture();
        capture.completed("Known", null, 1000);
        capture.closed(-1);
        capture.opened(5, "Event Rewards", true, 2000);
        assertTrue(capture.accepts(5));
        capture.contents(5, List.of(reward(0, 1)), 2001);
        assertTrue(capture.snapshot(2250).complete());
    }

    @Test
    void fullChestAndUnknownItemsPreserveAllSlotsAndResetDiscardsPendingCapture() {
        var capture = opened();
        var rewards = java.util.stream.IntStream.range(0, 27).mapToObj(slot -> new WorldEventLootSample.Reward(
                slot, "other", "Unknown item", 1, List.of("Original lore"))).toList();
        capture.contents(5, rewards, 2001);
        capture.slot(5, 27, reward(27, 99), 2002); // Appended player inventory.
        var sample = capture.snapshot(2250);
        assertTrue(sample.complete());
        assertEquals(27, sample.rewards().size());
        assertEquals("other", sample.rewards().getFirst().category());
        capture = opened();
        capture.contents(5, rewards, 2001);
        capture.reset();
        assertNull(capture.snapshot(2250));
    }
}
