package com.seqwawa.seq.managers;

import com.seqwawa.seq.model.DiscordRank;
import com.seqwawa.seq.model.RankPresentation;
import com.seqwawa.seq.model.RankProfilesResponse;
import com.seqwawa.seq.model.SeqBadgeTier;
import com.seqwawa.seq.model.SeqBadgeType;
import com.seqwawa.seq.utils.ColorRamp;
import com.seqwawa.seq.utils.PlayerNameCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Indexes the Sequoia Discord progression rank of every member, keyed by every
 * identity the client may realistically observe: the Minecraft UUID and username
 * for in-game guild chat, and the Discord id, username and display name for
 * messages arriving over the guild bridge.
 * <p>
 * A read-only view over {@link RankProfileRoster}, which owns the single fetch
 * and cache. A Discord-only member can also be matched for display purposes when
 * their nickname follows Sequoia's rank-and-Minecraft-name convention. That
 * unverified fallback is kept separate from authenticated Minecraft identities.
 */
public final class DiscordRankService {
    private static final Pattern MINECRAFT_USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static DiscordRankService instance;

    private volatile Index index = Index.empty();
    private volatile boolean loaded;

    private DiscordRankService() {
        RankProfileRoster.getInstance().subscribe(this::accept);
    }

    private DiscordRankService(Index index) {
        this.index = index;
        this.loaded = true;
    }

    /** A service over a fixed index, so lookups can be tested without a game directory. */
    static DiscordRankService withIndex(Index index) {
        return new DiscordRankService(index);
    }

    /** The shared index; created on first use, which loads the on-disk cache. */
    public static synchronized DiscordRankService getInstance() {
        if (instance == null) {
            instance = new DiscordRankService();
        }
        return instance;
    }

    /** True when at least one rank is known, i.e. decoration can do something useful. */
    public boolean hasRanks() {
        return !index.byMinecraftUuid().isEmpty() || !index.byDiscordIdentity().isEmpty();
    }

    /** Looks up a rank by Minecraft UUID. */
    public DiscordRank rankForUuid(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return index.byMinecraftUuid().get(PlayerNameCache.formatUUID(uuid.toString()));
    }

    /** Looks up a rank by Minecraft username (case-insensitive). */
    public DiscordRank rankForMinecraftUsername(String username) {
        return lookup(index.byMinecraftUsername(), username);
    }

    /**
     * Looks up a rank by any Discord-side identity: user id, username, or display
     * name. Sequoia display names follow a {@code "<Rank> <McName>"} convention, so
     * the trailing segment is also indexed to match bridge senders such as
     * {@code dix} coming from {@code Sapling dix}.
     */
    public DiscordRank rankForDiscordIdentity(String identity) {
        return lookup(index.byDiscordIdentity(), identity);
    }

    /** Resolves a bridge sender name against Discord identities, then Minecraft names. */
    public DiscordRank rankForBridgeSender(String senderName) {
        DiscordRank rank = rankForDiscordIdentity(senderName);
        return rank != null ? rank : rankForMinecraftUsername(senderName);
    }

    /**
     * Resolves a bridge sender, preferring their Discord id when the bridge supplies
     * one. The id is unique and stable; the display name is neither, so matching on it
     * can attach the wrong member's rank to a message.
     */
    public DiscordRank rankForBridgeSender(String senderName, String discordId) {
        DiscordRank byId = rankForDiscordIdentity(discordId);
        return byId != null ? byId : rankForBridgeSender(senderName);
    }

    /**
     * Rank and colours of an in-game speaker. A verified Minecraft username wins;
     * only when none exists may a unique nickname-derived alias supply the display.
     */
    public RankPresentation presentationForMinecraftUsername(String username) {
        RankPresentation verified = presentationForVerifiedMinecraftUsername(username);
        return verified != null ? verified : present(lookup(index.unverifiedMinecraftAliases(), username));
    }

    /** Actual game identities only; Discord nickname guesses cannot authorize guild chat decoration. */
    public RankPresentation presentationForVerifiedMinecraftUsername(String username) {
        DiscordRank rank = rankForMinecraftUsername(username);
        return rank == null ? null : presentation(rank, lookup(index.colorsByMinecraftUsername(), username));
    }

    /**
     * Rank and colours of a player identified by their Minecraft account rather than
     * by a name. Preferred wherever the account is known, such as a rendered entity:
     * the UUID cannot be spoofed by a nickname and survives a name change.
     */
    public RankPresentation presentationForMinecraftUuid(UUID uuid) {
        DiscordRank rank = rankForUuid(uuid);
        if (rank == null) {
            return null;
        }
        return presentation(rank, lookup(index.colorsByIdentity(), PlayerNameCache.formatUUID(uuid.toString())));
    }

    /** Rank and colours of a bridge sender, matched on Discord identity then game name. */
    public RankPresentation presentationForBridgeSender(String senderName) {
        return present(rankForBridgeSender(senderName), senderName);
    }

    /**
     * Rank and colours of a bridge sender, keyed on their Discord id when the bridge
     * supplies one. Colours resolve against the same identity that matched the rank,
     * so an individual palette is found by id too.
     */
    public RankPresentation presentationForBridgeSender(String senderName, String discordId) {
        DiscordRank rank = rankForBridgeSender(senderName, discordId);
        if (rank == null) {
            return null;
        }
        return presentation(rank, individualColorsForBridgeSender(senderName, discordId));
    }

    private ColorRamp individualColorsForBridgeSender(String senderName, String discordId) {
        ColorRamp byId = lookup(index.colorsByIdentity(), discordId);
        return byId != null && !byId.isEmpty() ? byId : lookup(index.colorsByIdentity(), senderName);
    }

    private RankPresentation present(DiscordRank rank, String identity) {
        return rank == null ? null : presentation(rank, lookup(index.colorsByIdentity(), identity));
    }

    private RankPresentation present(ProfilePresentation profile) {
        return profile == null ? null : presentation(profile.rank(), profile.colors());
    }

    private RankPresentation presentation(DiscordRank rank, ColorRamp individual) {
        return new RankPresentation(
                rank,
                roleColorsFor(rank),
                individual == null ? ColorRamp.empty() : individual);
    }

    /**
     * Colours to draw {@code rank} in for {@code identity}: the member's own palette
     * when the backend publishes one, otherwise their role's.
     * <p>
     * Resolving these separately from the rank is what lets two members of the same
     * rank render differently, which is the point of individual colouring.
     */
    ColorRamp colorsFor(String identity, DiscordRank rank) {
        ColorRamp member = lookup(index.colorsByIdentity(), identity);
        return colorsWithCatalogFallback(rank, member);
    }

    private ColorRamp colorsWithCatalogFallback(DiscordRank rank, ColorRamp member) {
        if (member != null && !member.isEmpty()) {
            return member;
        }
        return roleColorsFor(rank);
    }

    private ColorRamp roleColorsFor(DiscordRank rank) {
        return rank == null ? ColorRamp.empty() : index.colorsByRoleKey().getOrDefault(rank.key(), ColorRamp.empty());
    }

    /** Rebuilds the index from a roster snapshot. */
    private void accept(RankProfilesResponse response) {
        index = parseProfiles(response);
        loaded = true;
    }

    /** One-line diagnostic for {@code /seq rank status}. */
    public String status() {
        return "Discord ranks: "
                + index.rankedProfiles()
                + " ranked members ("
                + index.byMinecraftUsername().size()
                + " game names, "
                + index.byDiscordIdentity().size()
                + " discord aliases)"
                + (loaded ? "" : " | awaiting first roster snapshot")
                + " | "
                + RankProfileRoster.getInstance().status();
    }

    // ── Parsing ──

    static Index parseProfiles(RankProfilesResponse response) {
        if (response == null || response.schemaVersion() != 1 || response.catalog() == null) {
            throw new IllegalArgumentException("Unsupported or incomplete rank-profile response");
        }
        if (response.profiles() == null) {
            return Index.empty();
        }

        Map<String, DiscordRank> ranksByKey = progressionRanks(response.catalog());
        Map<String, DiscordRank> byUuid = new HashMap<>();
        Map<String, DiscordRank> byMinecraftUsername = new HashMap<>();
        Map<String, ColorRamp> colorsByMinecraftUsername = new HashMap<>();
        Map<String, DiscordRank> byDiscordIdentity = new HashMap<>();
        Map<String, ColorRamp> colorsByIdentity = new HashMap<>();
        Map<String, String> minecraftUsernameByDiscordIdentity = new HashMap<>();
        Map<String, ProfilePresentation> unverifiedMinecraftAliases = new HashMap<>();
        Set<String> ambiguousMinecraftAliases = new HashSet<>();
        int rankedProfiles = 0;

        for (RankProfilesResponse.Profile profile : response.profiles()) {
            DiscordRank rank = resolveRank(profile, ranksByKey);
            if (rank == null) {
                continue;
            }
            rankedProfiles++;
            ColorRamp individual = profileDisplayColors(profile.displayColors());

            RankProfilesResponse.MinecraftIdentity minecraft = profile.minecraft();
            if (minecraft != null) {
                String uuid = PlayerNameCache.formatUUID(minecraft.uuid());
                if (uuid != null) {
                    byUuid.put(uuid, rank);
                    putColors(colorsByIdentity, uuid, individual);
                }
                putIdentity(byMinecraftUsername, minecraft.username(), rank);
                putIdentity(colorsByMinecraftUsername, minecraft.username(), individual);
                putColors(colorsByIdentity, minecraft.username(), individual);
            }

            RankProfilesResponse.DiscordIdentity discord = profile.discord();
            if (discord != null) {
                putIdentity(byDiscordIdentity, discord.id(), rank);
                putIdentity(byDiscordIdentity, discord.username(), rank);
                putIdentity(byDiscordIdentity, discord.displayName(), rank);
                putIdentity(byDiscordIdentity, stripRankPrefix(discord.displayName(), ranksByKey), rank);
                putColors(colorsByIdentity, discord.id(), individual);
                putColors(colorsByIdentity, discord.username(), individual);
                putColors(colorsByIdentity, discord.displayName(), individual);
                putColors(colorsByIdentity, stripRankPrefix(discord.displayName(), ranksByKey), individual);

                putUnverifiedMinecraftAlias(
                        unverifiedMinecraftAliases,
                        ambiguousMinecraftAliases,
                        minecraftNameFromRankedDisplayName(discord.displayName(), ranksByKey),
                        new ProfilePresentation(rank, individual));

                // Badges are held against the game account, so a bridge sender has to
                // be mapped back to their Minecraft name before one can be looked up.
                String gameName = minecraft == null ? null : minecraft.username();
                putLink(minecraftUsernameByDiscordIdentity, discord.id(), gameName);
                putLink(minecraftUsernameByDiscordIdentity, discord.username(), gameName);
                putLink(minecraftUsernameByDiscordIdentity, discord.displayName(), gameName);
                putLink(
                        minecraftUsernameByDiscordIdentity,
                        stripRankPrefix(discord.displayName(), ranksByKey),
                        gameName);
            }
        }

        return new Index(
                Map.copyOf(byUuid),
                Map.copyOf(byMinecraftUsername),
                Map.copyOf(colorsByMinecraftUsername),
                Map.copyOf(byDiscordIdentity),
                roleColors(response.catalog()),
                Map.copyOf(colorsByIdentity),
                Map.copyOf(minecraftUsernameByDiscordIdentity),
                Map.copyOf(unverifiedMinecraftAliases),
                rankedProfiles);
    }

    /** Builds {@code roleKey -> colours} for every progression rank in the catalog. */
    static Map<String, ColorRamp> roleColors(RankProfilesResponse.Catalog catalog) {
        Map<String, ColorRamp> colors = new HashMap<>();
        if (catalog.roles() == null) {
            return Map.of();
        }
        for (RankProfilesResponse.RoleDefinition role : catalog.roles()) {
            if (role == null || role.key() == null || role.key().isBlank()) {
                continue;
            }
            List<Integer> ramp = colorRamp(role.colors());
            if (!ramp.isEmpty()) {
                colors.put(role.key(), ColorRamp.of(ramp));
            }
        }
        return Map.copyOf(colors);
    }

    private static void putColors(Map<String, ColorRamp> target, String identity, ColorRamp colors) {
        String key = normalizeKey(identity);
        if (key != null && !colors.isEmpty()) {
            target.put(key, colors);
        }
    }

    /** Builds {@code roleKey -> rank} for every progression rank in the catalog. */
    static Map<String, DiscordRank> progressionRanks(RankProfilesResponse.Catalog catalog) {
        Map<String, DiscordRank> ranks = new HashMap<>();
        if (catalog.roles() == null) {
            return ranks;
        }
        for (RankProfilesResponse.RoleDefinition role : catalog.roles()) {
            if (role == null
                    || !DiscordRank.PROGRESSION_CATEGORY.equalsIgnoreCase(role.category())
                    || role.key() == null
                    || role.key().isBlank()
                    || role.label() == null
                    || role.label().isBlank()) {
                continue;
            }
            ranks.put(role.key(), new DiscordRank(role.key(), role.label().trim(), role.position()));
        }
        return ranks;
    }

    /**
     * Insignia tier of a member, or {@code null} when they hold none.
     * <p>
     * Delegated to {@link LeaderboardBadgeService}, which already derives every badge
     * from the same catalog; deriving insignia a second time here would let the two
     * rosters disagree about the same member.
     */
    public SeqBadgeTier insigniaForMinecraftUsername(String username) {
        return LeaderboardBadgeService.getInstance().badgeForUsername(username, SeqBadgeType.INSIGNIA);
    }

    /**
     * Insignia of a bridge sender. Their Discord identity is resolved to the Minecraft
     * username it is linked to, because badges are held against the game account.
     */
    public SeqBadgeTier insigniaForBridgeSender(String senderName) {
        return insigniaForMinecraftUsername(minecraftUsernameFor(senderName));
    }

    /** Insignia of a bridge sender, preferring their Discord id when one is supplied. */
    public SeqBadgeTier insigniaForBridgeSender(String senderName, String discordId) {
        String linked = minecraftUsernameFor(discordId);
        return insigniaForMinecraftUsername(linked != null ? linked : minecraftUsernameFor(senderName));
    }

    /**
     * The Minecraft username linked to a Discord identity, or {@code identity} itself
     * when it is already a game name.
     */
    private String minecraftUsernameFor(String identity) {
        String linked = lookup(index.minecraftUsernameByDiscordIdentity(), identity);
        return linked != null ? linked : identity;
    }


    /**
     * The role's gradient stops in Discord's order, dropping any the backend leaves
     * unset or malformed. A solid role yields one stop and an uncoloured one none.
     */
    static List<Integer> colorRamp(RankProfilesResponse.RoleColors colors) {
        if (colors == null) {
            return List.of();
        }
        List<Integer> ramp = new ArrayList<>(3);
        for (String stop : List.of(
                nullToEmpty(colors.primary()), nullToEmpty(colors.secondary()), nullToEmpty(colors.tertiary()))) {
            Integer parsed = parseHexColor(stop);
            if (parsed != null) {
                ramp.add(parsed);
            }
        }
        return List.copyOf(ramp);
    }

    /**
     * Validates a profile-level palette as one indivisible backend override. An
     * absent, empty, or malformed palette must not mask the catalog colour of the
     * member's progression rank.
     */
    private static ColorRamp profileDisplayColors(RankProfilesResponse.RoleColors colors) {
        if (colors == null || !isContractColor(colors.primary())) {
            return ColorRamp.empty();
        }

        List<Integer> ramp = new ArrayList<>(3);
        ramp.add(parseHexColor(colors.primary()));
        if (colors.secondary() != null) {
            if (!isContractColor(colors.secondary())) {
                return ColorRamp.empty();
            }
            ramp.add(parseHexColor(colors.secondary()));
        }
        if (colors.tertiary() != null) {
            if (colors.secondary() == null || !isContractColor(colors.tertiary())) {
                return ColorRamp.empty();
            }
            ramp.add(parseHexColor(colors.tertiary()));
        }
        return ColorRamp.of(ramp);
    }

    private static boolean isContractColor(String value) {
        return value != null && value.matches("(?i)#[0-9a-f]{6}");
    }

    /** Parses a {@code #RRGGBB} colour, or {@code null} when it is absent or malformed. */
    static Integer parseHexColor(String value) {
        if (value == null) {
            return null;
        }
        String hex = value.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (!hex.matches("(?i)[0-9a-f]{6}")) {
            return null;
        }
        return Integer.parseInt(hex, 16);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Prefers the backend-resolved summary; falls back to the highest positioned
     * progression role in {@code role_keys} when the summary is absent.
     */
    private static DiscordRank resolveRank(RankProfilesResponse.Profile profile, Map<String, DiscordRank> ranksByKey) {
        if (profile == null) {
            return null;
        }

        if (profile.summary() != null && profile.summary().progressionRank() != null) {
            DiscordRank summarised = ranksByKey.get(profile.summary().progressionRank());
            if (summarised != null) {
                return summarised;
            }
        }

        List<String> roleKeys = profile.roleKeys();
        if (roleKeys == null) {
            return null;
        }
        DiscordRank best = null;
        for (String roleKey : roleKeys) {
            DiscordRank candidate = ranksByKey.get(roleKey);
            if (candidate != null && (best == null || candidate.position() > best.position())) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Sequoia members are nicknamed {@code "<Rank> <McName>"} on Discord. The bridge
     * forwards the bare name, so index that too.
     */
    static String stripRankPrefix(String displayName, Map<String, DiscordRank> ranksByKey) {
        if (displayName == null) {
            return null;
        }
        String trimmed = displayName.trim();
        for (DiscordRank rank : ranksByKey.values()) {
            String prefix = rank.label() + " ";
            if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String remainder = trimmed.substring(prefix.length()).trim();
                return remainder.isEmpty() ? null : remainder;
            }
        }
        return null;
    }

    /**
     * Extracts a possible Minecraft name only from the established
     * {@code "<recognized rank> <name>"} Discord nickname convention.
     */
    static String minecraftNameFromRankedDisplayName(
            String displayName, Map<String, DiscordRank> ranksByKey) {
        if (displayName == null) {
            return null;
        }
        String trimmed = displayName.trim();
        DiscordRank matchedPrefix = null;
        for (DiscordRank rank : ranksByKey.values()) {
            String prefix = rank.label() + " ";
            if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())
                    && (matchedPrefix == null || rank.label().length() > matchedPrefix.label().length())) {
                matchedPrefix = rank;
            }
        }
        if (matchedPrefix == null) {
            return null;
        }
        String candidate = trimmed.substring(matchedPrefix.label().length() + 1).trim();
        return MINECRAFT_USERNAME_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    /**
     * Adds a nickname-derived alias exactly once. A second profile claiming the
     * same case-insensitive name permanently marks that alias ambiguous for this
     * snapshot, irrespective of profile order.
     */
    private static void putUnverifiedMinecraftAlias(
            Map<String, ProfilePresentation> target,
            Set<String> ambiguous,
            String minecraftUsername,
            ProfilePresentation presentation) {
        String key = normalizeKey(minecraftUsername);
        if (key == null || ambiguous.contains(key)) {
            return;
        }
        if (target.putIfAbsent(key, presentation) != null) {
            target.remove(key);
            ambiguous.add(key);
        }
    }

    /** Links a Discord identity to a game account, ignoring blanks on either side. */
    private static void putLink(Map<String, String> target, String identity, String minecraftUsername) {
        String key = normalizeKey(identity);
        if (key != null && minecraftUsername != null && !minecraftUsername.isBlank()) {
            target.put(key, minecraftUsername);
        }
    }

    private static <T> void putIdentity(Map<String, T> target, String identity, T value) {
        String key = normalizeKey(identity);
        if (key != null) {
            target.put(key, value);
        }
    }

    /**
     * Null-safe index lookup. The maps are immutable, and those throw on a null key
     * rather than returning null, so an unidentified sender would crash the line.
     */
    private static <T> T lookup(Map<String, T> index, String identity) {
        String key = normalizeKey(identity);
        return key == null ? null : index.get(key);
    }

    private static String normalizeKey(String identity) {
        if (identity == null) {
            return null;
        }
        String normalized = identity.trim().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized.toLowerCase(Locale.ROOT);
    }

    /**
     * @param colorsByMinecraftUsername colours belonging to verified game identities
     * @param colorsByRoleKey           the colours every holder of a role gets by default
     * @param colorsByIdentity          colours granted to an individual member, which win over
     *                                  their role's
     * @param unverifiedMinecraftAliases unique, display-only candidates derived from ranked
     *                                   Discord nicknames
     */
    record Index(
            Map<String, DiscordRank> byMinecraftUuid,
            Map<String, DiscordRank> byMinecraftUsername,
            Map<String, ColorRamp> colorsByMinecraftUsername,
            Map<String, DiscordRank> byDiscordIdentity,
            Map<String, ColorRamp> colorsByRoleKey,
            Map<String, ColorRamp> colorsByIdentity,
            Map<String, String> minecraftUsernameByDiscordIdentity,
            Map<String, ProfilePresentation> unverifiedMinecraftAliases,
            int rankedProfiles) {

        /** An index that knows nobody, used before the first load. */
        static Index empty() {
            return new Index(
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0);
        }
    }

    /** Rank and profile palette kept together so aliases cannot mix members. */
    private record ProfilePresentation(DiscordRank rank, ColorRamp colors) {}
}
