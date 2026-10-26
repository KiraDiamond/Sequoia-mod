package com.seqwawa.seq.managers;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.seqwawa.seq.mixins.ClientPacketListenerMixin;
import net.minecraft.network.chat.Component;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.network.ConnectionManager;
import com.seqwawa.seq.ui.PrincessRaidCelebration;
import com.seqwawa.seq.utils.PacketTextNormalizer;

/**
 * Detects raid completions from Wynncraft chat and announces them to the guild.
 * <p>
 * Called from {@link ClientPacketListenerMixin} at the
 * packet level, before Wynntils or Fabric's message API can cancel/reformat
 * the message. Wynncraft sends raid completions as multiline system chat with
 * Unicode private-use prefix characters on each line, e.g.:
 * 
 * <pre>
 * \uDBFF\uDFFC\uDBFF\uDFFF\uDBFF\uDFFE Player1, Player2, and Player3
 * \uDBFF\uDFFC\uDC00\uDC06  finished The Nameless Anomaly and claimed 2x Aspects,
 * \uDBFF\uDFFC\uDC00\uDC06 2048x Emeralds, +10367m Guild Experience, and +410
 * \uDBFF\uDFFC\uDC00\uDC06 Seasonal Rating
 * </pre>
 */
public class RaidTracker {

    /**
     * Pattern for Wynncraft raid completion (applied after Unicode/whitespace
     * cleanup).
     * Group 1: Comma-separated player names (with optional "and")
     * Group 2: Raid name (e.g., "The Canyon Colossus")
     * Group 3: Reward clause, when present
     * Group 4: Guild Experience (numeric value before 'm')
     * Group 5: Seasonal Rating
     */
    private static final Pattern RAID_FINISH_PATTERN = Pattern.compile(
            "^(.+?) finished ([\\w ']+?) "
            + "and claimed\\s+(?:(.*?)(?:,\\s*)?(?:and\\s+)?)?"
            + "\\+(\\d+)m Guild Experience(?:, and \\+(\\d+) Seasonal Rating)?$");
    private static final Pattern RAID_REWARD_PATTERN =
            Pattern.compile("(?i)(?:(\\d+)x|no) (Emeralds?|Aspects?)");
    private static final Pattern COMMA_SPACING_PATTERN = Pattern.compile("\\s*,\\s*");
    private static final int DEFAULT_MAX_RAID_PARTY_MEMBERS = 4;
    private static final int ANNIHILATION_MAX_RAID_PARTY_MEMBERS = 10;
    private static final String RAID_FAILED_TEXT = "raid failed";
    /**
     * Called from {@link ClientPacketListenerMixin} at the
     * packet level for every non-overlay system chat message.
     */
    public static void onSystemChat(Component message) {
        String plain = message != null ? message.getString() : null;
        boolean raidCandidate = isRaidCandidateText(plain);
        if (!raidCandidate) {
            return;
        }
        SeqClient.LOGGER.info("[RaidTracker] Processing raid candidate raw='{}'", abbreviateForLog(plain));

        ParsedRaidCompletion completion = parseRaidCompletion(message);
        if (completion == null) {
            SeqClient.LOGGER.warn(
                    "[RaidTracker] Raid candidate did not produce a parsed completion raw='{}'",
                    abbreviateForLog(plain));
            return;
        }

        ResolvedRaidCompletion resolved = resolveForClient(completion, localMinecraftUsername());
        Map<String, Integer> gambitCounts = resolved.localCompletion()
                ? RaidGambitRosterTracker.snapshotForParty(resolved.partyMembers())
                : Map.of();
        boolean princessAtCompletion = PrincessMode.isEnabled();
        finishLocalCompletion(
                resolved,
                () -> recordPrincessCompletion(completion.raidName(), princessAtCompletion),
                RaidGambitRosterTracker::reset,
                () -> GuildRaidProgressService.getInstance().onLocalRaidCompleted());

        // Wynncraft broadcasts every guild raid completion to guild chat, so this fires
        // for other members' raids too. That is what lets the members panel mark someone
        // busy without the backend having to relay anything back to us.
        GuildRaidActivityTracker.recordCompletion(resolved.partyMembers());

        if (!ConnectionManager.isConnected()) {
            SeqClient.LOGGER.warn(
                    "[RaidTracker] Skipping raid announcement because backend is disconnected raw='{}'",
                    abbreviateForLog(plain));
            return;
        }
        if (SeqClient.getRaidAutoAnnounceSetting() == null
                || !SeqClient.getRaidAutoAnnounceSetting().getValue()) {
            SeqClient.LOGGER.info(
                    "[RaidTracker] Skipping raid announcement because auto-announce is disabled raw='{}'",
                    abbreviateForLog(plain));
            return;
        }

        ConnectionManager instance = ConnectionManager.getInstance();
        if (instance == null || resolved.partyMembers().isEmpty()) {
            return;
        }
        SeqClient.LOGGER.info(
                "[RaidTracker] Forwarding raid completion raid='{}' members={} aspects={} emeralds={} guildExp={} seasonalRating={}",
                completion.raidName(),
                resolved.partyMembers(),
                completion.aspects(),
                completion.emeralds(),
                completion.guildExp(),
                completion.seasonalRating());
        instance.sendRaidAnnouncement(
                resolved.partyMembers(),
                completion.raidName(),
                completion.aspects(),
                completion.emeralds(),
                completion.guildExp(),
                completion.seasonalRating(),
                gambitCounts);
    }

    static ResolvedRaidCompletion resolveForClient(
            ParsedRaidCompletion completion, String localMinecraftUsername) {
        boolean localCompletion = isLocalCompletion(completion.partyMembers(), localMinecraftUsername);
        List<String> partyMembers = localCompletion
                ? RaidPartySnapshotTracker.resolvePartyMembers(
                        completion.partyMembers(),
                        completion.displayedPartySize(),
                        maximumPartySize(completion.raidName()))
                : completion.partyMembers();
        return new ResolvedRaidCompletion(partyMembers, localCompletion);
    }

    static void finishLocalCompletion(
            ResolvedRaidCompletion completion,
            Runnable completionEffect,
            Runnable gambitResetEffect,
            Runnable progressRefreshEffect) {
        if (completion.localCompletion()) {
            RaidPartySnapshotTracker.onRaidCompleted();
            gambitResetEffect.run();
            completionEffect.run();
            progressRefreshEffect.run();
        }
    }

    private static void recordPrincessCompletion(String raidName, boolean princessAtCompletion) {
        if (!princessAtCompletion) {
            return;
        }
        PrincessRaidStatsManager manager = SeqClient.getPrincessRaidStatsManager();
        if (manager == null) {
            PrincessRaidCelebration.triggerIfEnabled();
            return;
        }
        PrincessRaidStatsManager.Completion completion = manager.recordCompletion(raidName);
        if (completion != null) {
            PrincessRaidCelebration.triggerIfEnabled(completion.eventId(), completion.displayedRaidCount());
            completion.confirmedRaidCount().thenAccept(count -> {
                if (SeqClient.mc != null) {
                    SeqClient.mc.execute(
                            () -> PrincessRaidCelebration.updateRaidCount(completion.eventId(), count));
                }
            });
        }
    }

    static boolean isLocalCompletion(List<String> partyMembers, String localMinecraftUsername) {
        if (localMinecraftUsername == null || localMinecraftUsername.isBlank()) {
            return false;
        }
        return partyMembers.stream().anyMatch(localMinecraftUsername::equalsIgnoreCase);
    }

    private static String localMinecraftUsername() {
        if (SeqClient.mc == null) {
            return null;
        }
        if (SeqClient.mc.getUser() != null) {
            return SeqClient.mc.getUser().getName();
        }
        return SeqClient.mc.player != null ? SeqClient.mc.player.getName().getString() : null;
    }

    /** Called from the raw vanilla title packet path, independently of Wynntils. */
    public static void onTitle(Component title) {
        if (isRaidFailedTitle(title)) {
            SeqClient.LOGGER.debug("[RaidTracker] Raid failure title detected");
            RaidPartySnapshotTracker.onRaidFailed();
            RaidGambitRosterTracker.reset();
        }
    }

    static boolean isRaidFailedTitle(Component title) {
        if (title == null) {
            return false;
        }
        String normalized = PacketTextNormalizer.normalizeForParsing(title.getString());
        return normalized.toLowerCase(java.util.Locale.ROOT).contains(RAID_FAILED_TEXT);
    }

    static ParsedRaidCompletion parseRaidCompletion(Component message) {
        if (message == null) {
            return null;
        }

        String plain = message.getString();

        // Quick keyword gate — skip cleanup & regex for the vast majority of messages.
        // Seasonal Rating is optional, so gate on core raid-completion tokens only.
        if (!isRaidCandidateText(plain))
            return null;

        String cleaned = normalizeForRaidParsing(plain);
        SeqClient.LOGGER.info("[RaidTracker] Normalized raid candidate cleaned='{}'", abbreviateForLog(cleaned));

        Matcher matcher = RAID_FINISH_PATTERN.matcher(cleaned);
        if (!matcher.matches()) {
            SeqClient.LOGGER.warn(
                    "[RaidTracker] Regex did not match normalized raid candidate cleaned='{}'",
                    abbreviateForLog(cleaned));
            return null;
        }

        String namesPart = matcher.group(1);
        if (namesPart.contains(":")) {
            return null;
        }
        String raidName = matcher.group(2);
        int maximumPartySize = maximumPartySize(raidName);
        List<String> parsedDisplayedNames = parseDisplayedNames(namesPart);
        if (parsedDisplayedNames.size() > maximumPartySize) {
            SeqClient.LOGGER.warn(
                    "[RaidTracker] Dropping raid candidate with too many displayed names raid='{}' count={} max={} namesPart='{}'",
                    raidName,
                    parsedDisplayedNames.size(),
                    maximumPartySize,
                    namesPart);
            return null;
        }
        List<String> partyMembers = resolvePartyMembers(parsedDisplayedNames, message);
        SeqClient.LOGGER.info(
                "[RaidTracker] Parsed raid candidate namesPart='{}' displayedNames={} resolvedMembers={}",
                namesPart,
                parsedDisplayedNames,
                partyMembers);

        if (partyMembers.isEmpty()) {
            SeqClient.LOGGER
                    .warn("[RaidTracker] Dropping raid announcement: no valid usernames found in completion message");
            return null;
        }

        RaidRewardCounts rewardCounts = parseRaidRewardCounts(matcher.group(3));

        // Wynncraft reports XP in millions (e.g. "10367m" = 10,367,000) — divide
        // by 1000 so the backend receives a friendlier value (10367 -> 10.367).
        double guildExp = Double.parseDouble(matcher.group(4)) / 1000.0;
        int seasonalRating = matcher.group(5) != null ? Integer.parseInt(matcher.group(5)) : 0;

        SeqClient.LOGGER.info(
                "[RaidTracker] Parsed raid completion raid='{}' aspects={} emeralds={} guildExp={} seasonalRating={}",
                raidName,
                rewardCounts.aspects(),
                rewardCounts.emeralds(),
                guildExp,
                seasonalRating);
        return new ParsedRaidCompletion(
                partyMembers,
                parsedDisplayedNames.size(),
                raidName,
                rewardCounts.aspects(),
                rewardCounts.emeralds(),
                guildExp,
                seasonalRating);
    }

    private static RaidRewardCounts parseRaidRewardCounts(String rewardClause) {
        int aspects = 0;
        int emeralds = 0;
        if (rewardClause == null || rewardClause.isBlank()) {
            return new RaidRewardCounts(aspects, emeralds);
        }

        Matcher rewardMatcher = RAID_REWARD_PATTERN.matcher(rewardClause);
        while (rewardMatcher.find()) {
            int amount = rewardMatcher.group(1) != null ? Integer.parseInt(rewardMatcher.group(1)) : 0;
            String rewardType = rewardMatcher.group(2).toLowerCase();
            if (rewardType.startsWith("aspect")) {
                aspects += amount;
            } else {
                emeralds += amount;
            }
        }
        return new RaidRewardCounts(aspects, emeralds);
    }

    static String normalizeForRaidParsing(String rawText) {
        return PacketTextNormalizer.normalizeForParsing(rawText)
                .replace(",and ", ", and ");
    }

    private static boolean isRaidCandidateText(String plain) {
        return plain != null
                && plain.contains("finished")
                && plain.contains("claimed")
                && plain.contains("Guild")
                && plain.contains("Experience");
    }

    private static List<String> parseDisplayedNames(String namesPart) {
        String canonicalNames = namesPart.replace(", and ", ", ").trim();
        if (!canonicalNames.contains(",")) {
            int finalAnd = canonicalNames.lastIndexOf(" and ");
            if (finalAnd >= 0) {
                canonicalNames = canonicalNames.substring(0, finalAnd)
                        + ", "
                        + canonicalNames.substring(finalAnd + " and ".length());
            }
        }

        return COMMA_SPACING_PATTERN.splitAsStream(canonicalNames)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static List<String> resolvePartyMembers(List<String> parsedDisplayedNames, Component message) {
        PacketNameResolver resolver = PacketNameResolver.from(message);
        Set<String> resolved = new LinkedHashSet<>();
        int searchFrom = 0;
        for (String display : parsedDisplayedNames) {
            int start = resolver.text().indexOf(display, searchFrom);
            if (start < 0) continue;
            searchFrom = start + display.length();
            String username = resolver.resolve(start, searchFrom, PacketNameResolver.Policy.CHAT, "raid.member").username();
            if (username != null) resolved.add(username);
        }
        return List.copyOf(resolved);
    }

    static int maximumPartySize(String raidName) {
        return raidName != null && raidName.toLowerCase(java.util.Locale.ROOT).contains("annihilation")
                ? ANNIHILATION_MAX_RAID_PARTY_MEMBERS
                : DEFAULT_MAX_RAID_PARTY_MEMBERS;
    }

    private static String abbreviateForLog(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 512 ? value : value.substring(0, 512) + "...";
    }

    record ParsedRaidCompletion(
            List<String> partyMembers,
            int displayedPartySize,
            String raidName,
            int aspects,
            int emeralds,
            double guildExp,
            int seasonalRating) {
    }

    record ResolvedRaidCompletion(List<String> partyMembers, boolean localCompletion) {
        ResolvedRaidCompletion {
            partyMembers = List.copyOf(partyMembers);
        }
    }

    private record RaidRewardCounts(int aspects, int emeralds) {
    }
}
