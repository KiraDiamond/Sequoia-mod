package com.seqwawa.seq.managers;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.model.RankProfilesResponse;
import com.seqwawa.seq.model.SeqBadge;
import com.seqwawa.seq.model.SeqBadgeType;
import com.seqwawa.seq.model.SeqBadgeTier;
import com.seqwawa.seq.utils.PlayerNameCache;

/**
 * Indexes the leaderboard badges every member holds, by Minecraft UUID for
 * nametag rendering and by username for chat.
 * <p>
 * A read-only view over {@link RankProfileRoster}, which owns the single fetch
 * and cache.
 */
public final class LeaderboardBadgeService {

    private static LeaderboardBadgeService instance;

    private volatile Map<String, Map<SeqBadgeType, SeqBadgeTier>> cachedBadges = Map.of();
    private volatile Map<String, Map<SeqBadgeType, SeqBadgeTier>> badgesByUsername = Map.of();
    private volatile boolean loaded;

    private LeaderboardBadgeService() {
        RankProfileRoster.getInstance().subscribe(this::accept);
    }

    /** Rebuilds both indexes, and the badge art cache, from a roster snapshot. */
    private void accept(RankProfilesResponse response) {
        cachedBadges = parseProfiles(response);
        badgesByUsername = parseProfilesByUsername(response);
        RankProfileBadgeAssetCache.refresh(response.catalog());
        loaded = true;
    }

    public static synchronized LeaderboardBadgeService getInstance() {
        if (instance == null) {
            instance = new LeaderboardBadgeService();
        }
        return instance;
    }

    public List<SeqBadge> badgesFor(UUID uuid) {
        EnumMap<SeqBadgeType, SeqBadgeTier> merged = new EnumMap<>(SeqBadgeType.class);
        String uuidKey = uuid == null ? null : PlayerNameCache.formatUUID(uuid.toString());
        if (uuidKey != null) {
            mergeBadges(merged, cachedBadges.get(uuidKey));
        }

        return SeqBadge.sortForRender(merged.entrySet().stream()
                .map(entry -> new SeqBadge(entry.getKey(), entry.getValue()))
                .toList());
    }

    /**
     * A player's badge of {@code type}, looked up by Minecraft username.
     * <p>
     * Nametag rendering has a UUID to hand, but chat does not: a guild line and a
     * bridged message both carry only a name. Indexing names here is what lets those
     * callers share this roster instead of parsing the same catalog again.
     */
    public SeqBadgeTier badgeForUsername(String username, SeqBadgeType type) {
        String key = normalizeUsername(username);
        if (key == null || type == null) {
            return null;
        }
        // The map is immutable, and those throw on a null key rather than returning
        // null, so an unresolved name must never reach the lookup.
        Map<SeqBadgeType, SeqBadgeTier> badges = badgesByUsername.get(key);
        return badges == null ? null : badges.get(type);
    }

    private static String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        String normalized = username.trim();
        return normalized.isEmpty() ? null : normalized.toLowerCase(Locale.ROOT);
    }

    /** One-line diagnostic for {@code /seq badges status}. */
    public String status() {
        return "Leaderboard badges: backend="
                + badgeCount(cachedBadges)
                + " badges for "
                + cachedBadges.size()
                + " players"
                + (loaded ? "" : " | awaiting first roster snapshot")
                + " | "
                + RankProfileRoster.getInstance().status();
    }


    static Map<String, Map<SeqBadgeType, SeqBadgeTier>> parseProfiles(RankProfilesResponse response) {
        return parseProfiles(response, profile -> PlayerNameCache.formatUUID(profile.minecraft().uuid()));
    }

    /** The same badges keyed by Minecraft username, for callers that have no UUID. */
    static Map<String, Map<SeqBadgeType, SeqBadgeTier>> parseProfilesByUsername(RankProfilesResponse response) {
        return parseProfiles(response, profile -> normalizeUsername(profile.minecraft().username()));
    }

    /**
     * Parses badge assignments into a map keyed by whatever identity {@code keyOf}
     * extracts. Profiles whose key cannot be resolved, or that hold no badge, are
     * skipped.
     */
    private static Map<String, Map<SeqBadgeType, SeqBadgeTier>> parseProfiles(
            RankProfilesResponse response, Function<RankProfilesResponse.Profile, String> keyOf) {
        Map<String, EnumMap<SeqBadgeType, SeqBadgeTier>> parsed = new HashMap<>();
        if (response == null || response.schemaVersion() != 1 || response.catalog() == null) {
            throw new IllegalArgumentException("Unsupported or incomplete rank-profile response");
        }
        if (response.profiles() == null) {
            return Map.of();
        }

        Map<String, SeqBadge> definitions = badgeDefinitions(response.catalog());
        for (RankProfilesResponse.Profile profile : response.profiles()) {
            if (profile == null || profile.minecraft() == null) {
                continue;
            }
            String key = keyOf.apply(profile);
            if (key == null) {
                continue;
            }
            EnumMap<SeqBadgeType, SeqBadgeTier> profileBadges = new EnumMap<>(SeqBadgeType.class);
            mergeDefinitionKeys(profileBadges, definitions, profile.roleKeys());
            mergeDefinitionKeys(profileBadges, definitions, profile.awardKeys());
            if (!profileBadges.isEmpty()) {
                parsed.put(key, profileBadges);
            }
        }

        Map<String, Map<SeqBadgeType, SeqBadgeTier>> immutable = new HashMap<>();
        parsed.forEach((key, badges) -> immutable.put(key, Map.copyOf(badges)));
        return Map.copyOf(immutable);
    }

    static Map<String, SeqBadge> badgeDefinitions(RankProfilesResponse.Catalog catalog) {
        Map<String, SeqBadge> definitions = new HashMap<>();
        HashSet<String> assets = new HashSet<>();
        if (catalog.assets() != null) {
            catalog.assets().stream()
                    .filter(asset -> asset != null && asset.key() != null)
                    .map(RankProfilesResponse.AssetDefinition::key)
                    .forEach(assets::add);
        }

        if (catalog.roles() != null) {
            for (RankProfilesResponse.RoleDefinition role : catalog.roles()) {
                if (role == null
                        || !"insignia".equalsIgnoreCase(role.category())
                        || !assets.contains(role.assetKey())) {
                    continue;
                }
                addDefinition(definitions, role.key(), SeqBadgeType.INSIGNIA, role.tier());
            }
        }
        if (catalog.awards() != null) {
            for (RankProfilesResponse.AwardDefinition award : catalog.awards()) {
                if (award == null
                        || !"raid_badge".equalsIgnoreCase(award.category())
                        || !assets.contains(award.assetKey())) {
                    continue;
                }
                SeqBadgeType type = SeqBadgeType.parse(award.series());
                if (type != SeqBadgeType.WTP && type != SeqBadgeType.NOL && type != SeqBadgeType.TCC) {
                    continue;
                }
                addDefinition(definitions, award.key(), type, award.tier());
            }
        }
        return definitions;
    }

    private static void addDefinition(
            Map<String, SeqBadge> definitions, String key, SeqBadgeType type, String tierValue) {
        if (type == SeqBadgeType.TCC && tierValue != null) {
            return;
        }
        SeqBadgeTier tier = type == SeqBadgeType.TCC
                ? SeqBadgeTier.SINGLE
                : SeqBadgeTier.parse(tierValue);
        if ((type == SeqBadgeType.TCC) != (tier == SeqBadgeTier.SINGLE)) {
            return;
        }
        if (key != null && !key.isBlank() && type != null && tier != null) {
            definitions.put(key, new SeqBadge(type, tier));
        }
    }

    private static void mergeDefinitionKeys(
            EnumMap<SeqBadgeType, SeqBadgeTier> target,
            Map<String, SeqBadge> definitions,
            List<String> keys) {
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            SeqBadge badge = definitions.get(key);
            if (badge != null) {
                target.merge(badge.type(), badge.tier(), SeqBadgeTier::highest);
            }
        }
    }

    private static void mergeBadges(
            EnumMap<SeqBadgeType, SeqBadgeTier> target,
            Map<SeqBadgeType, SeqBadgeTier> source) {
        if (source != null) {
            target.putAll(source);
        }
    }

    private static int badgeCount(Map<String, Map<SeqBadgeType, SeqBadgeTier>> badges) {
        return badges.values().stream().mapToInt(Map::size).sum();
    }
}
