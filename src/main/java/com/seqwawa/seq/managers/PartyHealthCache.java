package com.seqwawa.seq.managers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

public final class PartyHealthCache {
    private static final long FULL_BAR_MAX_PROMOTION_DELAY_MS = 2_000;
    static final long REFRESH_INTERVAL_MS = 200;

    private static final Map<UUID, CachedPartyHealth> healthByUuid = new ConcurrentHashMap<>();
    private static final Map<UUID, HealthMaxState> maxHealthByUuid = new ConcurrentHashMap<>();
    private static long lastRefreshMs;

    private PartyHealthCache() {}

    public record CachedPartyHealth(
            String nickname,
            String username,
            UUID uuid,
            int hp,
            int level,
            int maxHp,
            boolean overMax) {}

    public record HealthBarState(float percent, boolean overMax) {}

    public static synchronized void tick() {
        long now = System.currentTimeMillis();
        if (!isRefreshDue(now, lastRefreshMs)) {
            return;
        }
        lastRefreshMs = now;

        List<WynnPartyScoreboardReader.PartyHealth> scoreboardHealth = WynnPartyScoreboardReader.readPartyHealth();
        Map<String, UUID> visiblePlayerUuids = visiblePlayerUuidsByUsername();
        Set<UUID> currentPartyUuids = new HashSet<>();

        for (WynnPartyScoreboardReader.PartyHealth member : scoreboardHealth) {
            if (member.username() == null || member.username().isBlank()) {
                continue;
            }

            UUID uuid = resolveVisiblePlayerUuid(visiblePlayerUuids, member.username());
            if (uuid == null) {
                continue;
            }
            currentPartyUuids.add(uuid);

            if (!member.online() || !member.alive()) {
                healthByUuid.remove(uuid);
                maxHealthByUuid.remove(uuid);
                continue;
            }

            HealthMaxResult maxHealth = maxHealth(uuid, member.hp(), member.fullHealthBar());
            healthByUuid.put(uuid, new CachedPartyHealth(
                    member.nickname(),
                    member.username(),
                    uuid,
                    member.hp(),
                    member.level(),
                    maxHealth.maxHp(),
                    maxHealth.overMax()));
        }

        healthByUuid.keySet().retainAll(currentPartyUuids);
        maxHealthByUuid.keySet().retainAll(currentPartyUuids);
    }

    public static HealthBarState healthBarState(UUID uuid) {
        refreshIfStale();
        CachedPartyHealth health = uuid != null ? healthByUuid.get(uuid) : null;
        if (health == null || health.maxHp() <= 0) {
            return null;
        }
        float percent = Math.max(0f, Math.min(1f, health.hp() / (float) health.maxHp()));
        return new HealthBarState(percent, health.overMax());
    }

    private static void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (isRefreshDue(now, lastRefreshMs)) {
            tick();
        }
    }

    static boolean isRefreshDue(long nowMs, long lastRefreshMs) {
        return lastRefreshMs == 0 || nowMs - lastRefreshMs >= REFRESH_INTERVAL_MS;
    }

    private static HealthMaxResult maxHealth(UUID uuid, int currentHp, boolean fullHealthBar) {
        long now = System.currentTimeMillis();
        if (uuid == null) {
            return new HealthMaxResult(currentHp, false);
        }

        HealthMaxState state = maxHealthByUuid.computeIfAbsent(
                uuid,
                ignored -> new HealthMaxState(0, currentHp, now, currentHp));

        if (!fullHealthBar) {
            state.fullBarCandidateHp = currentHp;
            state.fullBarCandidateSinceMs = now;
            state.lastObservedHp = currentHp;
            int maxHp = displayMaxHp(state, currentHp);
            return new HealthMaxResult(maxHp, isOverMax(currentHp, maxHp));
        }

        if (state.stableMaxHp <= 0) {
            state.stableMaxHp = currentHp;
            state.fullBarCandidateHp = currentHp;
            state.fullBarCandidateSinceMs = now;
            state.lastObservedHp = currentHp;
            int maxHp = displayMaxHp(state, currentHp);
            return new HealthMaxResult(maxHp, false);
        }

        if (currentHp != state.lastObservedHp || currentHp != state.fullBarCandidateHp) {
            state.fullBarCandidateHp = currentHp;
            state.fullBarCandidateSinceMs = now;
            state.lastObservedHp = currentHp;
            return new HealthMaxResult(state.stableMaxHp, isOverMax(currentHp, state.stableMaxHp));
        }

        if (now - state.fullBarCandidateSinceMs >= FULL_BAR_MAX_PROMOTION_DELAY_MS) {
            state.stableMaxHp = currentHp;
        }

        state.lastObservedHp = currentHp;
        int maxHp = displayMaxHp(state, currentHp);
        return new HealthMaxResult(maxHp, isOverMax(currentHp, maxHp));
    }

    private static int displayMaxHp(HealthMaxState state, int currentHp) {
        return state.stableMaxHp > 0 ? state.stableMaxHp : currentHp;
    }

    private static boolean isOverMax(int currentHp, int maxHp) {
        return maxHp > 0 && currentHp > maxHp;
    }

    private static Map<String, UUID> visiblePlayerUuidsByUsername() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return Map.of();
        }

        Map<String, UUID> result = new HashMap<>();
        for (AbstractClientPlayer player : mc.level.players())
            rememberAccount(result, player.getGameProfile().name(), player.getUUID());
        return result;
    }

    static void rememberAccount(Map<String, UUID> result, String account, UUID uuid) {
        if (PacketNameResolver.validUsername(account) != null && uuid != null)
            result.merge(normalizeName(account), uuid, (a, b) -> a.equals(b) ? a : CONFLICT);
    }

    private static final UUID CONFLICT = new UUID(0, 0);

    static UUID resolveVisiblePlayerUuid(Map<String, UUID> visiblePlayerUuids, String username) {
        UUID uuid = visiblePlayerUuids.get(normalizeName(username));
        return CONFLICT.equals(uuid) ? null : uuid;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static final class HealthMaxState {
        private int stableMaxHp;
        private int fullBarCandidateHp;
        private long fullBarCandidateSinceMs;
        private int lastObservedHp;

        private HealthMaxState(
                int stableMaxHp,
                int fullBarCandidateHp,
                long fullBarCandidateSinceMs,
                int lastObservedHp) {
            this.stableMaxHp = stableMaxHp;
            this.fullBarCandidateHp = fullBarCandidateHp;
            this.fullBarCandidateSinceMs = fullBarCandidateSinceMs;
            this.lastObservedHp = lastObservedHp;
        }
    }

    private record HealthMaxResult(int maxHp, boolean overMax) {}
}
