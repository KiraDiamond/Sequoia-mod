package com.seqwawa.seq.managers;

import com.seqwawa.seq.network.WynncraftGuildClient;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Confirms in-game guild membership independently of Discord ranks and nickname aliases. */
public final class SequoiaGuildMembership {
    static final long REFRESH_INTERVAL_MS = WynncraftGuildClient.MINIMUM_REFRESH_INTERVAL.toMillis();
    static final long MAX_AGE_MS = Duration.ofMinutes(2).toMillis();
    private static SequoiaGuildMembership instance;

    private record Snapshot(WynncraftGuildClient.GuildRoster roster, long fetchedAtMs) {}

    private final Supplier<CompletableFuture<WynncraftGuildClient.GuildRoster>> fetcher;
    private final LongSupplier clock;
    private volatile Snapshot snapshot;
    private boolean loading;
    private boolean attempted;
    private long lastAttemptAtMs;
    private long generation;

    SequoiaGuildMembership(
            Supplier<CompletableFuture<WynncraftGuildClient.GuildRoster>> fetcher, LongSupplier clock) {
        this.fetcher = Objects.requireNonNull(fetcher);
        this.clock = Objects.requireNonNull(clock);
    }

    public static synchronized SequoiaGuildMembership getInstance() {
        if (instance == null) {
            instance = new SequoiaGuildMembership(
                    () -> WynncraftGuildClient.getInstance().fetchRoster("SEQ", null), System::currentTimeMillis);
        }
        return instance;
    }

    /** Unknown and expired membership never authorize decoration. No I/O on the rendering path. */
    public boolean isMember(String username) {
        Snapshot current = snapshot;
        long age = current == null ? -1 : clock.getAsLong() - current.fetchedAtMs();
        return current != null && age >= 0 && age < MAX_AGE_MS && current.roster().member(username) != null;
    }

    /** Refreshes asynchronously at most once per upstream cache interval. */
    public synchronized void tick() {
        long now = clock.getAsLong();
        if (loading || (attempted && now - lastAttemptAtMs < REFRESH_INTERVAL_MS)) {
            return;
        }
        loading = true;
        attempted = true;
        lastAttemptAtMs = now;
        long requestedFor = generation;
        try {
            fetcher.get().whenComplete((roster, failure) -> accept(requestedFor, roster, failure));
        } catch (RuntimeException failure) {
            accept(requestedFor, null, failure);
        }
    }

    private synchronized void accept(long requestedFor, WynncraftGuildClient.GuildRoster roster, Throwable failure) {
        if (requestedFor != generation) {
            return;
        }
        loading = false;
        snapshot = failure == null && roster != null && "SEQ".equalsIgnoreCase(roster.guildPrefix())
                ? new Snapshot(roster, clock.getAsLong())
                : null;
    }

    public synchronized void reset() {
        generation++;
        loading = false;
        attempted = false;
        snapshot = null;
    }
}
