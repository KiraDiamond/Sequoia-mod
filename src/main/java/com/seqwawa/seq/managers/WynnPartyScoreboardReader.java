package com.seqwawa.seq.managers;

import com.seqwawa.seq.client.SeqClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

public final class WynnPartyScoreboardReader {
    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");
    private static final Pattern BRACKETED_NUMBER = Pattern.compile("\\[(\\d+)]");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private WynnPartyScoreboardReader() {}

    public record PartyHealth(
            String nickname,
            String username,
            int hp,
            int level,
            boolean online,
            boolean alive,
            boolean fullHealthBar) {}

    record PartyObservation(
            List<PartyHealth> members,
            List<String> canonicalRosterUsernames,
            boolean partySidebarActive,
            boolean raidSidebarActive) {
        PartyObservation {
            members = List.copyOf(members);
            canonicalRosterUsernames = List.copyOf(canonicalRosterUsernames);
        }
    }

    private record SidebarLine(String text, PacketNameResolver resolver) {}

    private static List<SidebarLine> readSidebarLineComponents() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return List.of();
        }

        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return List.of();
        }

        List<PlayerScoreEntry> entries = new ArrayList<>(scoreboard.listPlayerScores(sidebar));
        entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());

        List<SidebarLine> lines = new ArrayList<>();
        for (PlayerScoreEntry entry : entries) {
            Component display = entry.display();
            Component lineComponent = display != null ? display : Component.literal(entry.owner());
            PacketNameResolver resolver = PacketNameResolver.from(lineComponent);
            lines.add(new SidebarLine(resolver.text(), resolver));
        }
        return lines;
    }

    public static List<PartyHealth> readPartyHealth() {
        return readPartyHealthRowsWithoutFallbacks(readSidebarLineComponents(), false);
    }

    static PartyObservation readPartyObservation() {
        return observeParty(readSidebarLineComponents(), wynntilsPartyMembers());
    }

    static PartyObservation readPartyObservation(List<Component> lines, List<String> wynntilsPartyMembers) {
        return observeParty(lines.stream().map(line -> {
            PacketNameResolver resolver = PacketNameResolver.from(line);
            return new SidebarLine(resolver.text(), resolver);
        }).toList(), wynntilsPartyMembers);
    }

    private static PartyObservation observeParty(List<SidebarLine> sidebarLines, List<String> wynntilsPartyMembers) {
        boolean partySidebarActive = sidebarLines.stream()
                .map(SidebarLine::text)
                .map(String::trim)
                .anyMatch(WynnPartyScoreboardReader::isPartyHeaderLine);
        boolean raidSidebarActive = sidebarLines.stream()
                .map(SidebarLine::text)
                .map(String::trim)
                .anyMatch(WynnPartyScoreboardReader::isRaidHeaderLine);
        List<PartyHealth> partyHealth = readPartyHealthRowsWithoutFallbacks(sidebarLines, true);
        return new PartyObservation(
                partyHealth,
                matchingWynntilsPartyRoster(partyHealth, wynntilsPartyMembers),
                partySidebarActive,
                raidSidebarActive);
    }

    private static List<PartyHealth> readPartyHealthRowsWithoutFallbacks(
            List<SidebarLine> sidebarLines, boolean includeRaidSection) {
        List<PartyHealth> partyHealth = new ArrayList<>();
        boolean inPartySection = false;

        for (SidebarLine sidebarLine : sidebarLines) {
            String line = sidebarLine.text();
            String trimmedLine = line.trim();

            if (isHeaderLine(trimmedLine)) {
                inPartySection = isPartyHeaderLine(trimmedLine)
                        || (includeRaidSection && isRaidHeaderLine(trimmedLine));
                continue;
            }

            int trimOffset = line.indexOf(trimmedLine);
            ParsedPartyLine parsed = parsePartyLine(trimmedLine, inPartySection);
            if (parsed == null) {
                continue;
            }

            boolean alive = parsed.online()
                    && !sidebarLine.resolver().hasStrikethrough(
                            trimOffset + parsed.nicknameStart(),
                            trimOffset + parsed.nicknameEnd());
            boolean nicknameStyle = sidebarLine.resolver().hasItalic(
                    trimOffset + parsed.nicknameStart(),
                    trimOffset + parsed.nicknameEnd());
            boolean fullHealthBar = isFullHealthBar(sidebarLine.resolver(), trimOffset, parsed);

            var identity = sidebarLine.resolver().resolve(trimOffset + parsed.nicknameStart(),
                    trimOffset + parsed.nicknameEnd(), PacketNameResolver.Policy.TRUNCATED, "sidebar.member");
            String username = identity.username();
            if (identity.state() == PacketNameResolver.State.UNRESOLVED && !nicknameStyle)
                username = resolveVisiblePlayerUsername(parsed.nickname());

            SeqClient.LOGGER.debug(
                    "[WynnPartyScoreboard] Parsed nickname='{}' username='{}' hp={} level={} online={} alive={} fullBar={} italic={} line='{}'",
                    parsed.nickname(),
                    username,
                    parsed.hp(),
                    parsed.level(),
                    parsed.online(),
                    alive,
                    fullHealthBar,
                    nicknameStyle,
                    line);

            partyHealth.add(new PartyHealth(
                    parsed.nickname(),
                    username,
                    parsed.hp(),
                    parsed.level(),
                    parsed.online(),
                    alive,
                    fullHealthBar));
        }
        return partyHealth;
    }

    // The party model supplies a canonical roster, not identities for sidebar rows.
    // Its order can differ from the sidebar, and a single unresolved row may be a
    // different player from the local account. Never turn those guesses into aliases.
    static List<String> matchingWynntilsPartyRoster(
            List<PartyHealth> partyHealth, List<String> wynntilsPartyMembers) {
        if (partyHealth.isEmpty() || wynntilsPartyMembers.isEmpty()) {
            return List.of();
        }

        for (List<String> candidate : wynntilsPartyMemberCandidates(wynntilsPartyMembers)) {
            if (candidate.size() != partyHealth.size()) {
                continue;
            }
            boolean containsResolvedRows = partyHealth.stream()
                    .map(PartyHealth::username)
                    .filter(username -> username != null && !username.isBlank())
                    .allMatch(username -> containsIgnoreCase(candidate, username));
            if (containsResolvedRows) {
                return List.copyOf(candidate);
            }
        }
        return List.of();
    }

    private static List<List<String>> wynntilsPartyMemberCandidates(List<String> wynntilsPartyMembers) {
        List<List<String>> candidates = new ArrayList<>();
        if (!wynntilsPartyMembers.isEmpty()) {
            candidates.add(wynntilsPartyMembers);
        }

        String localUsername = localUsername();
        if (localUsername == null || containsIgnoreCase(wynntilsPartyMembers, localUsername)) {
            return List.copyOf(candidates);
        }

        List<String> localFirst = new ArrayList<>(wynntilsPartyMembers.size() + 1);
        localFirst.add(localUsername);
        localFirst.addAll(wynntilsPartyMembers);
        candidates.add(List.copyOf(localFirst));

        List<String> localLast = new ArrayList<>(wynntilsPartyMembers.size() + 1);
        localLast.addAll(wynntilsPartyMembers);
        localLast.add(localUsername);
        candidates.add(List.copyOf(localLast));
        return List.copyOf(candidates);
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        for (String candidate : values) {
            if (candidate.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }


    private static List<String> wynntilsPartyMembers() {
        try {
            Class<?> modelsClass = Class.forName("com.wynntils.core.components.Models");
            Object partyModel = modelsClass.getField("Party").get(null);
            Object members = partyModel.getClass().getMethod("getPartyMembers").invoke(partyModel);
            if (!(members instanceof List<?> rawMembers)) {
                return List.of();
            }

            List<String> usernames = new ArrayList<>(rawMembers.size());
            for (Object member : rawMembers) {
                String username = validUsername(member instanceof String value ? value : null);
                if (username == null) {
                    return List.of();
                }
                usernames.add(username);
            }
            return List.copyOf(usernames);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return List.of();
        }
    }

    private static boolean isHeaderLine(String line) {
        return line.contains("Party:") || line.contains("Raid:");
    }

    private static boolean isPartyHeaderLine(String line) {
        return line.contains("Party:");
    }

    private static boolean isRaidHeaderLine(String line) {
        return line.contains("Raid:");
    }

    static ParsedPartyLine parsePartyLine(String line, boolean allowPartyLine) {
        if (line == null || line.isBlank()) {
            return null;
        }

        if (!allowPartyLine) {
            return null;
        }

        ParsedPartyLine onlineLine = parseOnlinePartyLine(line);
        if (onlineLine != null) {
            return onlineLine;
        }

        return parseOfflinePartyLine(line);
    }

    private static ParsedPartyLine parseOnlinePartyLine(String line) {
        BracketedNumber level = lastBracketedNumber(line);
        if (level == null) {
            return null;
        }

        Matcher hpMatcher = FIRST_NUMBER.matcher(line);
        hpMatcher.region(0, level.start());
        if (!findStandaloneHealthNumber(line, hpMatcher)) {
            return null;
        }

        int nicknameStart = hpMatcher.end();
        while (nicknameStart < level.start() && !isNicknameCharacter(line.charAt(nicknameStart))) {
            nicknameStart++;
        }

        int nicknameEnd = level.start();
        while (nicknameEnd > nicknameStart && !isNicknameCharacter(line.charAt(nicknameEnd - 1))) {
            nicknameEnd--;
        }

        if (nicknameStart >= nicknameEnd) {
            return null;
        }

        String nickname = line.substring(nicknameStart, nicknameEnd).trim();
        if (nickname.isBlank()) {
            return null;
        }

        return new ParsedPartyLine(
                Integer.parseInt(hpMatcher.group(1)),
                nickname,
                nicknameStart,
                nicknameEnd,
                level.value(),
                healthBarStart(line, hpMatcher.start()),
                healthBarEnd(line, hpMatcher.end(), level.start()),
                true);
    }

    private static ParsedPartyLine parseOfflinePartyLine(String line) {
        if (!line.startsWith("-")) {
            return null;
        }
        if (lastBracketedNumber(line) != null) {
            return null;
        }

        int nicknameStart = 1;
        while (nicknameStart < line.length() && !isNicknameCharacter(line.charAt(nicknameStart))) {
            nicknameStart++;
        }

        int nicknameEnd = line.length();
        while (nicknameEnd > nicknameStart && !isNicknameCharacter(line.charAt(nicknameEnd - 1))) {
            nicknameEnd--;
        }

        if (nicknameStart >= nicknameEnd) {
            return null;
        }

        String nickname = line.substring(nicknameStart, nicknameEnd).trim();
        if (nickname.isBlank()) {
            return null;
        }
        return new ParsedPartyLine(0, nickname, nicknameStart, nicknameEnd, 0, -1, -1, false);
    }

    private static boolean isFullHealthBar(PacketNameResolver resolver, int trimOffset, ParsedPartyLine parsed) {
        if (parsed.healthBarStart() < 0 || parsed.healthBarEnd() < 0) {
            return false;
        }
        return resolver.hasOnlyRedHealthCharacters(
                trimOffset + parsed.healthBarStart() + 1,
                trimOffset + parsed.healthBarEnd());
    }

    private static int healthBarStart(String line, int hpStart) {
        return line.lastIndexOf('[', hpStart);
    }

    private static int healthBarEnd(String line, int hpEnd, int levelStart) {
        int end = line.indexOf(']', hpEnd);
        return end >= 0 && end < levelStart ? end : -1;
    }

    private static BracketedNumber lastBracketedNumber(String line) {
        Matcher matcher = BRACKETED_NUMBER.matcher(line);
        BracketedNumber lastMatch = null;
        while (matcher.find()) {
            lastMatch = new BracketedNumber(Integer.parseInt(matcher.group(1)), matcher.start(), matcher.end());
        }
        return lastMatch;
    }

    private static boolean findStandaloneHealthNumber(String line, Matcher matcher) {
        while (matcher.find()) {
            if (isStandaloneNumber(line, matcher.start(), matcher.end())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStandaloneNumber(String line, int startInclusive, int endExclusive) {
        return (startInclusive <= 0 || !isNameTokenCharacter(line.charAt(startInclusive - 1)))
                && (endExclusive >= line.length() || !isNameTokenCharacter(line.charAt(endExclusive)));
    }

    private static boolean isNameTokenCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private static boolean isNicknameCharacter(char value) {
        return isNameTokenCharacter(value) || Character.isWhitespace(value);
    }

    private static String resolveVisiblePlayerUsername(String nickname) {
        String key = nickname == null ? "" : nickname.trim().toLowerCase(Locale.ROOT);
        if (key.length() < 3) {
            return null;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return null;
        }

        String uniqueMatch = null;
        for (AbstractClientPlayer player : mc.level.players()) {
            String canonicalUsername = canonicalVisibleUsername(player);
            if (canonicalUsername == null) {
                continue;
            }

            for (String candidate : visibleNameCandidates(player)) {
                String candidateKey = normalizeName(candidate);
                if (candidateKey.isBlank()) {
                    continue;
                }
                if (candidateKey.equals(key) || candidateKey.startsWith(key)) {
                    if (uniqueMatch != null && !uniqueMatch.equalsIgnoreCase(canonicalUsername)) {
                        return null;
                    }
                    uniqueMatch = canonicalUsername;
                }
            }
        }

        return uniqueMatch;
    }

    private static List<String> visibleNameCandidates(AbstractClientPlayer player) {
        List<String> candidates = new ArrayList<>();
        candidates.add(player.getName().getString());
        candidates.add(player.getGameProfile().name());
        candidates.add(player.getScoreboardName());
        if (player.getCustomName() != null) {
            candidates.add(player.getCustomName().getString());
        }
        return candidates;
    }

    private static String canonicalVisibleUsername(AbstractClientPlayer player) {
        String profileName = validUsername(player.getGameProfile().name());
        if (profileName != null) {
            return profileName;
        }

        return validUsername(player.getScoreboardName());
    }

    private static String validUsername(String username) {
        if (username == null) {
            return null;
        }
        String trimmed = username.trim();
        return USERNAME_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    private static String localUsername() {
        if (SeqClient.mc != null && SeqClient.mc.getUser() != null) {
            String username = validUsername(SeqClient.mc.getUser().getName());
            if (username != null) {
                return username;
            }
        }
        if (SeqClient.mc != null && SeqClient.mc.player != null) {
            return validUsername(SeqClient.mc.player.getName().getString());
        }
        return null;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    record ParsedPartyLine(
            int hp,
            String nickname,
            int nicknameStart,
            int nicknameEnd,
            int level,
            int healthBarStart,
            int healthBarEnd,
            boolean online) {}

    private record BracketedNumber(int value, int start, int end) {}
}
