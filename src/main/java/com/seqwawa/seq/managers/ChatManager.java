package com.seqwawa.seq.managers;

import com.seqwawa.seq.mixins.ClientPacketListenerMixin;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import com.seqwawa.seq.accessors.NotificationAccessor;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.events.DiscordChatEvent;
import com.seqwawa.seq.integrations.WynntilsGuildRankAccess;
import com.seqwawa.seq.integrations.WynntilsItemPreviewAccess;
import com.seqwawa.seq.model.ChatItemPreview;
import com.seqwawa.seq.model.RankPresentation;
import com.seqwawa.seq.network.ConnectionManager;
import com.seqwawa.seq.network.WynncraftServerPolicy;
import com.seqwawa.seq.utils.ChatIdentityResolver;
import com.seqwawa.seq.utils.PacketTextNormalizer;
import com.seqwawa.seq.utils.RankGradientAnimation;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.seqwawa.seq.client.SeqClient.mc;

/**
 * Bridges Wynncraft guild chat ↔ Discord via the Sequoia backend.
 * <p>
 * Outgoing: intercepts Wynncraft guild chat, resolves nicknames to real
 * usernames
 * via component insertion tags, and sends to backend.
 * Incoming: displays Discord and forwarded guild chat WS messages in MC chat.
 */
public class ChatManager {

    /**
     * Aqua text color (0x55FFFF / §b) used by Wynncraft for guild chat messages.
     * Adapted from Wynntils' RecipientType.GUILD foreground pattern
     * {@code ^§b(...).*$}.
     * Other message types use different colors: DMs use §#ddcc99ff, party uses §e,
     * shout uses §#bd45ffff, territory/battle info uses §c, etc.
     */
    private static final int GUILD_CHAT_COLOR = 0x55FFFF;
    private static final String BACKEND_GUILD_NAME = "Sequoia";
    private static long nextAchievementSendAt;

    public static boolean canAnnounceAchievements() {
        if (SeqClient.getAnnounceAchievementsSetting() != null
                && !SeqClient.getAnnounceAchievementsSetting().getValue()) {
            return false;
        }
        if (mc.player == null || mc.level == null || mc.getUser() == null
                || !ConnectionManager.isConnected()
                || WynncraftServerPolicy.currentScope() != WynncraftServerPolicy.Scope.MAIN) {
            return false;
        }
        var session = SeqClient.getConfigManager().getStoredAuthSession();
        return session != null && mc.getUser().getProfileId().toString().equalsIgnoreCase(session.minecraftUuid())
                && shouldRelayForGuild(WynntilsGuildRankAccess.guildMembership(BACKEND_GUILD_NAME));
    }

    /** Sends the guild copy; the backend independently posts earned achievements to Campfire. */
    static void announceAchievements(List<String> messages, BooleanSupplier currentSession) {
        mc.execute(() -> {
            if (!currentSession.getAsBoolean() || !canAnnounceAchievements()) {
                return;
            }
            var connection = mc.player.connection;
            var player = mc.getUser().getProfileId();
            for (String message : messages) {
                if (message.isBlank() || message.length() > 240
                        || message.chars().anyMatch(c -> Character.isISOControl(c) || c == '§')) {
                    continue;
                }
                long now = System.currentTimeMillis();
                long delay = Math.max(0, nextAchievementSendAt - now);
                nextAchievementSendAt = now + delay + 3_000;
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> mc.execute(() -> {
                    if (currentSession.getAsBoolean() && canAnnounceAchievements()
                            && mc.player.connection == connection && mc.getUser().getProfileId().equals(player)) {
                        connection.sendCommand("g " + message);
                    }
                }));
            }
        });
    }
    // Nicknames may contain spaces (e.g. "Emanant Force"), so allow spaces in the
    // display-name capture group. DOTALL so the message group captures across \n.
    // Packet-level normalization strips the icon/banner glyph spam before matching.
    private static final Pattern CHAT_PATTERN = Pattern.compile(
            "^(?:<\\d+>\\s*)?([a-zA-Z0-9_][a-zA-Z0-9_ ]*[a-zA-Z0-9_]|[a-zA-Z0-9_]{3,16})\\s*:\\s*(.*)$",
            Pattern.DOTALL);
    private static final Pattern HOVER_REAL_NAME_PATTERN = Pattern.compile(
            // Wynncraft/Wynntils formats: "<nick>'s real name is <username>" and
            // "<nick>'s real username is <username>".
            // Also accepts nicknames ending in 's' where Wynncraft uses "<nick>' real name
            // is <username>"
            // Wynntils may reverse the description to "<username>'s nickname is <nick>".
            // Legacy format: "Real Username: <username>".
            "(?:(?:'(?:s)? real (?:user)?name is|Real Username:)\\s*([a-zA-Z0-9_]{3,16})"
                    + "|\\b([a-zA-Z0-9_]{3,16})'(?:s)? nickname is\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_NICKNAME_USERNAME_PATTERN = Pattern.compile(
            "^(?:<\\d+>\\s*)?([a-zA-Z0-9_][a-zA-Z0-9_ ]*?)\\s*"
                    + "\\(([a-zA-Z0-9_]{3,16})\\)(?=\\s|:|$)");
    private static final Pattern DISPLAY_NAME_PATTERN =
            Pattern.compile("[a-zA-Z0-9_][a-zA-Z0-9_ ]{0,63}");
    private static final Duration OUTGOING_DEDUPE_WINDOW = Duration.ofMillis(750);
    private static volatile String lastOutgoingKey;
    private static volatile Instant lastOutgoingAt = Instant.EPOCH;

    private static final Pattern WYNNCRAFT_WELCOME_PATTERN = Pattern.compile("Welcome to Wynncraft!",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLIANCE_PATTERN = Pattern.compile(
            "\\b(?<subject>.+?)\\s+(?<action>formed|revoked)\\s+(?:an|the)\\s+alliance\\s+with\\s+(?<object>.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYER_CHAT_BEFORE_ALLIANCE_PATTERN = Pattern.compile(
            "^[^:]{1,80}:\\s+.*\\balliance\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Duration ALLIANCE_DEDUPE_WINDOW = Duration.ofSeconds(5);
    private static volatile String lastAllianceKey;
    private static volatile Instant lastAllianceAt = Instant.EPOCH;
    private static final Pattern GUILD_MEMBERSHIP_ACTOR_FIRST_PATTERN = Pattern.compile(
            "^(?<actor>you|[a-zA-Z0-9_][a-zA-Z0-9_ ]{2,63}?)\\s+"
                    + "(?:have\\s+|has\\s+)?(?:successfully\\s+)?"
                    + "(?<verb>invited|uninvited)\\s+"
                    + "(?<target>[a-zA-Z0-9_][a-zA-Z0-9_ ]{2,63}?)\\s+"
                    + "(?:(?:to\\s+(?:join\\s+)?|from\\s+)(?:(?:the|your)\\s+)?guild)[.!]?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GUILD_MEMBERSHIP_TARGET_FIRST_PATTERN = Pattern.compile(
            "^(?<target>[a-zA-Z0-9_][a-zA-Z0-9_ ]{2,63}?)\\s+(?:was|has\\s+been)\\s+"
                    + "(?<verb>invited|uninvited)\\s+"
                    + "(?:(?:to\\s+(?:join\\s+)?|from\\s+)(?:(?:the|your)\\s+)?guild)\\s+by\\s+"
                    + "(?<actor>[a-zA-Z0-9_][a-zA-Z0-9_ ]{2,63}?)[.!]?$",
            Pattern.CASE_INSENSITIVE);
    private static final Duration GUILD_MEMBERSHIP_DEDUPE_WINDOW = Duration.ofSeconds(5);
    private static volatile String lastGuildMembershipKey;
    private static volatile Instant lastGuildMembershipAt = Instant.EPOCH;

    private static boolean firstConnect = true;

    public ChatManager() {
        registerIncomingHook();
        ClientSendMessageEvents.ALLOW_COMMAND.register(ChatManager::allowQueueGuildCommand);
    }

    private static boolean allowQueueGuildCommand(String command) {
        return allowQueueGuildCommand(command, ServerQueueDetector.isInQueue(), message -> {
            if (!shouldRelayForLocalGuild()) return false;
            String username = currentMinecraftUsername();
            if (username == null) return false;
            String avatarUrl = "https://mc-heads.net/avatar/"
                    + URLEncoder.encode(username, StandardCharsets.UTF_8).replace("+", "%20") + "/128";
            return ConnectionManager.getInstance().sendGuildChat(
                    username, null, message, avatarUrl, List.of(), true);
        }, () -> NotificationAccessor.notifyPlayer(
                "Guild chat message could not be sent."));
    }

    /** Returns whether the command should continue to Wynncraft; handled queue messages never do. */
    static boolean allowQueueGuildCommand(
            String command, boolean inQueue, Predicate<String> sendMessage, Runnable notifyFailure) {
        String message = queueGuildCommandMessage(command);
        if (!inQueue || message == null) return true;
        if (!sendMessage.test(message)) notifyFailure.run();
        return false;
    }

    static String queueGuildCommandMessage(String command) {
        if (command == null || command.length() < 3 || command.charAt(0) != 'g'
                || !Character.isWhitespace(command.charAt(1))) {
            return null;
        }
        String message = command.substring(2).trim();
        return message.isBlank() ? null : message;
    }

    // ── Outgoing: Wynncraft guild → backend → Discord ──

    /**
     * Called from {@link ClientPacketListenerMixin} at the
     * packet level, before Wynntils or Fabric's message API can cancel/reformat
     * the message. This ensures multiline guild messages are never missed.
     */
    public static void onSystemChat(Component message) {
        // Bumliotech goon parser to auto goon.
        if (SeqClient.getAutoConnectSetting().getValue() && isWynncraftWelcomeMessage(message) && mc.player != null
                && !ConnectionManager.getInstance().isOpen() && firstConnect) {
            firstConnect = !firstConnect;
            mc.execute(() -> mc.player.connection.sendCommand("seq connect"));
        }

        ParsedAllianceUpdate allianceUpdate = parseAllianceUpdate(message);
        if (allianceUpdate != null && ConnectionManager.isConnected() && shouldRelayForLocalGuild()) {
            if (isDuplicateAllianceUpdate(allianceUpdate.action(), allianceUpdate.guildName())) {
                SeqClient.LOGGER.debug(
                        "[GuildAlliance] Duplicate alliance update ignored action='{}' guild='{}'",
                        allianceUpdate.action(),
                        allianceUpdate.guildName());
            } else {
                SeqClient.LOGGER.info(
                        "[GuildAlliance] Forwarding alliance update action='{}' guild='{}'",
                        allianceUpdate.action(),
                        allianceUpdate.guildName());
                ConnectionManager.getInstance().sendGuildAllianceUpdate(
                        allianceUpdate.action(), allianceUpdate.guildName());
            }
        }

        GuildRankEventParser.Event rankEvent = GuildRankEventParser.parse(message, currentMinecraftUsername());
        if (rankEvent != null && shouldRelayForLocalGuild()) {
            ConnectionManager.observeGuildRankEvent(rankEvent);
        }

        ParsedGuildMembershipEvent membershipEvent = parseGuildMembershipEvent(message, currentMinecraftUsername());
        if (membershipEvent != null && ConnectionManager.isConnected() && shouldRelayForLocalGuild()) {
            if (isDuplicateGuildMembershipEvent(membershipEvent)) {
                SeqClient.LOGGER.debug(
                        "[GuildMembership] Duplicate event ignored action='{}' actor='{}' target='{}'",
                        membershipEvent.action(),
                        membershipEvent.actor(),
                        membershipEvent.target());
            } else {
                SeqClient.LOGGER.info(
                        "[GuildMembership] Forwarding event action='{}' actor='{}' target='{}'",
                        membershipEvent.action(),
                        membershipEvent.actor(),
                        membershipEvent.target());
                ConnectionManager.getInstance().sendGuildMembershipEvent(
                        membershipEvent.action(), membershipEvent.actor(), membershipEvent.target());
            }
        }

        observeNicknameMappings(message);

        // Guild chat uses aqua color (§b / 0x55FFFF) per Wynntils' RecipientType.GUILD.
        // This cleanly rejects DMs, party, shout, territory, and other message types
        // that share the \uDAFF\uDFFC icon prefix but use different colors.
        if (!hasLeadingGuildChatColor(message))
            return;

        if (!ConnectionManager.isConnected())
            return;

        if (!shouldRelayForLocalGuild())
            return;

        ParsedMessage parsed = parseGuildMessage(message);
        if (parsed == null)
            return;

        if (isDuplicateOutgoing(parsed.username(), parsed.message())) {
            SeqClient.LOGGER.debug(
                    "[GuildChat] Duplicate outgoing guild chat ignored username='{}' content='{}'",
                    parsed.username(),
                    parsed.message());
            return;
        }

        SeqClient.LOGGER.info(
                "[GuildChat] Forwarding parsed guild chat username='{}' nickname='{}' content='{}' avatar='{}'",
                parsed.username(),
                parsed.nickname(),
                parsed.message(),
                parsed.avatarUrl());

        ConnectionManager.getInstance().sendGuildChat(
                parsed.username(),
                parsed.nickname(),
                parsed.message(),
                parsed.avatarUrl(),
                parsed.itemPreviews());
    }

    static void observeNicknameMappings(Component message) {
        String cleaned = PacketTextNormalizer.normalizeForParsing(message == null ? null : message.getString());
        Matcher inlineMatcher = LEADING_NICKNAME_USERNAME_PATTERN.matcher(cleaned);
        if (inlineMatcher.find()) {
            NicknameResolverCache.remember(inlineMatcher.group(1), inlineMatcher.group(2));
        }

        if (message != null) {
            for (Component fragment : message.toFlatList()) {
                String displayedName = PacketTextNormalizer.normalizeForParsing(fragment.getString()).trim();
                if (!DISPLAY_NAME_PATTERN.matcher(displayedName).matches()) {
                    continue;
                }

                String realUsername = extractHoverRealUsername(fragment.getStyle());
                if (realUsername == null) {
                    realUsername = extractInsertionUsername(fragment.getStyle());
                }
                if (realUsername != null) {
                    NicknameResolverCache.remember(displayedName, realUsername);
                }
            }
        }

        Matcher matcher = CHAT_PATTERN.matcher(cleaned);
        if (!matcher.find()) {
            return;
        }

        String displayedName = matcher.group(1).trim();
        String realUsername = findRealUsername(message, displayedName);
        if (realUsername == null || !realUsername.matches("[a-zA-Z0-9_]{3,16}")) {
            SeqClient.LOGGER.debug(
                    "[NicknameResolver] Chat observe no real username displayed='{}' real='{}' cleaned='{}'",
                    displayedName,
                    realUsername,
                    cleaned);
            return;
        }

        SeqClient.LOGGER.debug(
                "[NicknameResolver] Chat observe displayed='{}' real='{}' cleaned='{}'",
                displayedName,
                realUsername,
                cleaned);
        NicknameResolverCache.remember(displayedName, realUsername);
    }

    private static boolean shouldRelayForLocalGuild() {
        WynntilsGuildRankAccess.GuildMembership membership =
                WynntilsGuildRankAccess.guildMembership(BACKEND_GUILD_NAME);
        boolean shouldRelay = shouldRelayForGuild(membership);
        if (!shouldRelay) {
            SeqClient.LOGGER.debug(
                    "[GuildChat] Dropping guild chat relay: local Wynntils guild='{}' expected='{}'",
                    membership == null ? null : membership.currentGuildName(),
                    BACKEND_GUILD_NAME);
        }
        return shouldRelay;
    }

    private static String currentMinecraftUsername() {
        return mc.getUser() == null ? null : mc.getUser().getName();
    }

    static boolean shouldRelayForGuild(WynntilsGuildRankAccess.GuildMembership membership) {
        return membership != null && membership.available() && membership.inExpectedGuild();
    }

    static boolean hasLeadingGuildChatColor(Component message) {
        if (message == null) {
            return false;
        }

        var rootColor = message.getStyle().getColor();
        if (rootColor != null) {
            return rootColor.getValue() == GUILD_CHAT_COLOR;
        }

        Optional<Boolean> leadingColorIsGuild = message.visit((style, text) -> {
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            var color = style.getColor();
            return Optional.of(color != null && color.getValue() == GUILD_CHAT_COLOR);
        }, Style.EMPTY);
        return leadingColorIsGuild.orElse(false);
    }

    /**
     * Extracts real username and message content from Wynncraft guild chat.
     * 
     * <p>
     * Wynncraft sends nicknames as the visible text, but puts the real username
     * in the Style's insertion field (used for shift-click @mentions). We look for
     * the component with a valid username insertion to identify the speaker.
     */
    static ParsedMessage parseGuildMessage(Component message) {
        String cleaned = PacketTextNormalizer.normalizeForParsing(message == null ? null : message.getString());
        Matcher matcher = CHAT_PATTERN.matcher(cleaned);

        if (!matcher.find())
            return null;

        String displayedName = matcher.group(1).trim();
        String content = matcher.group(2).trim();
        WynntilsItemPreviewAccess.Result itemPreviewResult =
                WynntilsItemPreviewAccess.extract(extractRawContent(message.getString()));
        String previewCleanedContent = PacketTextNormalizer.normalizeForParsing(itemPreviewResult.message());
        if (!previewCleanedContent.isBlank()) {
            content = previewCleanedContent;
        }

        if (content.isEmpty())
            return null;

        // Search the component tree for the real username. Prefer candidates that
        // differ from the displayed nickname (e.g. nicked/decorated names).
        String realUsername = findRealUsername(message, displayedName);
        String avatarUsername = resolveAvatarUsername(displayedName, realUsername);
        if (avatarUsername == null) {
            SeqClient.LOGGER.warn(
                    "[GuildChat] Dropping guild chat: no avatar-safe username displayed='{}' real='{}'",
                    displayedName,
                    realUsername);
            return null;
        }

        if (!avatarUsername.equals(displayedName) && realUsername == null) {
            SeqClient.LOGGER.debug(
                    "[GuildChat] Using normalized fallback username='{}' from displayed='{}'",
                    avatarUsername,
                    displayedName);
        }

        String avatarUrl = "https://mc-heads.net/avatar/"
                + URLEncoder.encode(avatarUsername, StandardCharsets.UTF_8).replace("+", "%20")
                + "/128";
        String nickname = deriveNickname(displayedName, avatarUsername);
        NicknameResolverCache.remember(displayedName, avatarUsername);
        return new ParsedMessage(avatarUsername, nickname, content, avatarUrl, itemPreviewResult.previews());
    }

    private static String extractRawContent(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }
        int colonIndex = rawText.indexOf(':');
        if (colonIndex < 0 || colonIndex == rawText.length() - 1) {
            return "";
        }
        return rawText.substring(colonIndex + 1).trim();
    }

    static boolean isWynncraftWelcomeMessage(Component message) {
        String normalized = PacketTextNormalizer.normalizeForParsing(message == null ? null : message.getString());
        if (normalized.isEmpty()) {
            return false;
        }

        Matcher welcomeMatcher = WYNNCRAFT_WELCOME_PATTERN.matcher(normalized);
        return welcomeMatcher.find() && normalized.contains("play.wynncraft.com");
    }

    static ParsedAllianceUpdate parseAllianceUpdate(Component message) {
        String normalized = PacketTextNormalizer.normalizeForParsing(message == null ? null : message.getString());
        if (normalized.isEmpty() || !normalized.toLowerCase(java.util.Locale.ROOT).contains("alliance")) {
            return null;
        }
        if (parseGuildMessage(message) != null) {
            return null;
        }
        if (PLAYER_CHAT_BEFORE_ALLIANCE_PATTERN.matcher(normalized).matches()) {
            return null;
        }

        Matcher matcher = ALLIANCE_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return null;
        }
        String action = matcher.group("action").equalsIgnoreCase("formed") ? "formed" : "revoked";
        String guildName = alliancePartner(action, matcher.group("subject"), matcher.group("object"));
        if (!isValidAllianceGuildName(guildName)) {
            return null;
        }
        return new ParsedAllianceUpdate(action, guildName);
    }

    static ParsedGuildMembershipEvent parseGuildMembershipEvent(Component message, String localUsername) {
        String normalized = PacketTextNormalizer.normalizeForParsing(message == null ? null : message.getString());
        if (normalized.isEmpty() || parseGuildMessage(message) != null) {
            return null;
        }

        String systemMessage = normalized.replaceFirst("(?i)^\\[guild]\\s*", "");
        Matcher actorFirst = GUILD_MEMBERSHIP_ACTOR_FIRST_PATTERN.matcher(systemMessage);
        if (actorFirst.matches()) {
            String actor = actorFirst.group("actor");
            if ("you".equalsIgnoreCase(actor)) {
                actor = localUsername;
            }
            return guildMembershipEvent(
                    actorFirst.group("verb"),
                    resolveGuildMembershipUsername(message, actor),
                    resolveGuildMembershipUsername(message, actorFirst.group("target")));
        }

        Matcher targetFirst = GUILD_MEMBERSHIP_TARGET_FIRST_PATTERN.matcher(systemMessage);
        if (targetFirst.matches()) {
            return guildMembershipEvent(
                    targetFirst.group("verb"),
                    resolveGuildMembershipUsername(message, targetFirst.group("actor")),
                    resolveGuildMembershipUsername(message, targetFirst.group("target")));
        }
        return null;
    }

    private static String resolveGuildMembershipUsername(Component message, String displayedName) {
        if (displayedName == null) {
            return null;
        }
        String normalizedDisplayName = displayedName.trim();
        if (message != null) {
            for (Component fragment : message.toFlatList()) {
                String fragmentText = PacketTextNormalizer.normalizeForParsing(fragment.getString()).trim();
                if (!normalizedDisplayName.equalsIgnoreCase(fragmentText)) {
                    continue;
                }
                String username = extractHoverRealUsername(fragment.getStyle());
                if (username == null) {
                    username = extractInsertionUsername(fragment.getStyle());
                }
                if (ChatIdentityResolver.isValidUsername(username)) {
                    return username;
                }
            }
        }

        String cachedUsername = NicknameResolverCache.resolveUsername(normalizedDisplayName);
        return ChatIdentityResolver.isValidUsername(cachedUsername) ? cachedUsername : normalizedDisplayName;
    }

    private static ParsedGuildMembershipEvent guildMembershipEvent(String verb, String actor, String target) {
        if (!ChatIdentityResolver.isValidUsername(actor) || !ChatIdentityResolver.isValidUsername(target)) {
            return null;
        }
        String action;
        if ("invited".equalsIgnoreCase(verb)) {
            action = "invited";
        } else if ("uninvited".equalsIgnoreCase(verb)) {
            action = "uninvited";
        } else {
            return null;
        }
        return new ParsedGuildMembershipEvent(action, actor, target);
    }

    private static String alliancePartner(String action, String subject, String object) {
        String subjectGuild = cleanAllianceGuildName(subject);
        String objectGuild = cleanAllianceGuildName(object);
        if (BACKEND_GUILD_NAME.equalsIgnoreCase(subjectGuild)) {
            return objectGuild;
        }
        if (BACKEND_GUILD_NAME.equalsIgnoreCase(objectGuild)) {
            return subjectGuild;
        }
        if ("revoked".equals(action)) {
            return objectGuild;
        }
        return null;
    }

    private static String cleanAllianceGuildName(String guildName) {
        return guildName == null ? "" : guildName.trim();
    }

    private static boolean isValidAllianceGuildName(String guildName) {
        return guildName != null && !guildName.isEmpty() && guildName.length() <= 64 && !guildName.contains(":");
    }

    private static String deriveNickname(String displayedName, String actualUsername) {
        if (displayedName == null) {
            return null;
        }
        String trimmed = displayedName.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (actualUsername != null && trimmed.equalsIgnoreCase(actualUsername)) {
            return null;
        }
        return trimmed;
    }

    private static String resolveAvatarUsername(String displayedName, String realUsername) {
        if (realUsername != null && realUsername.matches("[a-zA-Z0-9_]{3,16}")) {
            return realUsername;
        }
        if (displayedName != null && displayedName.matches("[a-zA-Z0-9_]{3,16}")) {
            return displayedName;
        }

        String normalized = displayedName == null ? "" : displayedName.replaceAll("[^a-zA-Z0-9_]", "");
        if (normalized.matches("[a-zA-Z0-9_]{3,16}")) {
            return normalized;
        }

        return null;
    }

    private static boolean isDuplicateOutgoing(String username, String message) {
        String key = username + "\u0000" + message;
        Instant now = Instant.now();

        synchronized (ChatManager.class) {
            if (key.equals(lastOutgoingKey)
                    && Duration.between(lastOutgoingAt, now).compareTo(OUTGOING_DEDUPE_WINDOW) < 0) {
                return true;
            }
            lastOutgoingKey = key;
            lastOutgoingAt = now;
            return false;
        }
    }

    private static boolean isDuplicateAllianceUpdate(String action, String guildName) {
        String key = action + "\u0000" + guildName.toLowerCase(java.util.Locale.ROOT);
        Instant now = Instant.now();

        synchronized (ChatManager.class) {
            if (key.equals(lastAllianceKey)
                    && Duration.between(lastAllianceAt, now).compareTo(ALLIANCE_DEDUPE_WINDOW) < 0) {
                return true;
            }
            lastAllianceKey = key;
            lastAllianceAt = now;
            return false;
        }
    }

    private static boolean isDuplicateGuildMembershipEvent(ParsedGuildMembershipEvent event) {
        String key = event.action() + "\u0000" + event.actor().toLowerCase(java.util.Locale.ROOT) + "\u0000"
                + event.target().toLowerCase(java.util.Locale.ROOT);
        Instant now = Instant.now();

        synchronized (ChatManager.class) {
            if (key.equals(lastGuildMembershipKey)
                    && Duration.between(lastGuildMembershipAt, now).compareTo(GUILD_MEMBERSHIP_DEDUPE_WINDOW) < 0) {
                return true;
            }
            lastGuildMembershipKey = key;
            lastGuildMembershipAt = now;
            return false;
        }
    }

    private static String findRealUsername(Component component, String displayedName) {
        List<String> usernames = extractRealUsernames(component);
        if (usernames.isEmpty()) {
            return null;
        }

        if (displayedName == null || displayedName.isBlank()) {
            return usernames.get(0);
        }

        String displayedTrimmed = displayedName.trim();
        String displayedLower = displayedTrimmed.toLowerCase();

        String bestPrefixExpansion = null;
        for (String candidate : usernames) {
            if (candidate == null) {
                continue;
            }
            String candidateLower = candidate.toLowerCase();
            if (candidateLower.equals(displayedLower)) {
                continue;
            }
            if (candidateLower.startsWith(displayedLower)
                    && candidate.length() > displayedTrimmed.length()
                    && (bestPrefixExpansion == null || candidate.length() > bestPrefixExpansion.length())) {
                bestPrefixExpansion = candidate;
            }
        }
        if (bestPrefixExpansion != null) {
            return bestPrefixExpansion;
        }

        for (String candidate : usernames) {
            if (candidate != null && !candidate.equalsIgnoreCase(displayedTrimmed)) {
                return candidate;
            }
        }

        return usernames.get(0);
    }

    static String resolvePacketUsername(Component component, String displayedName) {
        String realUsername = findRealUsername(component, displayedName);
        if (realUsername != null && realUsername.matches("[a-zA-Z0-9_]{3,16}")) {
            return realUsername;
        }
        if (displayedName != null && displayedName.matches("[a-zA-Z0-9_]{3,16}")) {
            return displayedName;
        }
        return null;
    }

    public static List<String> extractRealUsernames(Component component) {
        if (component == null) {
            return List.of();
        }

        Set<String> usernames = new LinkedHashSet<>();
        collectHoverRealUsernames(component, usernames);
        collectInsertionUsernames(component, usernames);
        return List.copyOf(new ArrayList<>(usernames));
    }

    public static List<String> extractHoverRealUsernames(Component component) {
        if (component == null) {
            return List.of();
        }

        Set<String> usernames = new LinkedHashSet<>();
        collectHoverRealUsernames(component, usernames);
        return List.copyOf(new ArrayList<>(usernames));
    }

    public static List<String> extractInsertionUsernames(Component component) {
        if (component == null) {
            return List.of();
        }

        Set<String> usernames = new LinkedHashSet<>();
        collectInsertionUsernames(component, usernames);
        return List.copyOf(new ArrayList<>(usernames));
    }

    private static void collectHoverRealUsernames(Component component, Set<String> usernames) {
        if (component == null) {
            return;
        }

        String hoverUsername = extractHoverRealUsername(component.getStyle());
        if (hoverUsername != null) {
            usernames.add(hoverUsername);
        }

        for (Component sibling : component.getSiblings()) {
            collectHoverRealUsernames(sibling, usernames);
        }
    }

    private static void collectInsertionUsernames(Component component, Set<String> usernames) {
        if (component == null) {
            return;
        }

        String insertionUsername = extractInsertionUsername(component.getStyle());
        if (insertionUsername != null) {
            usernames.add(insertionUsername);
        }

        for (Component sibling : component.getSiblings()) {
            collectInsertionUsernames(sibling, usernames);
        }
    }

    static String extractHoverRealUsername(Style style) {
        if (style == null) {
            return null;
        }

        HoverEvent hoverEvent = style.getHoverEvent();
        if (!(hoverEvent instanceof HoverEvent.ShowText showTextEvent)) {
            return null;
        }

        Component hoverComponent = showTextEvent.value();
        if (hoverComponent == null) {
            return null;
        }

        String hoverText = PacketTextNormalizer.normalizeForParsing(hoverComponent.getString());
        Matcher matcher = HOVER_REAL_NAME_PATTERN.matcher(hoverText);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(2) == null ? matcher.group(1) : matcher.group(2);
    }

    static String extractInsertionUsername(Style style) {
        if (style == null) {
            return null;
        }

        String insertion = style.getInsertion();
        if (insertion == null || !insertion.matches("[a-zA-Z0-9_]{3,16}")) {
            return null;
        }
        return insertion;
    }

    // ── Incoming: backend → WS → MC chat ──

    private void registerIncomingHook() {
        ConnectionManager.onGuildChat(msg -> mc.execute(() -> {
            if (mc.player == null) return;
            // Client-only display bypasses the packet hook that relays server guild chat.
            RankPresentation rank = DiscordRankChatDecorator.guildRank(msg.username());
            List<String> lines = splitMessageLines(msg.message());
            for (int index = 0; index < lines.size(); index++) {
                MutableComponent line = index == 0
                        ? guildSenderLine(msg, lines.get(index), rank)
                        : guildContinuationLine(lines.get(index));
                DiscordRankChatDecorator.displayGuildLine(line, () -> mc.player.displayClientMessage(line, false));
            }
        }));
        ConnectionManager.onDiscordChat(msg -> {
            if (!SeqClient.getShowDiscordChatSetting().getValue())
                return;

            mc.execute(() -> {
                if (mc.player != null) {
                    displayBridgeMessage(msg);
                }

                if (SeqClient.getEventBus() != null) {
                    SeqClient.getEventBus().dispatch(new DiscordChatEvent(msg.username(), msg.message()));
                }
            });
        });
    }

    static MutableComponent guildSenderLine(
            ConnectionManager.GuildChatMessage msg, String text, RankPresentation rank) {
        return RankGradientAnimation.batchRegistrations(() -> {
            MutableComponent line = Component.empty().append(DiscordRankChatDecorator.guildSenderPrefix());
            TextColor textColor = DiscordRankChatDecorator.inGameGuildChatTextColor();
            if (rank != null) {
                line.append(DiscordRankChatDecorator.rankPill(rank, null, textColor, msg.username()))
                        .append(Component.literal(" "));
            }
            boolean hasNickname = msg.nickname() != null && !msg.nickname().isBlank();
            String name = hasNickname ? msg.nickname() : msg.username();
            Style identity = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)
                    .withInsertion(msg.username()).withItalic(hasNickname);
            if (!name.equals(msg.username())) {
                identity = identity.withHoverEvent(new HoverEvent.ShowText(
                        Component.empty()
                                .append(Component.literal(msg.username() + "'s ").withStyle(ChatFormatting.WHITE))
                                .append(Component.literal("nickname is ").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal(name).withStyle(ChatFormatting.WHITE))));
            }
            line.append(rank == null ? Component.literal(name).withStyle(identity)
                    : DiscordRankChatDecorator.colouredName(name, rank, identity));
            MutableComponent insignia = DiscordRankChatDecorator.guildInsignia(msg.username());
            if (insignia != null) line.append(insignia);
            return line.append(Component.literal(": ").withStyle(ChatFormatting.DARK_AQUA))
                    .append(Component.literal(text).withStyle(style -> style.withColor(textColor)));
        });
    }

    static MutableComponent guildContinuationLine(String text) {
        return Component.empty().append(DiscordRankChatDecorator.guildPrefix(true))
                .append(Component.literal(text).withStyle(style ->
                        style.withColor(DiscordRankChatDecorator.inGameGuildChatTextColor())));
    }

    /**
     * Renders a bridged Discord message. Ranked senders use the guild-chat-shaped
     * Discord presentation when bridge colouring is enabled; otherwise the legacy
     * Sequoia pill and white text keep an uncoloured bridge message identifiable.
     * <p>
     * A message carrying newlines becomes one chat line per line rather than one line
     * with breaks embedded in it. Minecraft draws an embedded break flush against the
     * left margin, which loses the marker column and detaches the rest of the message
     * from its sender; separate lines each receive the appropriate bridge prefix.
     */
    private void displayBridgeMessage(ConnectionManager.DiscordChatMessage msg) {
        List<String> lines = splitMessageLines(msg.message());
        RankPresentation rank = DiscordRankChatDecorator.bridgeRank(msg.username(), msg.discordId());

        for (int index = 0; index < lines.size(); index++) {
            MutableComponent line = index == 0
                    ? bridgeSenderLine(msg, lines.get(index), rank)
                    : bridgeContinuationLine(lines.get(index), rank != null);
            DiscordRankChatDecorator.displayUndecorated(
                    line, () -> mc.player.displayClientMessage(line, false), rank != null);
        }
    }

    static MutableComponent bridgeSenderLine(
            ConnectionManager.DiscordChatMessage msg, String text, RankPresentation rank) {
        return RankGradientAnimation.batchRegistrations(() -> bridgeSenderLineNow(msg, text, rank));
    }

    private static MutableComponent bridgeSenderLineNow(
            ConnectionManager.DiscordChatMessage msg, String text, RankPresentation rank) {
        if (rank == null) {
            return NotificationAccessor.prefixComponent()
                    .append(Component.literal(msg.username())
                            .withStyle(style -> style
                                    .withColor(ChatFormatting.WHITE)
                                    .withInsertion(msg.username())))
                    .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(text).withStyle(style -> style.withColor(
                            DiscordRankChatDecorator.discordChatTextColor())));
        }

        MutableComponent line = Component.empty().append(DiscordRankChatDecorator.bridgePrefix());
        line.append(DiscordRankChatDecorator.rankPill(
                        rank, null, DiscordRankChatDecorator.discordChatTextColor()))
                .append(Component.literal(" "));
        String displayName = bridgeDisplayName(msg.username(), rank);
        // Same shift-click insertion Wynncraft puts on in-game names, so a bridged
        // sender links to their profile just like a guild one.
        line.append(DiscordRankChatDecorator.colouredName(
                displayName,
                rank,
                Style.EMPTY.withColor(ChatFormatting.WHITE).withInsertion(displayName)));
        MutableComponent insignia = DiscordRankChatDecorator.bridgeInsignia(msg.username(), msg.discordId());
        if (insignia != null) {
            line.append(insignia);
        }
        TextColor textColor = DiscordRankChatDecorator.discordChatTextColor();
        return line.append(Component.literal(": ").withStyle(style -> style.withColor(textColor)))
                .append(Component.literal(text).withStyle(style -> style.withColor(textColor)));
    }

    /**
     * Discord nicknames may include the member's progression role, for example
     * {@code Treant OwORawr}. The bridge already draws that role in the pill, so omit
     * the matching leading label from the visible sender name.
     */
    static String bridgeDisplayName(String senderName, RankPresentation rank) {
        if (senderName == null || rank == null) {
            return senderName;
        }

        String trimmedName = senderName.strip();
        String rankLabel = rank.label().strip();
        if (trimmedName.length() <= rankLabel.length()
                || !trimmedName.regionMatches(true, 0, rankLabel, 0, rankLabel.length())
                || !Character.isWhitespace(trimmedName.charAt(rankLabel.length()))) {
            return senderName;
        }

        String withoutRank = trimmedName.substring(rankLabel.length()).stripLeading();
        return withoutRank.isBlank() ? senderName : withoutRank;
    }

    static MutableComponent bridgeContinuationLine(String text, boolean colored) {
        if (!colored) {
            return NotificationAccessor.prefixComponent()
                    .append(Component.literal(text).withStyle(style -> style.withColor(
                            DiscordRankChatDecorator.discordChatTextColor())));
        }
        TextColor textColor = DiscordRankChatDecorator.discordChatTextColor();
        return Component.empty()
                .append(DiscordRankChatDecorator.bridgePrefix())
                .append(Component.literal(text).withStyle(style -> style.withColor(textColor)));
    }

    /**
     * Splits a bridged message on any newline, dropping trailing blank lines: Discord
     * messages often end with one, and it would draw a rail under nothing.
     */
    static List<String> splitMessageLines(String message) {
        if (message == null || message.isEmpty()) {
            return List.of("");
        }
        List<String> lines = new ArrayList<>(Arrays.asList(message.split("\\R", -1)));
        while (lines.size() > 1 && lines.getLast().isBlank()) {
            lines.removeLast();
        }
        return List.copyOf(lines);
    }

    record ParsedMessage(
            String username, String nickname, String message, String avatarUrl, List<ChatItemPreview> itemPreviews) {
    }

    record ParsedAllianceUpdate(String action, String guildName) {
    }

    record ParsedGuildMembershipEvent(String action, String actor, String target) {
    }
}
