package com.seqwawa.seq.loot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Game-thread state machine. Contents are replacements, never sums or per-slot maxima. */
public final class WorldEventLootCapture {
    public static final int CAPACITY = 27;
    private static final long REWARD_WINDOW_MS = 5 * 60_000;
    private UUID occurrence;
    private String eventName;
    private String world;
    private long completedAt;
    private int container = -1;
    private long openedAt;
    private long changedAt;
    private boolean baseline;
    private boolean removalBeforeInteraction;
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
        if (container != -1 || occurrence == null || eventName == null) return;
        if (now - completedAt > REWARD_WINDOW_MS || now < completedAt) {
            eventName = null;
            return;
        }
        if (!threeRows || !"Event Rewards".equals(title)) return;
        container = id;
        openedAt = now;
        changedAt = now;
    }

    public boolean accepts(int id) {
        return container == id && eventName != null;
    }

    public void contents(int id, List<WorldEventLootSample.Reward> contents, long now) {
        if (!accepts(id)) return;
        WorldEventLootSample.Reward[] next = new WorldEventLootSample.Reward[CAPACITY];
        for (WorldEventLootSample.Reward reward : contents) next[reward.slot()] = reward;
        for (int slot = 0; slot < CAPACITY; slot++) replace(slot, next[slot], now);
        baseline = true;
    }

    public void slot(int id, int slot, WorldEventLootSample.Reward reward, long now) {
        if (!accepts(id) || slot < 0 || slot >= CAPACITY) return;
        replace(slot, reward, now);
    }

    private void replace(int slot, WorldEventLootSample.Reward reward, long now) {
        WorldEventLootSample.Reward old = slots[slot];
        if (Objects.equals(old, reward)) return;
        if (old != null && (reward == null || !old.name().equals(reward.name())
                || old.quantity() > reward.quantity())) removalBeforeInteraction = true;
        slots[slot] = reward;
        changedAt = now;
    }

    /** Freeze before any client click (including player inventory), or on close. */
    public WorldEventLootSample freeze(int id, long now) {
        if (!accepts(id)) return null;
        List<WorldEventLootSample.Reward> rewards = new ArrayList<>();
        for (WorldEventLootSample.Reward reward : slots) if (reward != null) rewards.add(reward);
        // The server has no generation-finished signal. This is explicitly a quiet-snapshot heuristic.
        boolean complete = baseline && !removalBeforeInteraction && now - openedAt >= 1500
                && now - changedAt >= 750;
        WorldEventLootSample sample = new WorldEventLootSample(1, occurrence, eventName, world,
                Instant.ofEpochMilli(completedAt), complete, CAPACITY, null, null, List.copyOf(rewards));
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
        removalBeforeInteraction = false;
        java.util.Arrays.fill(slots, null);
    }
}
