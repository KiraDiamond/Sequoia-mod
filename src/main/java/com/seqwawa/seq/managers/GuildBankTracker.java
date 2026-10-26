package com.seqwawa.seq.managers;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.integrations.WynntilsGuildRankAccess;
import com.seqwawa.seq.network.ConnectionManager;
import com.seqwawa.seq.utils.PacketTextNormalizer;

/**
 * Detects Wynncraft guild-bank messages and forwards structured events to the
 * backend over WebSocket.
 */
public final class GuildBankTracker {
    private static final String BACKEND_GUILD_NAME = "Sequoia";
    private static final Pattern GUILD_BANK_PATTERN = Pattern.compile(
            "^(.+?)\\s+(deposited|withdrew)\\s+(.+?)\\s+(to|from)\\s+the Guild Bank\\s+\\((.+)\\)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GUILD_WITHOUT_TIER_PATTERN = Pattern.compile(
            "^(.+?)\\s+(deposited|withdrew)\\s+(.+?)\\s+(to|from)\\s+the Guild(?: Bank)?$",
            Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_ACCESS_TIER = "Unknown";
    private static final Pattern QUANTITY_PATTERN = Pattern.compile("^(?:(\\d+)x\\s+)?(.+)$");
    private static final Pattern CHARGES_PATTERN = Pattern.compile("^(.*?)(?:\\s+\\[([^\\]]+)\\])?$");
    private static final Duration DEDUPE_WINDOW = Duration.ofSeconds(2);

    private static GuildBankTracker instance;

    private volatile String lastSentMessage;
    private volatile Instant lastSentAt = Instant.EPOCH;

    public static synchronized GuildBankTracker getInstance() {
        if (instance == null) {
            instance = new GuildBankTracker();
        }
        return instance;
    }

    public void onSystemChat(Component message) {
        GuildBankEvent event = parseEvent(message);
        if (event == null) {
            return;
        }
        if (!shouldForwardForLocalGuild()) {
            return;
        }
        if (isDuplicate(event.rawMessage(), Instant.now())) {
            SeqClient.LOGGER.debug("[GuildBank] Duplicate guild-bank message suppressed: {}", event.rawMessage());
            return;
        }
        ConnectionManager.getInstance().sendGuildBankEvent(
                event.action().wireValue(),
                event.player(),
                event.quantity(),
                event.itemName(),
                event.charges(),
                event.accessTier(),
                event.rawMessage());
    }

    private static boolean shouldForwardForLocalGuild() {
        WynntilsGuildRankAccess.GuildMembership membership =
                WynntilsGuildRankAccess.guildMembership(BACKEND_GUILD_NAME);
        boolean shouldForward = shouldForwardForGuild(membership);
        if (!shouldForward) {
            SeqClient.LOGGER.debug(
                    "[GuildBank] Dropping guild-bank event: local Wynntils guild='{}' expected='{}'",
                    membership == null ? null : membership.currentGuildName(),
                    BACKEND_GUILD_NAME);
        }
        return shouldForward;
    }

    static boolean shouldForwardForGuild(WynntilsGuildRankAccess.GuildMembership membership) {
        // Wynntils is optional, so only a positively identified different guild is
        // rejected here. The backend still authorizes every forwarded event.
        if (membership == null || !membership.available()) {
            return true;
        }
        String currentGuildName = membership.currentGuildName();
        return currentGuildName == null || currentGuildName.isBlank() || membership.inExpectedGuild();
    }

    GuildBankEvent parseEvent(Component message) {
        if (message == null) {
            return null;
        }

        GuildBankEvent parsed = parseEvent(message.getString());
        if (parsed == null) {
            return null;
        }

        PacketNameResolver resolver = PacketNameResolver.from(message);
        String cleaned = stripGuildChatPrefix(resolver.text().trim());
        Matcher actor = GUILD_BANK_PATTERN.matcher(cleaned);
        if (!actor.matches()) actor = GUILD_WITHOUT_TIER_PATTERN.matcher(cleaned);
        if (!actor.matches()) return null;
        int offset = resolver.text().indexOf(cleaned);
        String resolvedPlayer = resolver.resolve(actor.start(1) + offset, actor.end(1) + offset,
                PacketNameResolver.Policy.CHAT, "bank.actor").username();
        if (resolvedPlayer == null) {
            SeqClient.LOGGER.warn(
                    "[GuildBank] Dropping guild-bank message: no real username for displayed player '{}'",
                    parsed.player());
            return null;
        }

        if (resolvedPlayer.equals(parsed.player())) {
            return parsed;
        }

        return new GuildBankEvent(
                parsed.action(),
                resolvedPlayer,
                parsed.quantity(),
                parsed.itemName(),
                parsed.charges(),
                parsed.accessTier(),
                parsed.rawMessage());
    }

    GuildBankEvent parseEvent(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        if (!rawText.contains("Guild")) {
            return null;
        }
        if (!rawText.contains("deposited") && !rawText.contains("withdrew")) {
            return null;
        }

        String cleaned = stripGuildChatPrefix(normalizeForParsing(rawText));

        Matcher matcher = GUILD_BANK_PATTERN.matcher(cleaned);
        String accessTier;
        if (matcher.matches()) {
            accessTier = matcher.group(5).trim();
        } else {
            matcher = GUILD_WITHOUT_TIER_PATTERN.matcher(cleaned);
            if (!matcher.matches()) {
                return null;
            }
            accessTier = DEFAULT_ACCESS_TIER;
        }

        String player = matcher.group(1).trim();
        String actionToken = matcher.group(2).toLowerCase();
        String itemBlock = matcher.group(3).trim();
        if (player.isEmpty() || itemBlock.isEmpty()) {
            return null;
        }

        Matcher quantityMatcher = QUANTITY_PATTERN.matcher(itemBlock);
        if (!quantityMatcher.matches()) {
            return null;
        }

        Integer quantity = quantityMatcher.group(1) == null ? null : Integer.parseInt(quantityMatcher.group(1));
        String itemWithOptionalCharges = quantityMatcher.group(2).trim();

        Matcher chargesMatcher = CHARGES_PATTERN.matcher(itemWithOptionalCharges);
        if (!chargesMatcher.matches()) {
            return null;
        }

        String itemName = chargesMatcher.group(1).trim();
        if (itemName.isEmpty()) {
            return null;
        }

        return new GuildBankEvent(
                "deposited".equals(actionToken) ? GuildBankAction.DEPOSIT : GuildBankAction.WITHDRAWAL,
                player,
                quantity,
                itemName,
                chargesMatcher.group(2),
                accessTier,
                cleaned);
    }

    static String normalizeForParsing(String rawText) {
        return PacketTextNormalizer.normalizeForParsing(rawText);
    }

    private static String stripGuildChatPrefix(String rawText) {
        int chatPrefixIndex = rawText.indexOf("[CHAT/GUILD]");
        if (chatPrefixIndex >= 0) {
            return rawText.substring(chatPrefixIndex + "[CHAT/GUILD]".length()).trim();
        }
        return rawText;
    }

    private synchronized boolean isDuplicate(String rawMessage, Instant now) {
        if (Objects.equals(rawMessage, lastSentMessage)
                && Duration.between(lastSentAt, now).compareTo(DEDUPE_WINDOW) < 0) {
            return true;
        }
        lastSentMessage = rawMessage;
        lastSentAt = now;
        return false;
    }

    enum GuildBankAction {
        DEPOSIT,
        WITHDRAWAL;

        String wireValue() {
            return this == DEPOSIT ? "deposit" : "withdrawal";
        }
    }

    record GuildBankEvent(
            GuildBankAction action,
            String player,
            Integer quantity,
            String itemName,
            String charges,
            String accessTier,
            String rawMessage) {
    }
}
