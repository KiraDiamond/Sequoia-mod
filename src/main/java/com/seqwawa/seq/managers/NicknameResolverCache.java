package com.seqwawa.seq.managers;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import static com.seqwawa.seq.managers.PacketNameResolver.*;

/** Session-local observations only. Reads neither renew evidence nor create aliases. */
public final class NicknameResolverCache {
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int LIMIT = 2048;
    private static final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private NicknameResolverCache() {}

    static Clock clock = Clock.systemUTC();
    private static long generation;
    static synchronized long generation() { return generation; }
    public static synchronized void reset() { entries.clear(); generation++; }

    static synchronized void observe(Resolution result) {
        if (result.session() != generation) return;
        if (result.source() != Source.HOVER && result.source() != Source.INSERTION && result.source() != Source.INLINE) return;
        String alias = result.source() == Source.INLINE
                ? result.display().substring(0, result.display().lastIndexOf('(')).trim() : result.display();
        String key = key(alias);
        if (key.isBlank()) return;
        Instant now = clock.instant();
        entries.entrySet().removeIf(e -> expired(e.getValue(), now));
        Entry previous = entries.get(key);
        int authority = authority(result.source());
        if (previous != null && authority < authority(previous.observation().source())) return;
        if (previous != null && authority > authority(previous.observation().source())) previous = null;
        var candidates = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        if (previous != null) candidates.addAll(previous.observation().evidence());
        candidates.addAll(result.evidence());
        // Conflicting observations remain quarantined until expiry/reset.
        if (previous != null && previous.observation().state() == State.AMBIGUOUS) return;
        if (entries.size() >= LIMIT && !entries.containsKey(key)) return;
        Resolution observation = new Resolution(candidates.size() == 1 ? State.RESOLVED : State.AMBIGUOUS,
                alias, result.username(), null, result.source(), result.role(), result.start(), result.end(), List.copyOf(candidates), generation);
        entries.put(key, new Entry(observation, candidates.size() > 1 && previous != null ? previous.observedAt() : now));
    }

    static synchronized Resolution lookup(String display, boolean truncated, String role, int start, int end, long session) {
        var candidates = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        Instant now = clock.instant();
        String key = key(display);
        entries.forEach((alias, entry) -> {
            if (expired(entry, now)) entries.remove(alias, entry);
            else if (session == generation && (alias.equals(key) || (truncated && key.length() >= 3 && alias.startsWith(key))))
                candidates.addAll(entry.observation().evidence());
        });
        return new Resolution(candidates.isEmpty() ? State.UNRESOLVED : candidates.size() == 1 ? State.RESOLVED : State.AMBIGUOUS,
                display, candidates.size() == 1 ? candidates.first() : null, null, Source.CACHE, role, start, end, List.copyOf(candidates), session);
    }

    private static int authority(Source source) { return source == Source.INSERTION ? 1 : 2; }
    private static boolean expired(Entry entry, Instant now) { return Duration.between(entry.observedAt(), now).compareTo(TTL) >= 0; }
    private static String key(String display) { return display == null ? "" : display.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private record Entry(Resolution observation, Instant observedAt) {}
}
