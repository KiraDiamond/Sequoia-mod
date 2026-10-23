package com.seqwawa.seq.managers;

import com.seqwawa.seq.accessors.NotificationAccessor;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.integrations.WynntilsGuildRankAccess;
import com.seqwawa.seq.model.RankPresentation;
import com.seqwawa.seq.model.SeqBadgeTier;
import com.seqwawa.seq.utils.ColorRamp;
import com.seqwawa.seq.utils.ComponentTextEditor;
import com.seqwawa.seq.utils.PacketTextNormalizer;
import com.seqwawa.seq.utils.RankGradientAnimation;
import com.seqwawa.seq.utils.WynncraftTextShaderColor;
import com.seqwawa.seq.utils.WynnPillGlyphs;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;

/**
 * Swaps the Wynncraft guild rank shown in chat for the member's Sequoia Discord
 * rank, gives guild bridge messages the same badge, and applies the same member
 * colours to party-chat names without adding a badge there.
 * <p>
 * In guild chat Wynncraft draws the in-game rank as a glyph "pill"
 * ({@code RECRUITER}); this replaces that pill in place with the linked Discord
 * progression rank ({@code SAPLING}) and leaves the rest of the line alone,
 * including Wynntils' timestamps, nickname rendering and shift-click
 * insertions. Members with no linked Discord rank keep their Wynncraft rank.
 */
public final class DiscordRankChatDecorator {

    /**
     * Wynncraft guild ranks; every other pill (account ranks, etc.) is left alone.
     * Ordered longest first so {@code recruiter} wins over {@code recruit}.
     */
    private static final List<String> WYNNCRAFT_GUILD_RANKS =
            List.of("strategist", "recruiter", "recruit", "captain", "chief", "owner");

    /** Used when the backend publishes no colour for a rank, e.g. Upper Strategist. */
    private static final TextColor FALLBACK_RANK_COLOR = TextColor.fromLegacyFormat(ChatFormatting.DARK_GREEN);

    /**
     * The one colour every pill label is drawn in.
     * <p>
     * Labels used to be tinted with their own pill's colour and flipped between a light
     * and a dark grey depending on it, which left the palest and the most saturated
     * roles with a label barely separable from its background, and made one rank read
     * differently from one member to the next. A single dark tone, untinted, holds its
     * contrast across the pastels and mid brights Discord roles are actually set to.
     * It is deliberately short of pure black, which looks like a hole punched in the
     * pill at chat's glyph size.
     */
    static final TextColor PILL_LABEL_COLOR =
            RankGradientAnimation.markDecorationColor(TextColor.fromRgb(0x1F2126));

    /** Aqua Wynncraft uses for guild chat; see {@code ChatManager}. */
    private static final int GUILD_CHAT_COLOR = 0x55FFFF;

    /** Dark aqua Wynntils uses for the speaker name and trailing separator. */
    private static final int GUILD_SPEAKER_COLOR = 0x00AAAA;

    /** Yellow Wynncraft uses for party chat; see Wynntils' party recipient pattern. */
    private static final int PARTY_CHAT_COLOR = 0xFFFF55;

    /** Complete primary party marker; individual glyphs can also occur inside guild pills. */
    private static final String PARTY_MARKER_GLYPHS =
            "\uDAFF\uDFFC\uE005\uDBFF\uDFFF\uE002\uDBFF\uDFFE";

    /** Bar drawn down the left of a bridge block, used until a real one is captured. */
    static final String BRIDGE_CONTINUATION_GLYPH = "\uF8F1";
    /** Discord mark dropped into the middle of Wynncraft's arrow. */
    static final String BRIDGE_ICON_GLYPH = "\uF8F2";
    /** One pixel of positive advance, repeated to widen a prefix. */
    private static final char BRIDGE_ADVANCE_PAD = '\uF8FD';
    /** One pixel of negative advance, repeated to narrow one. */
    private static final char BRIDGE_ADVANCE_TRIM = '\uF8FE';
    /** Discord blurple, so a bridged line reads as Discord at a glance. */
    private static final TextColor DISCORD_ACCENT = TextColor.fromRgb(0x5865F2);
    private static final FontDescription BRIDGE_PREFIX_FONT = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath("seq", "discord_bridge"));

    /** Insignia glyphs, in tier order; see {@code assets/seq/font/insignia.json}. */
    private static final FontDescription INSIGNIA_FONT =
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("seq", "insignia"));
    private static final Map<SeqBadgeTier, String> INSIGNIA_GLYPHS = Map.of(
            SeqBadgeTier.BRONZE, "\uF8E0",
            SeqBadgeTier.SILVER, "\uF8E1",
            SeqBadgeTier.GOLD, "\uF8E2",
            SeqBadgeTier.DIAMOND, "\uF8E3");

    private static final Map<SeqBadgeTier, ChatFormatting> INSIGNIA_COLORS = Map.of(
            SeqBadgeTier.BRONZE, ChatFormatting.GOLD,
            SeqBadgeTier.SILVER, ChatFormatting.GRAY,
            SeqBadgeTier.GOLD, ChatFormatting.YELLOW,
            SeqBadgeTier.DIAMOND, ChatFormatting.AQUA);

    /** Legacy formatting marker, which Wynncraft and add-ons embed straight in the text. */
    private static final char LEGACY_FORMATTING_PREFIX = '§';

    /** Wynntils renders nicked players as {@code Nickname(RealUsername)}. */
    /** Account name before a slash, allowing the nickname after it to contain spaces. */
    /** Account name after a slash, allowing the nickname before it to contain spaces. */

    private static volatile boolean debug;
    private static boolean suppressed;
    private static boolean bridgeSequenceOpen;
    private static boolean guildSequenceOpen;
    /** The last line this mod displayed itself, kept so it can be recognised by identity. */
    private static Component lastBridgeLine;
    /** Bridge components and their exact styled rail while Minecraft may still rewrap them. */
    private static final ArrayDeque<ColoredBridgeLine> RECENT_COLORED_BRIDGE_LINES = new ArrayDeque<>();
    private static final int MAX_REMEMBERED_BRIDGE_LINES = 128;

    private record ColoredBridgeLine(Component message, Component continuationPrefix) {}

    private DiscordRankChatDecorator() {}

    // ── Wynncraft guild chat ──

    /**
     * Returns {@code message} with its Wynncraft guild rank pill replaced by the
     * speaker's Discord rank, or the untouched {@code message} when the line is not
     * guild chat or the speaker has no linked rank.
     */
    public static Component decorateGuildChat(Component message) {
        return RankGradientAnimation.batchRegistrations(() -> decorateGuildChatNow(message));
    }

    private static Component decorateGuildChatNow(Component message) {
        // Every ordinary chat line ends the current bridge block. A bridged line must
        // not, so it is recognised by identity as well as by the suppression flag:
        // another mod may queue the message and deliver it here after
        // displayUndecorated has returned, closing the very block it opened.
        boolean ownLine = suppressed || message == lastBridgeLine
                || RECENT_COLORED_BRIDGE_LINES.stream().anyMatch(line -> line.message() == message);
        if (!ownLine) {
            bridgeSequenceOpen = false;
            guildSequenceOpen = false;
        }

        if (message == null || ownLine) {
            return message;
        }

        Component decorated = message;
        boolean guildCandidate = WynnPillGlyphs.containsPill(message.getString());
        boolean knownGuildRank = guildCandidate && hasKnownGuildRankPill(message);
        // Party markers also contain BMP private-use glyphs, so containsPill() is true
        // for them. Detect the channel independently and give the more specific party
        // shape precedence instead of letting the broad guild pre-check mask it.
        boolean partyCandidate = isPartyChatCandidate(message);
        if (isEnabled() && (guildCandidate || partyCandidate)) {
            DiscordRankService service = DiscordRankService.getInstance();
            if (!service.hasRanks()) {
                logInspection(message, "no linked ranks loaded yet");
            } else {
                try {
                    decorated = decorateSupportedChat(
                            message,
                            service::presentationForVerifiedMinecraftUsername,
                            service::presentationForVerifiedMinecraftUsername,
                            guildCandidate,
                            partyCandidate,
                            SequoiaGuildMembership.getInstance()::isMember);
                    logInspection(
                            message,
                            decorated == message
                                    ? "no supported chat speaker or unlinked speaker"
                                    : "rewritten");
                } catch (RuntimeException exception) {
                    SeqClient.LOGGER.debug("[DiscordRanks] Failed to decorate chat line.", exception);
                }
            }
        }
        return knownGuildRank ? recolourGuildMessageText(decorated, inGameGuildChatTextColor()) : decorated;
    }

    /** Channel selection core, parameterised so the private-use overlap stays testable. */
    static Component decorateSupportedChat(
            Component message,
            Function<String, RankPresentation> rankLookup,
            boolean guildCandidate,
            boolean partyCandidate) {
        return decorateSupportedChat(message, rankLookup, rankLookup, guildCandidate, partyCandidate, username -> true);
    }

    static Component decorateSupportedChat(
            Component message,
            Function<String, RankPresentation> guildRankLookup,
            Function<String, RankPresentation> partyRankLookup,
            boolean guildCandidate,
            boolean partyCandidate,
            Predicate<String> sequoiaMember) {
        if (partyCandidate) {
            return partyChatColoringEnabled() ? decoratePartyChat(message, partyRankLookup) : message;
        }
        return guildCandidate
                ? decorateGuildChat(message, username -> guildRank(username, guildRankLookup, sequoiaMember))
                : message;
    }

    /** Rank alone is insufficient: Discord members and aliases can belong to another game guild. */
    static RankPresentation guildRank(
            String username, Function<String, RankPresentation> rankLookup, Predicate<String> sequoiaMember) {
        return sequoiaMember.test(username) ? rankLookup.apply(username) : null;
    }

    /** Party colouring is independently optional beneath general username colouring. */
    static boolean partyChatColoringEnabled() {
        return SeqClient.getColorPartyChatSetting() == null
                || SeqClient.getColorPartyChatSetting().getValue();
    }

    /** Decoration core, parameterised on the rank lookup so it stays unit-testable. */
    static Component decorateGuildChat(Component message, Function<String, RankPresentation> rankLookup) {
        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(message);
        String text = ComponentTextEditor.textOf(fragments);

        for (WynnPillGlyphs.Pill pill : WynnPillGlyphs.findPills(text)) {
            String guildRank = guildRankOf(pill.label());
            if (guildRank == null) {
                continue;
            }

            Component rewritten = replaceBadge(message, fragments, text, pill, rankLookup, guildRank);
            if (rewritten != null) {
                return rewritten;
            }
        }

        // Fail closed when no known Wynncraft guild-rank pill is present. Other
        // channels use the same private-use glyph range, so guessing from a glyph's
        // position can turn system markers into a player's rank pill and insignia.
        return message;
    }

    /**
     * Colours a linked member's party-chat name while leaving the channel marker,
     * separator and message body exactly as Wynncraft supplied them. Party chat has
     * no guild-rank badge to replace, so this deliberately reuses only the existing
     * speaker resolution and name-colouring path.
     */
    static Component decoratePartyChat(Component message, Function<String, RankPresentation> rankLookup) {
        if (message == null) {
            return null;
        }

        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(message);
        String text = ComponentTextEditor.textOf(fragments);
        int nameStart = partySpeakerStart(fragments, text);
        if (nameStart < 0) {
            return message;
        }

        Speaker speaker = resolveSpeaker(message, rankLookup, fragments, text, nameStart);
        if (speaker == null) {
            return message;
        }

        int colonIndex = text.indexOf(':', nameStart);
        int nameEnd = speaker.wholeDisplayName()
                ? colonIndex
                : speakerNameEnd(fragments, text, nameStart, colonIndex, speaker.username());
        List<ComponentTextEditor.Fragment> recoloured =
                recolourName(fragments, nameStart, nameEnd, speaker.rank(), speaker.username());
        return rebuild(recoloured);
    }

    /**
     * Finds the first visible character after Wynncraft's yellow party marker. The
     * marker check keeps other yellow messages with a colon out, while locating it by
     * style still works when Wynntils has prepended a timestamp.
     */
    private static int partySpeakerStart(List<ComponentTextEditor.Fragment> fragments, String text) {
        int primaryMarker = text.indexOf(PARTY_MARKER_GLYPHS);
        if (primaryMarker >= 0) {
            int start = decorationEnd(text, primaryMarker, text.length());
            return start < text.length() ? start : -1;
        }

        int cursor = 0;
        for (ComponentTextEditor.Fragment fragment : fragments) {
            TextColor color = fragment.style().getColor();
            int fragmentEnd = cursor + fragment.text().length();
            if (color != null && color.getValue() == PARTY_CHAT_COLOR) {
                for (int index = cursor; index < fragmentEnd; ) {
                    int codePoint = text.codePointAt(index);
                    if (isDecoration(codePoint)) {
                        int start = decorationEnd(text, index, text.length());
                        return start < text.length() ? start : -1;
                    }
                    index += Character.charCount(codePoint);
                }
            }
            cursor = fragmentEnd;
        }
        return -1;
    }

    /** Cheap gate before the party decorator allocates a flattened fragment list. */
    static boolean isPartyChatCandidate(Component message) {
        if (message.getString().contains(PARTY_MARKER_GLYPHS)) {
            return true;
        }
        return message.visit((style, text) -> {
            TextColor color = style.getColor();
            boolean yellowMarker = color != null
                    && color.getValue() == PARTY_CHAT_COLOR
                    && text.codePoints().anyMatch(DiscordRankChatDecorator::isDecoration);
            return yellowMarker
                    ? Optional.of(true)
                    : Optional.empty();
        }, Style.EMPTY).orElse(false);
    }

    private static Component replaceBadge(
            Component message,
            List<ComponentTextEditor.Fragment> fragments,
            String text,
            WynnPillGlyphs.Pill badge,
            Function<String, RankPresentation> rankLookup,
            String replacedGuildRank) {
        int colonIndex = text.indexOf(':', badge.endExclusive());
        if (colonIndex < 0) {
            return null;
        }
        Speaker speaker = resolveSpeaker(message, rankLookup, fragments, text, badge.endExclusive());
        if (speaker == null) {
            return null;
        }
        RankPresentation rank = speaker.rank();

        int start = decorationStart(fragments, text, badge.start());
        GuildChatMarkers.observe(fragments, start);
        int end = decorationEnd(text, badge.endExclusive(), colonIndex);

        int nameEnd = speaker.wholeDisplayName()
                ? colonIndex
                : speakerNameEnd(fragments, text, end, colonIndex, speaker.username());
        // The resolved username is stamped on as the shift-click insertion too: names
        // are matched from the displayed "Nick(Username)" text rather than from an
        // insertion, so one is not guaranteed to be there, and where Wynncraft does set
        // it a nicked player's carries the nickname rather than the account.
        String speakerName = speaker.username();
        List<ComponentTextEditor.Fragment> recoloured = recolourName(
                fragments, end, nameEnd, rank, speakerName);

        // The pill keeps the default font on purpose: Wynncraft's badge uses a font of
        // its own in which these glyphs mean nothing, so inheriting it collapses the
        // pill to no width and drags the rest of the line left.
        MutableComponent replacement = Component.empty()
                .append(rankPill(rank, replacedGuildRank, inGameGuildChatTextColor(), speaker.username()))
                .append(Component.literal(" "));
        List<ComponentTextEditor.Fragment> withBadge =
                insertInsignia(recoloured, colonIndex, speaker.username());
        return ComponentTextEditor.replaceRange(withBadge, start, end, replacement);
    }

    /**
     * Upper bound of the speaker's displayed name, i.e. where Wynncraft's
     * {@code Nickname(RealUsername)} reveal begins.
     * <p>
     * The cut is the first fragment carrying a complete resolved username token.
     * A substring of a nickname, such as reyzhia in reyzhianator, is not a reveal.
     * Token boundaries are checked across the full name region, since styles may
     * split a nickname into several fragments. Colours and brackets are not usable:
     * Wynncraft and Wynntils both build these lines with legacy {@code §} codes
     * inside the text, so a fragment's own colour says nothing about how it renders.
     * When the name and the reveal share one fragment there is no safe cut, and this
     * returns {@code nameStart} so nothing is recoloured: leaving the name plain is
     * harmless, repainting the reveal is not.
     */
    static int speakerNameEnd(
            List<ComponentTextEditor.Fragment> fragments,
            String text,
            int nameStart,
            int colonIndex,
            String username) {
        if (username == null || username.isBlank()) {
            return colonIndex;
        }
        String region = text.substring(nameStart, colonIndex);
        if (region.strip().equalsIgnoreCase(username)) {
            return colonIndex;
        }

        // The reveal is appended as a legacy "§c(...)" run inside the text, so the
        // first section sign already marks the end of the name.
        int legacyCode = region.indexOf(LEGACY_FORMATTING_PREFIX);
        if (legacyCode >= 0) {
            return nameStart + legacyCode;
        }

        // Add-ons may expose a nicked speaker as "username/nickname" (or the
        // reverse). Both halves belong to the displayed speaker, so the complete
        // region should receive the role colour. Stopping at the slash used to leave
        // the nickname in Wynncraft's dark-aqua base colour, most noticeably for
        // solid-colour roles that remain a single text fragment.
        if (region.indexOf('/') >= 0) {
            return colonIndex;
        }

        Matcher accountName = Pattern.compile(
                        "(?<![a-zA-Z0-9_])" + Pattern.quote(username) + "(?![a-zA-Z0-9_])",
                        Pattern.CASE_INSENSITIVE)
                .matcher(region);
        if (!accountName.find()) {
            return colonIndex;
        }
        int accountNameStart = nameStart + accountName.start();

        int cursor = 0;
        for (ComponentTextEditor.Fragment fragment : fragments) {
            int fragmentStart = cursor;
            cursor += fragment.text().length();
            if (cursor <= nameStart || fragmentStart >= colonIndex) {
                continue;
            }
            if (accountNameStart < fragmentStart || accountNameStart >= cursor) {
                continue;
            }
            // A fragment that is exactly the username is the name itself, not a
            // reveal, so recolour it and stop after it.
            return fragment.text().strip().equalsIgnoreCase(username)
                    ? Math.min(colonIndex, cursor)
                    : Math.max(nameStart, fragmentStart);
        }
        return colonIndex;
    }

    /**
     * Where the replacement starts: the later of the two bounds below, so both have
     * to agree before a character is dropped.
     */
    static int decorationStart(List<ComponentTextEditor.Fragment> fragments, String text, int badgeStart) {
        return Math.max(badgeFontRunStart(fragments, badgeStart), glyphRunStart(text, badgeStart));
    }

    /**
     * Walks back over glyphs and spaces, stopping at the first visible character.
     * This is what protects a line whose badge shares the default font with the rest
     * of it: a bridged message, say, where a timestamp added by another mod sits
     * right before the pill and must not be swallowed.
     */
    static int glyphRunStart(String text, int badgeStart) {
        int start = badgeStart;
        int index = badgeStart;
        while (index > 0) {
            int codePoint = text.codePointBefore(index);
            if (!isDecoration(codePoint) && codePoint != ' ') {
                break;
            }
            index -= Character.charCount(codePoint);
            if (codePoint != ' ') {
                start = index;
            }
        }
        return start;
    }

    /**
     * Start of the run of fragments sharing the badge's font.
     * <p>
     * Wynncraft draws a guild line with three fonts: one for the guild icon, one for
     * the rank badge, and the default one for the text. The badge's own pieces
     * (background blocks, corners and letters) all sit in the badge font even though
     * they differ in colour, while the icon does not. The font boundary is therefore
     * exactly where the badge begins, and it keeps the icon whole without having to
     * recognise its codepoints.
     */
    static int badgeFontRunStart(List<ComponentTextEditor.Fragment> fragments, int badgeStart) {
        FontDescription badgeFont = fontAt(fragments, badgeStart);
        int runStart = 0;
        FontDescription runFont = null;
        int cursor = 0;

        for (ComponentTextEditor.Fragment fragment : fragments) {
            int fragmentStart = cursor;
            cursor += fragment.text().length();

            FontDescription font = fragment.style().getFont();
            if (fragmentStart == 0 || !Objects.equals(font, runFont)) {
                runFont = font;
                runStart = fragmentStart;
            }
            if (cursor > badgeStart) {
                return Objects.equals(font, badgeFont) ? runStart : badgeStart;
            }
        }
        return badgeStart;
    }

    /**
     * Widens the replaced span forwards to where the speaker's name begins, so no
     * fragment of the original badge survives between the new pill and the name.
     * Spaces are absorbed here because the replacement re-adds one.
     */
    static int decorationEnd(String text, int badgeEnd, int limit) {
        int end = badgeEnd;
        while (end < limit) {
            int codePoint = text.codePointAt(end);
            if (!isDecoration(codePoint) && codePoint != ' ') {
                break;
            }
            end += Character.charCount(codePoint);
        }
        return end;
    }

    /**
     * Wynncraft's chat decoration glyphs. The categories mirror
     * {@link PacketTextNormalizer}: the badge and icon glyphs are spread across
     * private-use <em>and</em> unassigned codepoints, and missing the unassigned ones
     * cuts the walk short, leaving the old pill showing behind the new one.
     * <p>
     * Glyphs this mod draws are excluded. They are private use too, so counting them
     * would let the walk swallow a marker or an insignia this very class had put there.
     */
    private static boolean isDecoration(int codePoint) {
        if (codePoint <= Character.MAX_VALUE && WynnPillGlyphs.isModGlyph((char) codePoint)) {
            return false;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL,
                    Character.FORMAT,
                    Character.PRIVATE_USE,
                    Character.SURROGATE,
                    Character.UNASSIGNED -> true;
            default -> false;
        };
    }

    /** The font of the fragment holding {@code index}, so the new pill renders identically. */
    private static FontDescription fontAt(List<ComponentTextEditor.Fragment> fragments, int index) {
        return styleAt(fragments, index).getFont();
    }

    /** The style of the fragment holding {@code index}, or {@link Style#EMPTY}. */
    private static Style styleAt(List<ComponentTextEditor.Fragment> fragments, int index) {
        int cursor = 0;
        for (ComponentTextEditor.Fragment fragment : fragments) {
            cursor += fragment.text().length();
            if (cursor > index) {
                return fragment.style();
            }
        }
        return Style.EMPTY;
    }

    static Component recolourGuildMessageText(Component message, TextColor textColor) {
        if (message == null || textColor == null || !WynnPillGlyphs.containsPill(message.getString())) {
            return message;
        }

        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(message);
        String text = ComponentTextEditor.textOf(fragments);
        int messageStart = guildMessageStart(fragments, text);
        if (messageStart < 0 || messageStart >= text.length()) {
            return message;
        }

        List<ComponentTextEditor.Fragment> recoloured = ComponentTextEditor.restyleRange(
                fragments,
                messageStart,
                text.length(),
                style -> isGuildMessageText(style) ? style.withColor(textColor) : style);
        if (recoloured.equals(fragments)) {
            return message;
        }

        return rebuild(recoloured);
    }

    /** Reassembles edited fragments without dropping any non-colour style metadata. */
    private static MutableComponent rebuild(List<ComponentTextEditor.Fragment> fragments) {
        MutableComponent result = Component.empty();
        for (ComponentTextEditor.Fragment fragment : fragments) {
            result.append(Component.literal(fragment.text()).withStyle(fragment.style()));
        }
        return result;
    }

    private static int guildMessageStart(List<ComponentTextEditor.Fragment> fragments, String text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != ':' || !isGuildSeparatorColor(styleAt(fragments, index).getColor())) {
                continue;
            }
            int start = index + 1;
            while (start < text.length() && text.charAt(start) == ' ') {
                start++;
            }
            return start;
        }
        return -1;
    }

    private static boolean isGuildChatColor(TextColor color) {
        return color != null && color.getValue() == GUILD_CHAT_COLOR;
    }

    /** Guild marker glyphs share aqua with the body but live in Wynncraft's icon font. */
    private static boolean isGuildMessageText(Style style) {
        return FontDescription.DEFAULT.equals(style.getFont()) && isGuildChatColor(style.getColor());
    }

    private static boolean isGuildSeparatorColor(TextColor color) {
        if (color == null) {
            return false;
        }
        int value = color.getValue();
        return value == GUILD_SPEAKER_COLOR || value == GUILD_CHAT_COLOR;
    }

    /**
     * The Wynncraft guild rank a decoded pill label denotes, or {@code null} for any
     * other badge. A suffix match tolerates a stray leading glyph decoding into the
     * label, which happens when Wynncraft pads a badge with an extra font character.
     */
    static String guildRankOf(String pillLabel) {
        String normalized = pillLabel == null ? "" : pillLabel.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        for (String rank : WYNNCRAFT_GUILD_RANKS) {
            if (normalized.endsWith(rank)) {
                return rank;
            }
        }
        return null;
    }

    private static boolean hasKnownGuildRankPill(Component message) {
        return WynnPillGlyphs.findPills(message.getString()).stream()
                .anyMatch(pill -> guildRankOf(pill.label()) != null);
    }

    /**
     * Identifies the speaker from the name region between the rank pill and the
     * {@code ':'} separator, so player names mentioned inside the message body are
     * never mistaken for the sender.
     */
    private static Speaker resolveSpeaker(
            Component message, Function<String, RankPresentation> rankLookup,
            List<ComponentTextEditor.Fragment> fragments, String text, int pillEnd) {
        int colon = text.indexOf(':', pillEnd);
        if (colon < 0) return null;
        var result = PacketNameResolver.from(message).resolveRaw(text, pillEnd, colon,
                PacketNameResolver.Policy.CHAT, "chat.speaker");
        if (result.username() == null) return null;
        RankPresentation rank = rankLookup.apply(result.username());
        return rank == null ? null : new Speaker(result.username(), rank,
                metadataCoversEmbeddedUsername(fragments, pillEnd, colon, result.username()));
    }

    /**
     * Whether metadata resolved an account name embedded in an otherwise plain,
     * unrevealed nickname. In that shape the username-looking word is part of the
     * nickname itself, not a separately styled reveal to stop before.
     */
    private static boolean metadataCoversEmbeddedUsername(
            List<ComponentTextEditor.Fragment> fragments, int start, int endExclusive, String username) {
        int cursor = 0;
        for (ComponentTextEditor.Fragment fragment : fragments) {
            int fragmentStart = cursor;
            cursor += fragment.text().length();
            if (cursor <= start || fragmentStart >= endExclusive) {
                continue;
            }

            String insertion = PacketNameResolver.extractInsertionUsername(fragment.style());
            String hovered = PacketNameResolver.extractHoverRealUsername(fragment.style());
            if (!username.equalsIgnoreCase(insertion == null ? "" : insertion)
                    && !username.equalsIgnoreCase(hovered == null ? "" : hovered)) {
                continue;
            }

            String fragmentName = PacketTextNormalizer.normalizeForParsing(fragment.text());
            if (isPlainEmbeddedUsername(fragmentName, username)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlainEmbeddedUsername(String displayedName, String username) {
        if (displayedName == null
                || displayedName.indexOf(' ') < 0
                || displayedName.indexOf('/') >= 0
                || displayedName.indexOf('(') >= 0
                || displayedName.indexOf('[') >= 0) {
            return false;
        }
        for (String token : displayedName.split("[^a-zA-Z0-9_]+")) {
            if (token.equalsIgnoreCase(username)) {
                return true;
            }
        }
        return false;
    }

    /** The player a guild chat line belongs to, and the rank that was matched for them. */
    private record Speaker(String username, RankPresentation rank, boolean wholeDisplayName) {}

    // ── Discord guild bridge ──

    static RankPresentation guildRank(String username) {
        return isEnabled()
                ? guildRank(username, DiscordRankService.getInstance()::presentationForVerifiedMinecraftUsername,
                        SequoiaGuildMembership.getInstance()::isMember)
                : null;
    }

    static MutableComponent guildInsignia(String username) {
        SeqBadgeTier tier = insigniaEnabled() && SequoiaGuildMembership.getInstance().isMember(username)
                ? insigniaOf(username) : null;
        return tier == null ? null : insigniaBadge(tier);
    }

    static MutableComponent guildPrefix(boolean continuation) {
        GuildChatMarkers.Marker marker = continuation ? GuildChatMarkers.bar() : GuildChatMarkers.arrow();
        MutableComponent prefix = marker == null
                ? Component.literal(continuation ? "│ " : "➜ ").withStyle(ChatFormatting.AQUA)
                : Component.empty().append(Component.literal(marker.glyphs()).withStyle(marker.style()))
                        .append(continuation ? Component.empty() : Component.literal(" "));
        return continuation ? alignToGuildColumn(prefix, true) : prefix;
    }

    /** Uses the guild arrow for a new block, then the bar for consecutive relayed messages. */
    static MutableComponent guildSenderPrefix() {
        return guildPrefix(guildSequenceOpen);
    }

    /** Retains guild wrapping and bypasses re-decoration without opening a Discord block. */
    static void displayGuildLine(Component line, Runnable display) {
        rememberColoredBridgeLine(line, guildPrefix(true));
        displayUndecorated(line, display, false);
        guildSequenceOpen = true;
    }

    /** Chat clearing and disconnects must start the next relayed message with a full prefix. */
    public static void resetChatSequences() {
        bridgeSequenceOpen = false;
        guildSequenceOpen = false;
        lastBridgeLine = null;
        RECENT_COLORED_BRIDGE_LINES.clear();
    }

    /**
     * Rank of a bridge message sender, or {@code null} when they have none. Callers
     * use it for both the badge and the sender name colour.
     */
    public static RankPresentation bridgeRank(String senderName, String discordId) {
        return bridgeColoringEnabled()
                ? DiscordRankService.getInstance().presentationForBridgeSender(senderName, discordId)
                : null;
    }

    /** Bridge colours are a child of in-game Discord rank decoration. */
    static boolean bridgeColoringEnabled() {
        return isEnabled()
                && SeqClient.getColorDiscordBridgeSetting() != null
                && SeqClient.getColorDiscordBridgeSetting().getValue();
    }

    public static TextColor discordChatTextColor() {
        return TextColor.fromRgb(SeqClient.getDiscordChatTextColorSetting() == null
                ? GUILD_CHAT_COLOR
                : SeqClient.getDiscordChatTextColorSetting().getValue());
    }

    static TextColor inGameGuildChatTextColor() {
        return TextColor.fromRgb(SeqClient.getInGameGuildChatTextColorSetting() == null
                ? GUILD_CHAT_COLOR
                : SeqClient.getInGameGuildChatTextColorSetting().getValue());
    }

    /**
     * Prefix for the next Discord bridge line: the Discord arrow at the start of a
     * block, then an aligned vertical bar while bridge messages remain consecutive.
     */
    public static MutableComponent bridgePrefix() {
        return alignToGuildColumn(bridgeSequenceOpen ? continuationBar() : discordMark());
    }

    /** Pure continuation form used for visual lines created by Minecraft's wrapping. */
    private static MutableComponent bridgeContinuationPrefix() {
        return alignToGuildColumn(continuationBar());
    }

    /** Exact styled rail retained for {@code message}, or {@code null} for ordinary chat. */
    public static Component bridgeContinuationPrefixFor(Component message) {
        if (message == null) {
            return null;
        }
        for (ColoredBridgeLine bridgeLine : RECENT_COLORED_BRIDGE_LINES) {
            if (bridgeLine.message() == message) {
                return bridgeLine.continuationPrefix();
            }
        }
        // Structural fallback for another mod that copied the component before it
        // reached ChatComponent. The identity registry also covers captured Wynncraft
        // continuation bars, which intentionally use Wynncraft's own font and glyphs.
        boolean hasBridgeMarker = ComponentTextEditor.flatten(message).stream()
                .anyMatch(fragment -> BRIDGE_PREFIX_FONT.equals(fragment.style().getFont())
                        && (fragment.text().contains(BRIDGE_ICON_GLYPH)
                                || fragment.text().contains(BRIDGE_CONTINUATION_GLYPH)));
        return hasBridgeMarker ? bridgeContinuationPrefix() : null;
    }

    /**
     * Brings a bridge prefix to the column Wynncraft starts its guild badges on, so
     * bridged lines, their continuations and real guild lines all line up.
     * <p>
     * There is deliberately one target rather than two. Sizing the logo against the
     * guild marker and then sizing the two bridge forms against each other gives two
     * answers that disagree, and which of them wins depends on whether a bar has been
     * captured yet — which is what made the alignment come and go.
     */
    private static MutableComponent alignToGuildColumn(MutableComponent prefix) {
        return alignToGuildColumn(prefix, false);
    }

    private static MutableComponent alignToGuildColumn(MutableComponent prefix, boolean useFallbackGuildArrow) {
        if (GuildChatMarkers.arrow() == null && !useFallbackGuildArrow) {
            return prefix;
        }
        try {
            Font font = Minecraft.getInstance().font;
            return alignPrefixToReference(prefix, guildPrefix(false), font::width);
        } catch (RuntimeException | LinkageError ignored) {
            // No client available (unit tests); the prefix keeps its natural width.
            return prefix;
        }
    }

    /** Shares the same invisible spacing for guild and Discord prefixes, using their rendered font widths. */
    static MutableComponent alignPrefixToReference(
            MutableComponent prefix, Component reference, ToIntFunction<Component> width) {
        return appendAdvance(prefix, width.applyAsInt(reference) - width.applyAsInt(prefix));
    }

    /**
     * Appends {@code pixels} of pure advance, positive or negative, so a prefix can be
     * widened or narrowed without drawing anything.
     */
    private static MutableComponent appendAdvance(MutableComponent target, int pixels) {
        if (pixels == 0) {
            return target;
        }
        char glyph = pixels < 0 ? BRIDGE_ADVANCE_TRIM : BRIDGE_ADVANCE_PAD;
        return target.append(Component.literal(String.valueOf(glyph).repeat(Math.abs(pixels)))
                .withStyle(style -> style.withFont(BRIDGE_PREFIX_FONT)));
    }

    /**
     * A separating space on an unstyled root. It must not be a sibling of the marker,
     * or it inherits the marker's font, which has no space glyph and would draw
     * Minecraft's missing-character box.
     */
    private static MutableComponent withSeparator(MutableComponent marker) {
        return Component.empty().append(marker).append(Component.literal(" "));
    }

    /**
     * Opens a bridge block with Wynncraft's own guild chat marker, its icon swapped
     * for the Discord logo. The arrow is not redrawn: the captured glyph is re-emitted
     * unchanged and only the icon slot, which Wynncraft itself varies between the
     * guild shield, the bomb and the raid icons, carries the Discord mark instead.
     */
    private static MutableComponent discordMark() {
        GuildChatMarkers.Marker marker = GuildChatMarkers.arrow();
        MutableComponent mark = Component.empty();
        if (marker != null) {
            // Only the glyph that pins Wynncraft's markers to the left margin, so the
            // logo lines up with the bar drawn under it. The rest of the frame is left
            // out: it is fixed-size art, and forcing a wider logo through it detaches
            // its right-hand piece into what looks like a second arrow.
            int[] codePoints = marker.glyphs().codePoints().toArray();
            mark.append(Component.literal(new String(codePoints, 0, 1))
                    .withStyle(marker.style().withColor(DISCORD_ACCENT).withoutShadow()));
        }
        // Width is not corrected here: bridgePrefix aligns the finished prefix to the
        // guild column, which is the single target both bridge forms answer to.
        return withSeparator(mark.append(Component.literal(BRIDGE_ICON_GLYPH).withStyle(bridgeMarkStyle())));
    }

    /**
     * Inserts the member's insignia just behind their name, after the nick reveal and
     * before the {@code ':'}, or returns the fragments unchanged when they hold none.
     * The caller passes the separator index it already resolved: searching for one
     * here would have to skip the rank badge, and any glyph inside the message body
     * would throw that search off.
     */
    static List<ComponentTextEditor.Fragment> insertInsignia(
            List<ComponentTextEditor.Fragment> fragments, int colonIndex, String username) {
        if (!insigniaEnabled() || colonIndex < 0) {
            return fragments;
        }
        SeqBadgeTier tier = insigniaOf(username);
        if (tier == null || !INSIGNIA_GLYPHS.containsKey(tier)) {
            return fragments;
        }

        return ComponentTextEditor.insertAt(fragments, colonIndex, insigniaBadge(tier));
    }

    /** A spaced insignia glyph with its tooltip, shared by guild chat and the bridge. */
    static MutableComponent insigniaBadge(SeqBadgeTier tier) {
        MutableComponent tooltip = Component.literal("Sequoia insignia: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(insigniaName(tier)).withStyle(INSIGNIA_COLORS.get(tier)));
        return Component.empty()
                .append(Component.literal(" "))
                .append(Component.literal(INSIGNIA_GLYPHS.get(tier))
                        .withStyle(style ->
                                style.withFont(INSIGNIA_FONT).withHoverEvent(new HoverEvent.ShowText(tooltip))));
    }

    /**
     * Insignia of a bridge sender, or {@code null} when they hold none or the setting
     * is off. Guild chat gets the same check from {@link #decorateGuildChat(Component)},
     * which returns early; the bridge builds its line itself and so must check here.
     */
    public static MutableComponent bridgeInsignia(String senderName, String discordId) {
        try {
            // The lookup is a lambda so the roster is not even touched when the
            // setting is off; the gate lives in the overload below.
            return bridgeInsignia(
                    senderName, name -> DiscordRankService.getInstance().insigniaForBridgeSender(name, discordId));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** Insignia core, parameterised on the lookup so the setting gate stays testable. */
    static MutableComponent bridgeInsignia(String senderName, Function<String, SeqBadgeTier> insigniaLookup) {
        if (!insigniaEnabled()) {
            return null;
        }
        SeqBadgeTier tier = insigniaLookup.apply(senderName);
        return tier == null ? null : insigniaBadge(tier);
    }

    /** Title case, so the tooltip reads "Diamond" rather than the enum name. */
    private static String insigniaName(SeqBadgeTier tier) {
        String name = tier.name();
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    /** The member's insignia tier, or {@code null} when the service is unavailable. */
    private static SeqBadgeTier insigniaOf(String username) {
        try {
            return DiscordRankService.getInstance().insigniaForMinecraftUsername(username);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Style of the Discord mark: its own font, blurple, and keeping Minecraft's text
     * shadow so it sits under the logo the way Wynncraft's chat icons do.
     */
    static Style bridgeMarkStyle() {
        return Style.EMPTY.withFont(BRIDGE_PREFIX_FONT).withColor(DISCORD_ACCENT);
    }

    /**
     * Continues a bridge block with the bar Wynncraft draws down the left of a wrapped
     * guild line, captured from a real line so width and spacing match exactly. Falls
     * back to this mod's own glyph until one has been seen.
     */
    private static MutableComponent continuationBar() {
        GuildChatMarkers.Marker bar = GuildChatMarkers.bar();
        if (bar == null) {
            // Unlike discord_mark.png, the bundled continuation glyph is already
            // painted in Discord blurple. Draw it white so Minecraft does not
            // multiply #5865F2 by #5865F2 a second time, and retain the same text
            // shadow as the Discord mark above it.
            return withSeparator(Component.literal(BRIDGE_CONTINUATION_GLYPH)
                    .withStyle(style -> style.withFont(BRIDGE_PREFIX_FONT).withColor(ChatFormatting.WHITE)));
        }
        // Wynncraft's bar ends with its own spacer glyph. Adding a separator on top of
        // it would push the line right of the one above.
        return Component.empty()
                .append(Component.literal(bar.glyphs())
                        .withStyle(bar.style().withColor(DISCORD_ACCENT)));
    }

    /**
     * Runs {@code display} with decoration turned off, for lines this mod builds
     * itself. A bridged message already carries its rank pill, and re-processing it
     * would only risk clipping whatever another mod prepended, such as a timestamp.
     * Chat is displayed on the render thread and {@code addMessage} runs
     * synchronously, so a plain flag is enough.
     */
    public static void displayUndecorated(Component line, Runnable display) {
        displayUndecorated(line, display, true);
    }

    /**
     * Variant for neutral bridge lines, which are suppressed from rank decoration but
     * must not open the Discord marker/continuation sequence.
     */
    static void displayUndecorated(Component line, Runnable display, boolean opensBridgeSequence) {
        if (opensBridgeSequence) {
            rememberColoredBridgeLine(line, bridgeContinuationPrefix());
        }
        lastBridgeLine = line;
        suppressed = true;
        try {
            display.run();
            bridgeSequenceOpen = opensBridgeSequence;
            guildSequenceOpen = false;
        } finally {
            suppressed = false;
        }
    }

    private static void rememberColoredBridgeLine(Component line, Component continuationPrefix) {
        RECENT_COLORED_BRIDGE_LINES.addLast(new ColoredBridgeLine(line, continuationPrefix));
        while (RECENT_COLORED_BRIDGE_LINES.size() > MAX_REMEMBERED_BRIDGE_LINES) {
            RECENT_COLORED_BRIDGE_LINES.removeFirst();
        }
    }

    /**
     * Carries a remembered rail over to {@code rewritten} when a later decoration
     * rebuilds a bridged line, as world links do to the lines that name a world.
     * <p>
     * A rail is retained by identity, and only the first line of a block is
     * recognisable from its shape: a continuation carries Wynncraft's own captured
     * bar. Without this, rewriting a line mid-block would drop the rail from every
     * visual line Minecraft's wrapping makes of it.
     */
    public static void retainBridgeRail(Component original, Component rewritten) {
        if (original == null || rewritten == null || original == rewritten) {
            return;
        }

        Component continuationPrefix = null;
        for (ColoredBridgeLine bridgeLine : RECENT_COLORED_BRIDGE_LINES) {
            if (bridgeLine.message() == original) {
                continuationPrefix = bridgeLine.continuationPrefix();
                break;
            }
        }
        if (continuationPrefix != null) {
            rememberColoredBridgeLine(rewritten, continuationPrefix);
        }
    }

    // ── Shared ──

    /** Builds the glyph pill for {@code rank}, tooltipped with the rank it replaces. */
    static MutableComponent rankPill(RankPresentation rank, String replacedWynncraftRank) {
        return rankPill(rank, replacedWynncraftRank, FALLBACK_RANK_COLOR);
    }

    /** Rank pill whose role colour can return to the supplied source/default background. */
    static MutableComponent rankPill(
            RankPresentation rank, String replacedWynncraftRank, TextColor baseBackgroundColor) {
        MutableComponent pill = buildRankPill(rank, rank.pillLabel(), baseBackgroundColor);
        TextColor primary = colorFor(rank);
        MutableComponent tooltip = Component.literal("Sequoia rank: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(rank.label()).withStyle(style -> style.withColor(primary)));
        if (replacedWynncraftRank != null && !replacedWynncraftRank.isBlank()) {
            tooltip.append(Component.literal("\nGuild rank: " + capitalize(replacedWynncraftRank))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return withTooltip(pill, tooltip);
    }

    /** In-game pill using either the Discord or Wynncraft rank label. */
    static MutableComponent rankPill(
            RankPresentation rank,
            String replacedWynncraftRank,
            TextColor baseBackgroundColor,
            String speakerUsername) {
        String inGameRank = replacedWynncraftRank == null || replacedWynncraftRank.isBlank()
                ? null
                : capitalize(replacedWynncraftRank);
        if (inGameRank == null && PrincessRankEasterEgg.isLocalSpeaker(speakerUsername)) {
            inGameRank = WynntilsGuildRankAccess.currentRankLabel();
        }
        boolean showDiscordRank = SeqClient.getShowDiscordRankPillsSetting() == null
                || SeqClient.getShowDiscordRankPillsSetting().getValue()
                || inGameRank == null;
        String pillLabel = showDiscordRank
                ? PrincessRankEasterEgg.pillLabel(rank.pillLabel(), speakerUsername)
                : inGameRank.toUpperCase(Locale.ROOT);
        MutableComponent pill = buildRankPill(rank, pillLabel, baseBackgroundColor);
        MutableComponent tooltip = showDiscordRank
                ? Component.literal("In-game rank: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(inGameRank == null ? "Unknown" : inGameRank)
                                .withStyle(ChatFormatting.WHITE))
                : Component.literal("Sequoia rank: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(rank.label()).withStyle(style -> style.withColor(colorFor(rank))));
        return withTooltip(pill, tooltip);
    }

    private static MutableComponent buildRankPill(
            RankPresentation rank, String pillLabel, TextColor baseBackgroundColor) {
        return NotificationAccessor.wynnPill(
                pillLabel,
                rampFor(rank),
                roleRampFor(rank),
                PILL_LABEL_COLOR,
                null,
                baseBackgroundColor);
    }

    private static MutableComponent withTooltip(MutableComponent pill, Component tooltip) {
        return pill.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(tooltip)));
    }

    /**
     * The member's colour stops, falling back to a neutral single colour when neither
     * they nor their role publishes any, so an uncoloured rank still renders a
     * readable pill.
     */
    static ColorRamp rampFor(RankPresentation rank) {
        return rank.colors().isEmpty() ? ColorRamp.of(FALLBACK_RANK_COLOR.getValue()) : rank.colors();
    }

    /** Shared progression-rank palette used while per-user colouring is disabled. */
    static ColorRamp roleRampFor(RankPresentation rank) {
        return rank.roleColors().isEmpty()
                ? ColorRamp.of(FALLBACK_RANK_COLOR.getValue())
                : rank.roleColors();
    }

    /**
     * A name painted across the member's complete role palette. Gradient colours are
     * minted through {@link RankGradientAnimation}, so the same render hook that moves
     * a rank pill moves the name with it. Solid names stay one component.
     */
    static MutableComponent colouredName(String name, RankPresentation rank, Style style) {
        return RankGradientAnimation.batchRegistrations(() -> colouredNameNow(name, rank, style));
    }

    private static MutableComponent colouredNameNow(String name, RankPresentation rank, Style style) {
        String text = name == null ? "" : name;
        Style baseStyle = style == null ? Style.EMPTY : style;
        ColorRamp displayRamp = rampFor(rank);
        ColorRamp roleRamp = roleRampFor(rank);
        if ((!displayRamp.isGradient() && !roleRamp.isGradient())
                || text.codePointCount(0, text.length()) <= 1) {
            TextColor color = RankGradientAnimation.colorAt(
                    displayRamp, roleRamp, 0d, RankGradientAnimation.Target.USERNAME, baseStyle.getColor());
            return Component.literal(text).withStyle(withRegisteredColor(baseStyle, color));
        }

        MutableComponent coloured = Component.empty();
        int codePointCount = text.codePointCount(0, text.length());
        int index = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            double position = (double) index / (codePointCount - 1);
            TextColor color = RankGradientAnimation.colorAt(
                    displayRamp,
                    roleRamp,
                    position,
                    RankGradientAnimation.Target.USERNAME,
                    baseStyle.getColor());
            coloured.append(Component.literal(new String(Character.toChars(codePoint)))
                    .withStyle(withRegisteredColor(baseStyle, color)));
            offset += Character.charCount(codePoint);
            index++;
        }
        return coloured;
    }

    private static List<ComponentTextEditor.Fragment> recolourName(
            List<ComponentTextEditor.Fragment> fragments,
            int start,
            int endExclusive,
            RankPresentation rank,
            String insertion) {
        return RankGradientAnimation.batchRegistrations(
                () -> recolourNameNow(fragments, start, endExclusive, rank, insertion));
    }

    private static List<ComponentTextEditor.Fragment> recolourNameNow(
            List<ComponentTextEditor.Fragment> fragments,
            int start,
            int endExclusive,
            RankPresentation rank,
            String insertion) {
        ColorRamp displayRamp = rampFor(rank);
        ColorRamp roleRamp = roleRampFor(rank);
        if (!displayRamp.isGradient() && !roleRamp.isGradient()) {
            return ComponentTextEditor.restyleRange(
                    fragments,
                    start,
                    endExclusive,
                    style -> withRegisteredColor(
                                    style,
                                    RankGradientAnimation.colorAt(
                                            displayRamp,
                                            roleRamp,
                                            0d,
                                            RankGradientAnimation.Target.USERNAME,
                                            style.getColor()))
                            .withInsertion(insertion));
        }
        return ComponentTextEditor.restyleRangeByPosition(
                fragments,
                start,
                endExclusive,
                (style, position) -> withRegisteredColor(
                                style,
                                RankGradientAnimation.colorAt(
                                        displayRamp,
                                        roleRamp,
                                        position,
                                        RankGradientAnimation.Target.USERNAME,
                                        style.getColor()))
                        .withInsertion(insertion));
    }

    /**
     * Keeps the freshly registered colour instance even when its RGB value matches
     * the source style. {@link Style#withColor(TextColor)} otherwise returns the
     * source style unchanged, which drops the identity used to animate that glyph.
     */
    static Style withRegisteredColor(Style style, TextColor color) {
        return style.withColor((TextColor) null).withColor(color);
    }

    /**
     * The primary colour for a rank, used by solid names and places such as tooltips
     * where splitting the text into independently coloured glyphs would be distracting.
     */
    static TextColor colorFor(RankPresentation rank) {
        return WynncraftTextShaderColor.safeTextColor(rampFor(rank).first());
    }

    private static boolean isEnabled() {
        return SeqClient.getShowDiscordRanksSetting() != null
                && SeqClient.getShowDiscordRanksSetting().getValue();
    }

    /** Chat insignias are independently optional beneath Discord rank decoration. */
    private static boolean insigniaEnabled() {
        return isEnabled()
                && (SeqClient.getShowChatInsigniasSetting() == null
                        || SeqClient.getShowChatInsigniasSetting().getValue());
    }

    // ── Diagnostics ──

    /**
     * When on, every chat line the decorator declines to rewrite is dumped to the log
     * with its codepoints. Wynncraft's rank pill glyphs are invisible in normal
     * output, so this is the only practical way to confirm which codepoints a given
     * server build actually sends. Toggled by {@code /seq rank debug}.
     */
    public static void setDebug(boolean enabled) {
        debug = enabled;
    }

    /** Whether undecorated guild lines are being dumped to the log. */
    public static boolean isDebug() {
        return debug;
    }

    private static void logInspection(Component message, String outcome) {
        if (!debug) {
            return;
        }
        String text = ComponentTextEditor.textOf(ComponentTextEditor.flatten(message));
        if (text.indexOf(':') < 0) {
            return;
        }

        List<WynnPillGlyphs.Pill> pills = WynnPillGlyphs.findPills(text);
        String speakers = pills.stream()
                .map(pill -> pill.label() + "->"
                        + PacketNameResolver.from(message).resolveRaw(text, pill.endExclusive(),
                                Math.max(pill.endExclusive(), indexOfColon(text, pill.endExclusive())),
                                PacketNameResolver.Policy.CHAT, "chat.speaker"))
                .toList()
                .toString();
        SeqClient.LOGGER.info(
                "[DiscordRanks] {} | badges={} | speakers={} | codepoints={}",
                outcome,
                pills.stream().map(WynnPillGlyphs.Pill::label).toList(),
                speakers,
                describeCodepoints(text));
    }

    private static int indexOfColon(String text, int from) {
        int colonIndex = text.indexOf(':', from);
        return colonIndex < 0 ? from : colonIndex;
    }

    /** Renders a chat line with every non-ASCII character shown as {@code U+XXXX}. */
    static String describeCodepoints(String text) {
        StringBuilder described = new StringBuilder();
        text.codePoints().limit(220).forEach(codePoint -> {
            if (codePoint >= 0x20 && codePoint < 0x7F) {
                described.append((char) codePoint);
            } else {
                described.append(String.format("<U+%04X>", codePoint));
            }
        });
        return described.toString();
    }

    private static String capitalize(String value) {
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT);
    }
}
