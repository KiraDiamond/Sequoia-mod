package com.seqwawa.seq.loot;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Stages explicit nearby labels before entry and latches one identity for a bounded radius session. */
public final class WorldEventIdentity {
    private static final long PRE_ENTRY_LIFETIME_MS = 15_000;
    private static final long MAX_SESSION_MS = 60 * 60_000;
    private static final double MAX_DISTANCE_SQUARED = 64 * 64;
    private boolean inside;
    private Candidate staged;
    private long stagedAt;
    private Candidate active;
    private long enteredAt;
    private long lastSeenAt;
    private boolean conflict;
    private String missingReason = "no_explicit_name";

    public record Candidate(String name, String source, double x, double z) {
        double distanceSquared(double playerX, double playerZ) {
            return (x - playerX) * (x - playerX) + (z - playerZ) * (z - playerZ);
        }
    }

    public void enter(long now, double playerX, double playerZ) {
        if (inside) return; // Duplicate radius packets must not reset the identity or extend its lifetime.
        inside = true;
        enteredAt = now;
        if (staged != null) {
            if (now < stagedAt || now - stagedAt > PRE_ENTRY_LIFETIME_MS) {
                missingReason = "staged_name_expired";
            } else if (staged.distanceSquared(playerX, playerZ) > MAX_DISTANCE_SQUARED) {
                missingReason = "staged_name_out_of_range";
            } else {
                active = staged;
                lastSeenAt = stagedAt;
            }
        }
        staged = null;
    }

    public boolean inside() { return inside; }

    public void observe(Collection<Candidate> candidates, long now, double playerX, double playerZ) {
        List<Candidate> nearby = candidates.stream()
                .filter(candidate -> candidate != null && candidate.name() != null && !candidate.name().isBlank()
                        && candidate.distanceSquared(playerX, playerZ) <= MAX_DISTANCE_SQUARED)
                .toList();
        long names = nearby.stream().map(Candidate::name).distinct().count();
        if (names > 1) {
            staged = null;
            if (inside) {
                active = null;
                conflict = true;
            }
        } else if (names == 1) {
            Candidate candidate = nearby.stream()
                    .min(Comparator.comparingDouble(value -> value.distanceSquared(playerX, playerZ))).orElseThrow();
            if (!inside) {
                staged = candidate;
                stagedAt = now;
            } else if (!conflict) {
                if (active != null && !active.name().equals(candidate.name())) {
                    active = null;
                    conflict = true;
                } else {
                    active = candidate;
                    lastSeenAt = now;
                }
            }
        }
        // Empty observations are expected once the pre-start hologram disappears.
    }

    public String state(long now) {
        if (!inside) return staged != null ? "staged" : "outside";
        if (now < enteredAt || now - enteredAt > MAX_SESSION_MS) return "session_expired";
        if (conflict) return "conflicting_names";
        return active == null ? missingReason : "identified";
    }

    public String description(long now) {
        return state(now) + ":" + (active != null ? active.name() : staged != null ? staged.name() : "none");
    }

    public long sessionAgeMs(long now) { return inside ? now - enteredAt : -1; }
    public long lastSeenAgeMs(long now) { return active != null ? now - lastSeenAt : -1; }
    public String source() { return active == null ? "none" : active.source(); }

    public String complete(long now) {
        String result = "identified".equals(state(now)) ? active.name() : null;
        leave();
        return result;
    }

    public void leave() {
        inside = false;
        staged = null;
        active = null;
        conflict = false;
        missingReason = "no_explicit_name";
    }
}
