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
    void snapshotsInitialContentsAndDeduplicatesReopeningAndRepeatedCompletion() {
        var capture = opened();
        capture.contents(5, List.of(reward(0, 3), reward(1, 2)), 2001);
        var sample = capture.freeze(5, 4000);
        assertTrue(sample.complete());
        assertEquals(5, sample.rewards().stream().mapToInt(WorldEventLootSample.Reward::quantity).sum());
        capture.opened(6, "Event Rewards", true, 4500);
        assertNull(capture.freeze(6, 6000));
        capture.completed("The Test Event", "WC1", 6001);
        capture.opened(7, "Event Rewards", true, 6002);
        assertNull(capture.freeze(7, 8000));
        capture.completed("The Test Event", "WC2", 20_000);
        capture.opened(8, "Event Rewards", true, 20_001);
        capture.contents(8, List.of(), 20_002);
        assertNotEquals(sample.sampleId(), capture.freeze(8, 23_000).sampleId());
    }

    @Test
    void zeroSpecificDropsAndEmptyChestsRemainSamples() {
        var capture = opened();
        capture.contents(5, List.of(), 2001);
        var sample = capture.freeze(5, 4000);
        assertTrue(sample.complete());
        assertTrue(sample.rewards().isEmpty());
    }

    @Test
    void initialEmptyThenSlotPopulationUsesLatestSnapshot() {
        var capture = opened();
        capture.contents(5, List.of(), 2001);
        capture.slot(5, 1, reward(1, 2), 3000);
        capture.slot(5, 1, reward(1, 3), 3100);
        var sample = capture.freeze(5, 4000);
        assertTrue(sample.complete());
        assertEquals(List.of(reward(1, 3)), sample.rewards());
    }

    @Test
    void fastLootingMissingBaselineAndRecentUpdatesAreIncomplete() {
        var capture = opened();
        capture.contents(5, List.of(reward(0, 1)), 2001);
        assertFalse(capture.freeze(5, 2100).complete());
        capture = opened();
        capture.slot(5, 0, reward(0, 1), 2001);
        assertFalse(capture.freeze(5, 4000).complete());
        capture = opened();
        capture.contents(5, List.of(reward(0, 1)), 3900);
        assertFalse(capture.freeze(5, 4000).complete());
    }

    @Test
    void movingItemsNeverUnionsOldAndNewSlotsAndIsIncomplete() {
        var capture = opened();
        capture.contents(5, List.of(reward(0, 3)), 2001);
        capture.slot(5, 0, null, 2100);
        capture.slot(5, 1, reward(1, 3), 2200);
        capture.slot(5, 27, reward(27, 99), 2201); // Appended player inventory.
        var sample = capture.freeze(5, 4000);
        assertFalse(sample.complete());
        assertEquals(List.of(reward(1, 3)), sample.rewards());
    }

    @Test
    void rejectsOrdinaryChestsWrongSizeUnattributedAndExpiredRewards() {
        var capture = new WorldEventLootCapture();
        capture.opened(1, "Event Rewards", true, 1000);
        assertFalse(capture.accepts(1));
        capture.completed("Known", null, 2000);
        capture.opened(2, "Loot Chest", true, 2001);
        capture.opened(3, "Event Rewards", false, 2002);
        assertEquals(-1, capture.containerId());
        capture.opened(4, "Event Rewards", true, 400_000);
        assertFalse(capture.accepts(4));
    }

    @Test
    void fullChestAndUnknownItemsPreserveAllSlots() {
        var capture = opened();
        var rewards = java.util.stream.IntStream.range(0, 27).mapToObj(this::unknownReward).toList();
        capture.contents(5, rewards, 2001);
        var sample = capture.freeze(5, 4000);
        assertTrue(sample.complete());
        assertEquals(27, sample.rewards().size());
        assertEquals("other", sample.rewards().getFirst().category());
        capture.reset();
        assertNull(capture.freeze(5, 5000));
    }

    private WorldEventLootSample.Reward unknownReward(int slot) {
        return new WorldEventLootSample.Reward(slot, "other", "Unknown item", 1, List.of("Original lore"));
    }
}
