package com.seqwawa.seq.loot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Game-thread state machine. Contents are replacements, never sums or per-slot maxima. */
public final class WorldEventLootCapture {
    public static final int CAPACITY = 27;
    private static final long SNAPSHOT_DELAY_MS = 250;
    private static final long REWARD_WINDOW_MS = 5 * 60_000;
    private UUID occurrence;
    private String eventName;
    private String world;
    private long completedAt;
    private int container = -1;
    private long openedAt;
    private boolean baseline;
    private boolean closed;
    private final WorldEventLootSample.Reward[] slots = new WorldEventLootSample.Reward[CAPACITY];

    public void completed(String name, String worldName, long now) {
        // Duplicate completion packets must not re-arm a consumed reward.
        if (occurrence != null && now - completedAt < 10_000) return;
        reset();
        if (name == null || name.isBlank()) return;
        occurrence = UUID.randomUUID();
        eventName = name;
        world = worldName;
        completedAt = now;
    }

    public void opened(int id, String title, boolean threeRows, long now) {
        if (container != -1) {
            closed = true; // A replacement screen must not overwrite the pending chest, even with a reused ID.
            return;
        }
        if (occurrence == null || eventName == null) return;
        if (now - completedAt > REWARD_WINDOW_MS || now < completedAt) {
            eventName = null;
            return;
        }
        if (!threeRows || !"Event Rewards".equals(title)) return;
        container = id;
        openedAt = now;
        closed = false;
    }

    public boolean accepts(int id) {
        return container >= 0 && container == id && eventName != null && !closed;
    }

    public void contents(int id, List<WorldEventLootSample.Reward> contents, long now) {
        if (!acceptsUpdates(id, now)) return;
        WorldEventLootSample.Reward[] next = new WorldEventLootSample.Reward[CAPACITY];
        for (WorldEventLootSample.Reward reward : contents) next[reward.slot()] = reward;
        System.arraycopy(next, 0, slots, 0, CAPACITY);
        baseline = true;
    }

    public void slot(int id, int slot, WorldEventLootSample.Reward reward, long now) {
        if (!acceptsUpdates(id, now) || slot < 0 || slot >= CAPACITY) return;
        slots[slot] = reward;
    }

    private boolean acceptsUpdates(int id, long now) {
        return accepts(id) && now >= openedAt && now - openedAt < SNAPSHOT_DELAY_MS;
    }

    /** Closing stops packet updates, but preserves the cached contents until the original deadline. */
    public void closed(int id) {
        if (container >= 0 && container == id) closed = true;
    }

    /** Freeze the 250 ms observation once; late ticks cannot include packets after the cutoff. */
    public WorldEventLootSample snapshot(long now) {
        if (container < 0 || eventName == null || now - openedAt < SNAPSHOT_DELAY_MS) return null;
        List<WorldEventLootSample.Reward> rewards = new ArrayList<>();
        for (WorldEventLootSample.Reward reward : slots) if (reward != null) rewards.add(reward);
        WorldEventLootSample sample = new WorldEventLootSample(1, occurrence, eventName, world,
                Instant.ofEpochMilli(completedAt), baseline, CAPACITY, null, null, List.copyOf(rewards));
        eventName = null; // Consumed: later packets and reopening cannot create another sample.
        container = -1;
        return sample;
    }

    public int containerId() {
        return container;
    }

    public void reset() {
        occurrence = null;
        eventName = null;
        container = -1;
        baseline = false;
        closed = false;
        java.util.Arrays.fill(slots, null);
    }
}
