package com.seqwawa.seq.loot;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorldEventLootOutboxTest {
    @Test
    void sameAccountRetainsOriginalPayloadAcrossTransfersAndRetries() {
        var outbox = new WorldEventLootOutbox();
        UUID account = UUID.randomUUID();
        assertTrue(outbox.update(account, true));
        var sample = sample();
        outbox.add(sample);
        long generation = outbox.generation();
        // Scope does not own the queue: UNKNOWN/MAIN ticks use the same account/preference.
        assertFalse(outbox.update(account, true));
        assertSame(sample, outbox.first());
        assertEquals(generation, outbox.generation());
        assertTrue(outbox.matchesAccount(account.toString()));
        assertFalse(outbox.matchesAccount(UUID.randomUUID().toString()));
        outbox.remove(sample);
        assertNull(outbox.first());
    }

    @Test
    void accountChangesAndDisablingInvalidateQueueAndOldCallbacks() {
        var outbox = new WorldEventLootOutbox();
        UUID account = UUID.randomUUID();
        outbox.update(account, true);
        outbox.add(sample());
        long generation = outbox.generation();
        outbox.update(UUID.randomUUID(), true);
        assertNull(outbox.first());
        assertNotEquals(generation, outbox.generation());
        outbox.add(sample());
        outbox.update(account, false);
        assertNull(outbox.first());
        assertFalse(outbox.matchesAccount(account.toString()));
    }

    @Test
    void queueIsBounded() {
        var outbox = new WorldEventLootOutbox();
        outbox.update(UUID.randomUUID(), true);
        var first = sample();
        outbox.add(first);
        for (int i = 0; i < 60; i++) outbox.add(sample());
        assertNotSame(first, outbox.first());
        for (int i = 0; i < 60; i++) outbox.remove(outbox.first());
        assertNull(outbox.first());
    }

    private WorldEventLootSample sample() {
        return new WorldEventLootSample(1, UUID.randomUUID(), "Event", "WC1", Instant.now(),
                true, 27, null, null, List.of());
    }
}
