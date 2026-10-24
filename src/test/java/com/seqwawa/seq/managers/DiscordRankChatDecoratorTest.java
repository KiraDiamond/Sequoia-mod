package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.config.Setting;
import com.seqwawa.seq.model.DiscordRank;
import com.seqwawa.seq.model.SeqBadgeTier;
import com.seqwawa.seq.model.RankPresentation;
import com.seqwawa.seq.network.ConnectionManager;
import com.seqwawa.seq.utils.ColorRamp;
import com.seqwawa.seq.utils.ComponentTextEditor;
import com.seqwawa.seq.utils.RankGradientAnimation;
import com.seqwawa.seq.utils.WynnPillGlyphs;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DiscordRankChatDecoratorTest {

    /** Captured markers are global state; clear them so test order cannot matter. */
    @BeforeEach
    void forgetCapturedMarkers() {
        GuildChatMarkers.reset();
        NicknameResolverCache.reset();
    }

    @AfterEach
    void forgetNicknameCache() {
        NicknameResolverCache.reset();
    }

    private static final RankPresentation SAPLING = presentation("rank.sapling", "Sapling", 88, 0x4CB4FA);
    private static final RankPresentation DRUID = presentation("rank.druid", "Druid", 92, 0xD7BCEA);
    private static final Map<String, RankPresentation> RANKS =
            Map.of("arcleretour", SAPLING, "pat_crafter07", DRUID);
    /** Wynncraft's guild chat aqua. */
    private static final int GUILD_AQUA = 0x55FFFF;
    private static final int DARK_AQUA = 0x00AAAA;
    /** Wynncraft's party chat yellow. */
    private static final int PARTY_YELLOW = 0xFFFF55;
    private static final int PARTY_SPEAKER_GOLD = 0xFFAA00;

    // Shapes taken from a real guild line (latest.log): Wynncraft draws the guild
    // icon in one font, the rank badge in another, and the text in the default one.
    // The icon mixes supplementary-plane and BMP glyphs, so only the font tells the
    // two decorations apart.
    private static final FontDescription ICON_FONT =
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("wynncraft", "cp"));
    private static final FontDescription BADGE_FONT =
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("wynncraft", "bp"));
    private static final String ICON_GLYPHS = "\uDAFF\uDFFC\uE01E\uDBFF\uDFFF\uE002";
    private static final String PARTY_ICON_GLYPHS =
            "\uDAFF\uDFFC\uE005\uDBFF\uDFFF\uE002\uDBFF\uDFFE";
    private static final String CONTINUATION_GLYPHS = "\uDAFF\uDFFC\uE001\uDB00\uDC06";
    /** Guild pill captured from the Regret line that contains a party-marker glyph internally. */
    private static final String ALTERNATE_GUILD_PILL = new String(
            new int[] {
                0xE060,
                0xCFFFF,
                0xE032,
                0xCFFFF,
                0xE037,
                0xCFFFF,
                0xE038,
                0xCFFFF,
                0xE034,
                0xCFFFF,
                0xE035,
                0xCFFFF,
                0xE062,
                0xCFFE2,
                0xE002,
                0xE007,
                0xE008,
                0xE004,
                0xE005,
                0xD0002
            },
            0,
            20);
    private static final String BADGE_GEOMETRY = "\uE001 \uE002\uE003";
    private static final String LEGACY_RED = "\u00A7c";
    private static final String LEGACY_RESET = "\u00A7f";

    @Test
    void replacesTheWynncraftGuildRankWithTheLinkedDiscordRank() {
        Component message = guildLine("RECRUITER", "EightySix(ArcLeRetour)", "ArcLeRetour", "=')");

        Component decorated = DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup);

        assertEquals(List.of("sapling"), pillLabels(decorated));
        assertTrue(decorated.getString().contains("EightySix(ArcLeRetour): =')"));
    }

    @Test
    void poisonedPartyCacheCannotGiveAnUnrankedAccountYearnmsDruidRank() {
        IdentityTestSupport.remember("IChooseThou", "Yearnm");
        Component message = guildLine("RECRUIT", "IChooseThou", "IChooseThou", "hello");
        assertSame(message, DiscordRankChatDecorator.decorateGuildChat(
                message, name -> name.equalsIgnoreCase("Yearnm") ? DRUID : null));
    }

    @Test
    void scopedInsertionUsesTheSameAccountAsTheRelay() {
        Component message = guildLine("RECRUIT", "IChooseThou", "Yearnm", "hello");
        assertEquals("Yearnm", ChatManager.parseGuildMessage(
                Component.empty().append(Component.literal("IChooseThou").withStyle(Style.EMPTY.withInsertion("Yearnm")))
                        .append(": hello")).username());
        assertEquals(List.of("druid"), pillLabels(DiscordRankChatDecorator.decorateSupportedChat(
                message, name -> name.equalsIgnoreCase("Yearnm") ? DRUID : null, name -> null,
                true, false, name -> name.equalsIgnoreCase("Yearnm"))));
    }

    @Test
    void cachedUnrankedRealAccountWinsOverRankedItalicNickname() {
        IdentityTestSupport.remember("ArcLeRetour", "IChooseThou");
        Component message = Component.empty()
                .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUIT") + " "))
                .append(Component.literal("ArcLeRetour").withStyle(Style.EMPTY.withItalic(true)
                        .withInsertion("ArcLeRetour")))
                .append(Component.literal(": hello"));
        assertSame(message, DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup));
    }

    @Test
    void unrankedRealAccountHoverOverridesRankedNicknameInsertionAndCache() {
        IdentityTestSupport.remember("ArcLeRetour", "pat_crafter07");
        Style identity = Style.EMPTY.withItalic(true).withInsertion("pat_crafter07")
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("ArcLeRetour's real username is IChooseThou")));
        Component message = Component.empty()
                .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUIT") + " "))
                .append(Component.literal("ArcLeRetour").withStyle(identity))
                .append(Component.literal(": hello"));
        assertSame(message, DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup));
    }

    @Test
    void unrankedExplicitAccountRevealCannotFallThroughToRankedNicknameOrCache() {
        IdentityTestSupport.remember("ArcLeRetour(IChooseThou)", "pat_crafter07");
        Component message = guildLine("RECRUIT", "ArcLeRetour(IChooseThou)", "pat_crafter07", "hello");
        assertSame(message, DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup));
    }

    @Test
    void guildColoringRequiresConfirmedSequoiaMembershipEvenForRankedAccounts() {
        Component message = guildLine("RECRUIT", "ArcLeRetour", "ArcLeRetour", "hello");
        assertSame(message, DiscordRankChatDecorator.decorateSupportedChat(
                message, DiscordRankChatDecoratorTest::lookup, DiscordRankChatDecoratorTest::lookup,
                true, false, username -> false));
        assertEquals(List.of("sapling"), pillLabels(DiscordRankChatDecorator.decorateSupportedChat(
                message, DiscordRankChatDecoratorTest::lookup, DiscordRankChatDecoratorTest::lookup,
                true, false, username -> username.equalsIgnoreCase("ArcLeRetour"))));
        assertNull(DiscordRankChatDecorator.guildRank("ArcLeRetour", DiscordRankChatDecoratorTest::lookup, name -> false));
    }

    @Test
    void partyColoringKeepsItsOwnLookupAndDoesNotRequireGuildMembership() {
        Component message = partyLine("ArcLeRetour", "ArcLeRetour", "ready");
        Component decorated = DiscordRankChatDecorator.decorateSupportedChat(
                message, name -> null, DiscordRankChatDecoratorTest::lookup, true, true, name -> false);
        assertEquals(message.getString(), decorated.getString());
        assertEquals(0x4CB4FA, colorOfFragmentContaining(ComponentTextEditor.flatten(decorated), "ArcLeRetour"));
    }

    @Test
    void partyNicknameMatchingAnotherAccountUsesNunotsRole() {
        for (String apostrophe : List.of("'", "\u2019")) {
            IdentityTestSupport.remember("Reyzhia", "reyzhia");
            Style name = Style.EMPTY.withColor(PARTY_SPEAKER_GOLD).withItalic(true).withInsertion("reyzhia")
                    .withHoverEvent(new HoverEvent.ShowText(
                            Component.literal("Reyzhia" + apostrophe + "s real name is nunot")));
            Component message = Component.empty()
                    .append(Component.literal(PARTY_ICON_GLYPHS + " ").withStyle(Style.EMPTY.withColor(PARTY_YELLOW)))
                    .append(Component.literal("Reyzhia").withStyle(name))
                    .append(Component.literal(": hello").withStyle(Style.EMPTY.withColor(PARTY_YELLOW)));

            Component decorated = DiscordRankChatDecorator.decoratePartyChat(
                    message, username -> username.equalsIgnoreCase("nunot") ? SAPLING : DRUID);

            assertEquals(message.getString(), decorated.getString());
            assertEquals(0x4CB4FA, colorOfFragmentContaining(ComponentTextEditor.flatten(decorated), "Reyzhia"));
            assertSame(message, DiscordRankChatDecorator.decoratePartyChat(
                    message, username -> username.equalsIgnoreCase("reyzhia") ? DRUID : null));
        }
    }

    @Test
    void partyColorIgnoresAnInheritedHoverDescribingAnotherPlayer() {
        Component message = Component.empty().withStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("OtherNick's real name is RaidKeeper"))))
                .append(Component.literal(PARTY_ICON_GLYPHS + " ").withStyle(Style.EMPTY.withColor(PARTY_YELLOW)))
                .append(Component.literal("GaztheCat").withStyle(Style.EMPTY.withInsertion("GaztheCat")))
                .append(Component.literal(": hello OtherNick"));

        Component decorated = DiscordRankChatDecorator.decoratePartyChat(
                message, username -> username.equalsIgnoreCase("GaztheCat") ? SAPLING : DRUID);

        assertEquals(0x4CB4FA, colorOfFragmentContaining(ComponentTextEditor.flatten(decorated), "GaztheCat"));
    }

    @Test
    void unresolvedSpacedPartyNicknameCannotBorrowAnInheritedHoverIdentity() {
        Component message = Component.empty().withStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("OtherNick's real name is RaidKeeper"))))
                .append(Component.literal(PARTY_ICON_GLYPHS + " ").withStyle(Style.EMPTY.withColor(PARTY_YELLOW)))
                .append(Component.literal("Some Nick"))
                .append(Component.literal(": hello OtherNick"));

        assertSame(message, DiscordRankChatDecorator.decoratePartyChat(
                message, username -> username.equalsIgnoreCase("RaidKeeper") ? DRUID : null));
    }

    @Test
    void realAccountHoverWinsWhenInsertionContainsOnlyTheNickname() {
        Component message = Component.empty()
                .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUIT") + " "))
                .append(Component.literal("Burger").withStyle(Style.EMPTY.withInsertion("Burger")
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("Burger's real username is pat_crafter07")))))
                .append(Component.literal(": hello"));
        assertEquals(List.of("druid"), pillLabels(DiscordRankChatDecorator.decorateGuildChat(
                message, DiscordRankChatDecoratorTest::lookup)));
    }

    @Test
    void canKeepTheWynncraftRankOnTheColoredPill() {
        Setting.BooleanSetting previous = SeqClient.showDiscordRankPillsSetting;
        try {
            SeqClient.showDiscordRankPillsSetting =
                    new Setting.BooleanSetting("show_discord_rank_pills", "chat", false);

            Component decorated = DiscordRankChatDecorator.decorateGuildChat(
                    guildLine("RECRUITER", "ArcLeRetour", "ArcLeRetour", "hi"),
                    DiscordRankChatDecoratorTest::lookup);

            assertEquals(List.of("recruiter"), pillLabels(decorated));
            assertEquals("Sequoia rank: Sapling", hoverText(decorated));
        } finally {
            SeqClient.showDiscordRankPillsSetting = previous;
        }
    }

    @Test
    void guildPillContainingPartyGlyphStillUsesGuildDecoration() {
        Component message = Component.empty()
                .append(Component.literal(CONTINUATION_GLYPHS)
                        .withStyle(Style.EMPTY.withFont(ICON_FONT).withColor(GUILD_AQUA)))
                .append(Component.literal(" ").withStyle(Style.EMPTY.withColor(GUILD_AQUA)))
                .append(Component.literal(ALTERNATE_GUILD_PILL)
                        .withStyle(Style.EMPTY.withFont(BADGE_FONT).withColor(GUILD_AQUA)))
                .append(Component.literal(" ").withStyle(Style.EMPTY.withColor(GUILD_AQUA)))
                .append(Component.literal("Regret")
                        .withStyle(Style.EMPTY.withColor(DARK_AQUA).withInsertion("Regret")))
                .append(Component.literal(":").withStyle(Style.EMPTY.withColor(DARK_AQUA)))
                .append(Component.literal(" oh nvm").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));

        boolean partyCandidate = DiscordRankChatDecorator.isPartyChatCandidate(message);
        Component decorated = DiscordRankChatDecorator.decorateSupportedChat(
                message,
                candidate -> candidate.equalsIgnoreCase("Regret") ? SAPLING : null,
                WynnPillGlyphs.containsPill(message.getString()),
                partyCandidate);

        assertFalse(partyCandidate, "a glyph inside the guild pill is not a party marker");
        assertEquals(List.of("sapling"), pillLabels(decorated));
        assertFalse(decorated.getString().contains(ALTERNATE_GUILD_PILL));
        assertTrue(decorated.getString().contains("Regret: oh nvm"));
    }

    @Test
    void keepsShiftClickInsertionOfTheSpeakerName() {
        Component decorated = DiscordRankChatDecorator.decorateGuildChat(
                guildLine("RECRUITER", "EightySix(ArcLeRetour)", "ArcLeRetour", "hi"), DiscordRankChatDecoratorTest::lookup);

        assertTrue(ComponentTextEditor.flatten(decorated).stream()
                .anyMatch(fragment -> "ArcLeRetour".equals(fragment.style().getInsertion())));
    }

    @Test
    void resolvesSpeakersThatChatUnderTheirPlainUsername() {
        Component message = guildLine("RECRUIT", "ArcLeRetour", null, "no nickname here");

        assertEquals(List.of("sapling"), pillLabels(DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup)));
    }

    @Test
    void leavesTheLineAloneWhenTheSpeakerHasNoLinkedRank() {
        Component message = guildLine("RECRUITER", "SomeoneElse", "SomeoneElse", "hello");

        assertSame(message, DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup));
    }

    @Test
    void leavesNonGuildPillsSuchAsAccountRanksAlone() {
        Component message = guildLine("CHAMPION", "ArcLeRetour", "ArcLeRetour", "trading");

        assertSame(message, DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup));
    }

    @Test
    void ignoresPlayersMentionedInsideTheMessageBody() {
        Component message = Component.empty()
                .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUITER") + " "))
                .append(Component.literal("SomeoneElse").withStyle(Style.EMPTY.withInsertion("SomeoneElse")))
                .append(Component.literal(": ping "))
                .append(Component.literal("ArcLeRetour").withStyle(Style.EMPTY.withInsertion("ArcLeRetour")));

        assertSame(message, DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup));
    }

    @Test
    void readsGuildRanksEvenWhenTheBadgeCarriesAStrayGlyph() {
        assertEquals("recruiter", DiscordRankChatDecorator.guildRankOf("xrecruiter"));
        assertEquals("recruiter", DiscordRankChatDecorator.guildRankOf("RECRUITER"));
        assertEquals("recruit", DiscordRankChatDecorator.guildRankOf("recruit"));
        assertNull(DiscordRankChatDecorator.guildRankOf("champion"));
        assertNull(DiscordRankChatDecorator.guildRankOf(null));
    }

    @Test
    void showsTheRankDecodedFromACapturedLayeredBadge() {
        Component message = Component.empty()
                .append(Component.literal("[23:38:16] "))
                .append(Component.literal(layeredGuildRankPill("chief", 0xCFFE2) + " "))
                .append(Component.literal("ArcLeRetour")
                        .withStyle(Style.EMPTY.withColor(GUILD_AQUA).withInsertion("ArcLeRetour")))
                .append(Component.literal(": hi").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));

        Component decorated =
                DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup);

        assertEquals("In-game rank: Chief", hoverText(decorated));
    }

    @Test
    void leavesOurOwnBridgeLineAloneWhenItComesBackForDecoration() {
        // Only the most recent bridged line is remembered by identity, so an earlier one
        // can reach the decorator. Our marker sits in the same private-use block as
        // Wynncraft's badges and the line carries guild aqua, which is exactly what the
        // position fallback looks for: it would replace our own marker with a pill.
        Component bridged = Component.empty()
                .append(Component.literal(DiscordRankChatDecorator.BRIDGE_ICON_GLYPH + " ")
                        .withStyle(Style.EMPTY.withColor(GUILD_AQUA)))
                .append(Component.literal("ArcLeRetour")
                        .withStyle(Style.EMPTY.withColor(GUILD_AQUA).withInsertion("ArcLeRetour")))
                .append(Component.literal(": hi").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));

        assertSame(
                bridged, DiscordRankChatDecorator.decorateGuildChat(bridged, DiscordRankChatDecoratorTest::lookup));
    }

    @Test
    void keepsAnInsigniaItAlreadyInsertedInsteadOfEatingIt() {
        // A decorated line carries our insignia just before the ':'. Re-decorating must
        // not treat that glyph as part of a Wynncraft badge.
        Component decorated = Component.empty()
                .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUITER") + " "))
                .append(Component.literal("ArcLeRetour")
                        .withStyle(Style.EMPTY.withColor(GUILD_AQUA).withInsertion("ArcLeRetour")))
                .append(Component.literal(""))
                .append(Component.literal(": hi").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));

        String result = DiscordRankChatDecorator.decorateGuildChat(
                        decorated, DiscordRankChatDecoratorTest::lookup)
                .getString();

        assertTrue(result.contains(""), "the insignia must survive: " + describe(result));
    }

    private static String describe(String text) {
        return DiscordRankChatDecorator.describeCodepoints(text);
    }

    @Test
    void leavesUnknownPrivateUsePillsAlone() {
        // Unknown private-use glyphs can belong to another channel or system marker.
        Component message = Component.empty()
                .append(Component.literal("\uE07A\uE0FF\uE07B "))
                .append(Component.literal("ArcLeRetour").withStyle(Style.EMPTY.withInsertion("ArcLeRetour")))
                .append(Component.literal(": selling stuff"));

        assertSame(message, DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup));
    }

    @Test
    void configuredGuildColorDoesNotRecolorUnknownPill() {
        Setting.ColorSetting previous = SeqClient.inGameGuildChatTextColorSetting;
        try {
            SeqClient.inGameGuildChatTextColorSetting =
                    new Setting.ColorSetting("in_game_guild_chat_text_color", "chat", 0xA1B2C3);
            Component message = wynncraftGuildLine("UNKNOWN", "EightySix", "ArcLeRetour", "hello");

            assertSame(message, DiscordRankChatDecorator.decorateGuildChat(message));
        } finally {
            SeqClient.inGameGuildChatTextColorSetting = previous;
        }
    }

    @Test
    void leavesPartyFinderSystemMessagesWithLaterGuildAquaAlone() {
        // Captured Party Finder prefix. Its private-use icon looks like an unreadable
        // badge, while the activity name later in the line can be guild aqua. Only a
        // decoded guild-rank pill may trigger decoration, regardless of inherited
        // player metadata on the system label.
        Component message = Component.empty()
                .append(Component.literal("󏿼󏿿󏿾 Party Finder: ")
                        .withStyle(Style.EMPTY.withColor(0xAA00AA).withInsertion("pat_crafter07")))
                .append(Component.literal("Hey theoplegends, over here! Join the ")
                        .withStyle(Style.EMPTY.withColor(0xFF55FF)))
                .append(Component.literal("The Nameless Anomaly").withStyle(Style.EMPTY.withColor(GUILD_AQUA)))
                .append(Component.literal(" queue and match up with 2 other players!")
                        .withStyle(Style.EMPTY.withColor(0xFF55FF)));

        assertSame(message, DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup));
    }

    @Test
    void leavesTheSeparatingSpaceInTheDefaultFont() {
        // In the marker's font there is no space glyph, so inheriting it would draw
        // Minecraft's missing-character box right next to the logo.
        List<ComponentTextEditor.Fragment> fragments =
                ComponentTextEditor.flatten(DiscordRankChatDecorator.bridgePrefix());

        ComponentTextEditor.Fragment space = fragments.getLast();
        assertEquals(" ", space.text());
        assertEquals(FontDescription.DEFAULT, space.style().getFont());
    }

    @Test
    void capturesOnlyTheMarkerFontAndNotAModsTimestamp() {
        DiscordRankChatDecorator.decorateGuildChat(
                wynncraftGuildLine("RECRUITER", "EightySix", "ArcLeRetour", "t"),
                DiscordRankChatDecoratorTest::lookup);

        assertNotNull(GuildChatMarkers.arrow());
        assertEquals(ICON_GLYPHS, GuildChatMarkers.arrow().glyphs());
        assertEquals(ICON_FONT, GuildChatMarkers.arrow().style().getFont());
    }

    @Test
    void keepsTheGuildIconAndItsFontUntouched() {
        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(
                DiscordRankChatDecorator.decorateGuildChat(
                        wynncraftGuildLine("RECRUITER", "EightySix", "ArcLeRetour", "t"),
                        DiscordRankChatDecoratorTest::lookup));

        ComponentTextEditor.Fragment icon = fragments.stream()
                .filter(fragment -> fragment.text().contains(ICON_GLYPHS))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the guild icon must survive"));
        assertEquals(ICON_FONT, icon.style().getFont(), "and keep the font that renders it");
    }

    @Test
    void clearsEveryFragmentDrawnInTheBadgeFont() {
        // Badge geometry is negative space: a surviving piece drags the rest of the
        // line left rather than merely showing.
        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(
                DiscordRankChatDecorator.decorateGuildChat(
                        wynncraftGuildLine("RECRUITER", "EightySix", "ArcLeRetour", "t"),
                        DiscordRankChatDecoratorTest::lookup));

        assertTrue(
                fragments.stream().noneMatch(fragment -> BADGE_FONT.equals(fragment.style().getFont())),
                "nothing in the badge font may remain");
        assertFalse(ComponentTextEditor.textOf(fragments).contains(BADGE_GEOMETRY));
    }

    @Test
    void drawsTheNewPillInTheDefaultFont() {
        // The badge font has no glyphs for these codepoints; inheriting it would
        // collapse the pill to no width.
        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(
                DiscordRankChatDecorator.decorateGuildChat(
                        wynncraftGuildLine("RECRUITER", "EightySix", "ArcLeRetour", "t"),
                        DiscordRankChatDecoratorTest::lookup));

        List<ComponentTextEditor.Fragment> pillFragments = fragments.stream()
                .filter(fragment -> fragment.text().indexOf(WynnPillGlyphs.CORNER_LEFT) >= 0
                        || fragment.text().indexOf(WynnPillGlyphs.BACKGROUND) >= 0)
                .toList();

        assertFalse(pillFragments.isEmpty(), "the new pill must be there");
        pillFragments.forEach(fragment ->
                assertEquals(FontDescription.DEFAULT, fragment.style().getFont(), "pill glyph font"));
    }

    @Test
    void keepsAPrecedingTimestampWhenTheBadgeSharesTheDefaultFont() {
        // A bridged line: pill and timestamp are both in the default font, so the
        // font boundary alone would run back over the timestamp.
        Component message = Component.empty()
                .append(Component.literal("[19:40:06] "))
                .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUITER") + " "))
                .append(Component.literal("ArcLeRetour")
                        .withStyle(Style.EMPTY.withColor(GUILD_AQUA).withInsertion("ArcLeRetour")))
                .append(Component.literal(": aadz").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));

        String decorated = DiscordRankChatDecorator.decorateGuildChat(
                        message, DiscordRankChatDecoratorTest::lookup)
                .getString();

        assertTrue(decorated.startsWith("[19:40:06] "), "the timestamp must survive: " + decorated);
        assertEquals(List.of("sapling"), pillLabels(Component.literal(decorated)));
    }

    @Test
    void glyphRunStartNeverCrossesVisibleText() {
        String text = "[19:40:06] " + WynnPillGlyphs.encodePlainPill("RECRUITER") + " Name: hi";
        int badgeStart = text.indexOf(WynnPillGlyphs.CORNER_LEFT);

        assertEquals(badgeStart, DiscordRankChatDecorator.glyphRunStart(text, badgeStart));
    }

    @Test
    void keepsTheTimestampAndTheMessageIntact() {
        String decorated = DiscordRankChatDecorator.decorateGuildChat(
                        wynncraftGuildLine("RECRUITER", "EightySix", "ArcLeRetour", "t"),
                        DiscordRankChatDecoratorTest::lookup)
                .getString();

        assertTrue(decorated.startsWith("[19:25:32] " + ICON_GLYPHS + " "), decorated);
        assertTrue(decorated.endsWith(" EightySix" + LEGACY_RED + "(ArcLeRetour)" + LEGACY_RESET + ": t"), decorated);
    }

    @Test
    void stopsRecolouringAtTheLegacyCodeThatOpensTheNickReveal() {
        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(
                DiscordRankChatDecorator.decorateGuildChat(
                        wynncraftGuildLine("RECRUITER", "EightySix", "ArcLeRetour", "t"),
                        DiscordRankChatDecoratorTest::lookup));

        assertEquals(0x4CB4FA, colorOfFragmentContaining(fragments, "EightySix"));
        assertEquals(
                DARK_AQUA,
                colorOfFragmentContaining(fragments, LEGACY_RED + "(ArcLeRetour)"),
                "the reveal keeps the styling it arrived with, its own §c doing the rest");
    }

    @Test
    void speakerNameEndStopsAtTheFirstLegacyCode() {
        String text = "  EightySix" + LEGACY_RED + "(ArcLeRetour)" + LEGACY_RESET + ": t";
        int colon = text.indexOf(':');

        assertEquals(
                text.indexOf(LEGACY_RED),
                DiscordRankChatDecorator.speakerNameEnd(
                        ComponentTextEditor.flatten(Component.literal(text)), text, 2, colon, "ArcLeRetour"));
    }

    @Test
    void leavesExactlyOneSpaceBetweenThePillAndTheName() {
        Component decorated = DiscordRankChatDecorator.decorateGuildChat(
                guildLine("RECRUITER", "ArcLeRetour", "ArcLeRetour", "hi"), DiscordRankChatDecoratorTest::lookup);

        assertTrue(decorated.getString().endsWith(" ArcLeRetour: hi"));
        assertFalse(decorated.getString().contains("  ArcLeRetour"), "no doubled space");
    }

    @Test
    void paintsTheSpeakerNameWithTheirRankColour() {
        Component decorated = DiscordRankChatDecorator.decorateGuildChat(
                guildLine("RECRUITER", "ArcLeRetour", "ArcLeRetour", "hi"), DiscordRankChatDecoratorTest::lookup);

        assertEquals(
                0x4CB4FA,
                ComponentTextEditor.flatten(decorated).stream()
                        .filter(fragment -> fragment.text().contains("ArcLeRetour"))
                        .findFirst()
                        .orElseThrow()
                        .style()
                        .getColor()
                        .getValue());
    }

    @Test
    void paintsPartyChatSpeakerWithoutAddingARankPill() {
        Component message = partyLine("ArcLeRetour", "ArcLeRetour", "ready");
        assertTrue(
                WynnPillGlyphs.containsPill(message.getString()),
                "the party marker overlaps the broad guild pill pre-check");

        Component decorated = DiscordRankChatDecorator.decorateSupportedChat(
                message, DiscordRankChatDecoratorTest::lookup, true, true);
        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(decorated);

        assertEquals(message.getString(), decorated.getString());
        assertTrue(WynnPillGlyphs.findPills(decorated.getString()).isEmpty());
        assertEquals(0x4CB4FA, colorOfFragmentContaining(fragments, "ArcLeRetour"));
        assertEquals(PARTY_YELLOW, colorOfFragmentContaining(fragments, "ready"));
    }

    @Test
    void partyChatColoringCanBeDisabledIndependently() {
        Setting.BooleanSetting previous = SeqClient.colorPartyChatSetting;
        Component message = partyLine("ArcLeRetour", "ArcLeRetour", "ready");
        try {
            SeqClient.colorPartyChatSetting = new Setting.BooleanSetting("color_party_chat", "chat", false);

            Component decorated = DiscordRankChatDecorator.decorateSupportedChat(
                    message, DiscordRankChatDecoratorTest::lookup, true, true);

            assertSame(message, decorated);
        } finally {
            SeqClient.colorPartyChatSetting = previous;
        }
    }

    @Test
    void paintsPartySpeakerWhenWynncraftUsesItsGenericChatMarker() {
        Component message = partyLine(CONTINUATION_GLYPHS, "ArcLeRetour", "ArcLeRetour", "ready");

        Component decorated =
                DiscordRankChatDecorator.decoratePartyChat(message, DiscordRankChatDecoratorTest::lookup);

        assertEquals(
                0x4CB4FA,
                colorOfFragmentContaining(ComponentTextEditor.flatten(decorated), "ArcLeRetour"));
        assertTrue(WynnPillGlyphs.findPills(decorated.getString()).isEmpty());
    }

    @Test
    void partyUsernameColorCanBeDisabledWithoutRebuildingTheMessage() {
        withRankColoring(true, false, () -> {
            Component decorated = DiscordRankChatDecorator.decoratePartyChat(
                    partyLine("ArcLeRetour", "ArcLeRetour", "ready"),
                    DiscordRankChatDecoratorTest::lookup);
            TextColor storedNameColor = ComponentTextEditor.flatten(decorated).stream()
                    .filter(fragment -> fragment.text().contains("ArcLeRetour"))
                    .findFirst()
                    .orElseThrow()
                    .style()
                    .getColor();

            assertEquals(0x4CB4FA, storedNameColor.getValue());
            assertEquals(PARTY_SPEAKER_GOLD, RankGradientAnimation.animate(storedNameColor).getValue());
        });
    }

    @Test
    void leavesYellowNonPartyMessagesAlone() {
        Component message = Component.literal("ArcLeRetour: ready")
                .withStyle(Style.EMPTY.withColor(PARTY_YELLOW));

        assertSame(
                message,
                DiscordRankChatDecorator.decoratePartyChat(message, DiscordRankChatDecoratorTest::lookup));
    }

    @Test
    void leavesUnlinkedPartySpeakersAlone() {
        Component message = partyLine("SomeoneElse", "SomeoneElse", "ready");

        assertSame(
                message,
                DiscordRankChatDecorator.decoratePartyChat(message, DiscordRankChatDecoratorTest::lookup));
    }

    @Test
    void canColorTheRankPillWithoutColoringTheUsername() {
        withRankColoring(true, false, () -> {
            Component decorated = DiscordRankChatDecorator.decorateGuildChat(
                    guildLine("RECRUITER", "ArcLeRetour", "ArcLeRetour", "hi"),
                    DiscordRankChatDecoratorTest::lookup);
            List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(decorated);

            assertEquals(0x4CB4FA, pillBackgroundColors(decorated).getFirst());
            TextColor storedNameColor = fragments.stream()
                    .filter(fragment -> fragment.text().contains("ArcLeRetour"))
                    .findFirst()
                    .orElseThrow()
                    .style()
                    .getColor();
            assertEquals(0x4CB4FA, storedNameColor.getValue(), "the role color remains available to turn back on");
            assertNull(RankGradientAnimation.animate(storedNameColor), "rendering restores the inherited base color");
        });
    }

    @Test
    void canColorTheUsernameWithoutColoringTheRankPill() {
        withRankColoring(false, true, () -> {
            Component decorated = DiscordRankChatDecorator.decorateGuildChat(
                    guildLine("RECRUITER", "ArcLeRetour", "ArcLeRetour", "hi"),
                    DiscordRankChatDecoratorTest::lookup);
            List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(decorated);

            assertEquals(GUILD_AQUA, renderedPillBackgroundColors(decorated).getFirst());
            assertEquals(0x4CB4FA, colorOfFragmentContaining(fragments, "ArcLeRetour"));
        });
    }

    @Test
    void uncoloredNativeRankPillRestoresTheConfiguredChatColor() {
        Setting.ColorSetting previous = SeqClient.inGameGuildChatTextColorSetting;
        try {
            SeqClient.inGameGuildChatTextColorSetting =
                    new Setting.ColorSetting("in_game_guild_chat_text_color", "chat", 0xA1B2C3);
            withRankColoring(false, true, () -> {
                Component decorated = DiscordRankChatDecorator.decorateGuildChat(
                        wynncraftGuildLine("RECRUITER", "EightySix", "ArcLeRetour", "hi"),
                        DiscordRankChatDecoratorTest::lookup);

                assertEquals(0xA1B2C3, renderedPillBackgroundColors(decorated).getFirst());
                assertEquals(
                        0x4CB4FA, pillBackgroundColors(decorated).getFirst(), "the role color can be restored live");
            });
        } finally {
            SeqClient.inGameGuildChatTextColorSetting = previous;
        }
    }

    @Test
    void uncoloredBridgeUsernamePreservesItsCallerSuppliedStyle() {
        withRankColoring(true, false, () -> {
            Style original = Style.EMPTY.withColor(0xABCDEF).withInsertion("ArcLeRetour");

            List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(
                    DiscordRankChatDecorator.colouredName("ArcLeRetour", SAPLING, original));

            assertEquals(1, fragments.size());
            TextColor storedColor = fragments.getFirst().style().getColor();
            assertEquals(0x4CB4FA, storedColor.getValue());
            assertEquals(0xABCDEF, RankGradientAnimation.animate(storedColor).getValue());
            assertEquals("ArcLeRetour", fragments.getFirst().style().getInsertion());
        });
    }

    @Test
    void paintsNativeGuildMessageBodyWithConfiguredColorEvenWhenRanksAreDisabled() {
        Setting.BooleanSetting previousRanks = SeqClient.showDiscordRanksSetting;
        Setting.ColorSetting previousTextColor = SeqClient.inGameGuildChatTextColorSetting;
        try {
            SeqClient.showDiscordRanksSetting = new Setting.BooleanSetting("show_discord_ranks", "chat", false);
            SeqClient.inGameGuildChatTextColorSetting =
                    new Setting.ColorSetting("in_game_guild_chat_text_color", "chat", 0xA1B2C3);

            Component decorated = DiscordRankChatDecorator.decorateGuildChat(
                    wynncraftGuildLine("RECRUITER", "EightySix", "ArcLeRetour", "hello"));
            List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(decorated);

            assertEquals(0xA1B2C3, colorOfFragmentContaining(fragments, "hello"));
            assertEquals(DARK_AQUA, colorOfFragmentContaining(fragments, "EightySix"));
        } finally {
            SeqClient.showDiscordRanksSetting = previousRanks;
            SeqClient.inGameGuildChatTextColorSetting = previousTextColor;
        }
    }

    @Test
    void keepsNativeMultilineGuildRailAquaWhileRecoloringMessageText() {
        Component message = wynncraftGuildLine("RECRUITER", "EightySix", "ArcLeRetour", "first line")
                .copy()
                .append(Component.literal("\n"))
                .append(Component.literal(CONTINUATION_GLYPHS)
                        .withStyle(Style.EMPTY.withFont(ICON_FONT).withColor(GUILD_AQUA)))
                .append(Component.literal(" second line").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));

        Component decorated = DiscordRankChatDecorator.recolourGuildMessageText(
                message, TextColor.fromRgb(0xA1B2C3));
        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(decorated);

        assertEquals(0xA1B2C3, colorOfFragmentContaining(fragments, "first line"));
        assertEquals(GUILD_AQUA, colorOfFragmentContaining(fragments, CONTINUATION_GLYPHS));
        assertEquals(0xA1B2C3, colorOfFragmentContaining(fragments, "second line"));
    }

    @Test
    void leavesAmbiguousUsernameSlashNicknameAloneWithoutAccountMetadata() {
        Component message = guildLine("RECRUITER", "ArcLeRetour/EightySix", null, "hi");

        Component decorated =
                DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup);

        assertSame(message, decorated);
    }

    @Test
    void leavesAmbiguousNicknameFirstSlashNamesAloneWithoutAccountMetadata() {
        // A matching rank cannot prove which half names the actual account.
        Component message = guildLine("RECRUITER", "EightySix/ArcLeRetour", null, "hi");

        assertSame(message, DiscordRankChatDecorator.decorateGuildChat(
                message, DiscordRankChatDecoratorTest::lookup));
    }

    @Test
    void preservesParenthesizedRevealTextColourAndNicknameStyle() {
        for (String separator : List.of("", " ")) {
            Style nickname = Style.EMPTY.withItalic(true).withInsertion("cat").withHoverEvent(
                    new HoverEvent.ShowText(Component.literal("cat's real name is catsfield")));
            Component original = Component.empty()
                    .append(WynnPillGlyphs.encodePlainPill("STRATEGIST") + " ")
                    .append(Component.literal("cat").withStyle(nickname))
                    .append(Component.literal(separator + "(catsfield)").withStyle(Style.EMPTY.withColor(0xFF5555)))
                    .append(": test");
            Component decorated = DiscordRankChatDecorator.decorateGuildChat(original,
                    account -> account.equals("catsfield") ? DRUID : null);
            assertEquals(List.of("druid"), pillLabels(decorated));
            assertTrue(decorated.getString().endsWith("cat" + separator + "(catsfield): test"));
            var fragments = ComponentTextEditor.flatten(decorated);
            assertEquals(0xFF5555, colorOfFragmentContaining(fragments, "(catsfield)"));
            assertTrue(fragments.stream().filter(fragment -> "catsfield".equals(fragment.style().getInsertion()))
                    .allMatch(fragment -> fragment.style().isItalic()));
        }
    }

    @Test
    void preservesWynntilsRevealedPairsWhileDecoratingTheRealAccount() {
        for (String display : List.of("catsfield/cat", "cat/catsfield")) {
            Style identity = Style.EMPTY.withInsertion("cat").withItalic(true).withHoverEvent(
                    new HoverEvent.ShowText(Component.literal("§fcatsfield§7's nickname is §fcat")));
            Component original = Component.empty()
                    .append(WynnPillGlyphs.encodePlainPill("STRATEGIST") + " ")
                    .append(Component.literal(display).withStyle(identity)).append(": test");
            Component decorated = DiscordRankChatDecorator.decorateGuildChat(original,
                    account -> account.equals("catsfield") ? DRUID : null);
            assertEquals(List.of("druid"), pillLabels(decorated));
            assertTrue(decorated.getString().endsWith(display + ": test"));
            var name = ComponentTextEditor.flatten(decorated).stream()
                    .filter(fragment -> "catsfield".equals(fragment.style().getInsertion())).toList();
            assertEquals(display, name.stream().map(ComponentTextEditor.Fragment::text).reduce("", String::concat));
            assertTrue(name.stream().allMatch(fragment -> identity.getHoverEvent().equals(fragment.style().getHoverEvent())));
        }
    }

    @Test
    void resolvesTheLoggedUsernameSlashSpacedClassNickname() {
        Component message = guildLine("RECRUITER", "pat_crafter07/I Burger", null, "test");

        Component decorated =
                DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup);
        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(decorated);

        assertEquals(List.of("druid"), pillLabels(decorated));
        assertEquals(0xD7BCEA, colorOfFragmentContaining(fragments, "pat_crafter07"));
        assertEquals(0xD7BCEA, colorOfFragmentContaining(fragments, "I Burger"));
        assertTrue(decorated.getString().contains("pat_crafter07/I Burger: test"));
    }

    @Test
    void colorsAnUnrevealedSpacedNicknameResolvedFromItsRealUsernameHover() {
        Style nicknameStyle = Style.EMPTY
                .withColor(DARK_AQUA)
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("§fI Burger§7's real username is §fpat_crafter07")));
        Component message = Component.empty()
                .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUITER") + " "))
                .append(Component.literal("I Burger").withStyle(nicknameStyle))
                .append(Component.literal(": test").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));

        Component decorated =
                DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup);
        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(decorated);

        assertEquals(List.of("druid"), pillLabels(decorated));
        assertEquals(0xD7BCEA, colorOfFragmentContaining(fragments, "I Burger"));
        assertTrue(decorated.getString().contains("I Burger: test"));
    }

    @Test
    void colorsAscendedNunotWhenMetadataResolvesItsEmbeddedUsernameFirst() {
        Style nicknameStyle = Style.EMPTY.withColor(DARK_AQUA).withInsertion("nunot");
        Component message = Component.empty()
                .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUITER") + " "))
                .append(Component.literal("Ascended nunot").withStyle(nicknameStyle))
                .append(Component.literal(": test").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));

        RankPresentation nunotRank = presentation("rank.sprite", "Sprite", 84, 0xFF007B, 0xC54FA3);
        Component decorated = DiscordRankChatDecorator.decorateGuildChat(
                message, candidate -> candidate.equalsIgnoreCase("nunot") ? nunotRank : null);
        List<ComponentTextEditor.Fragment> name = ComponentTextEditor.flatten(decorated).stream()
                .filter(fragment -> "nunot".equals(fragment.style().getInsertion()))
                .toList();

        assertEquals("Ascended nunot", name.stream().map(ComponentTextEditor.Fragment::text).reduce("", String::concat));
        assertEquals(0xFF007B, name.getFirst().style().getColor().getValue());
        assertEquals(0xC54FA3, name.getLast().style().getColor().getValue());
    }

    @Test
    void colorsNicknamesContainingTheAccountNameAcrossTheirWholeGradient() {
        RankPresentation rank = presentation("rank.druid", "Druid", 92, 0xFF0000, 0x0000FF);
        for (String nickname : List.of("reyzhianator", "SuperReYzHiA", "xxreyzhiaxx", "reyzhia_2")) {
            Style style = Style.EMPTY.withColor(DARK_AQUA).withItalic(true)
                    .withHoverEvent(new HoverEvent.ShowText(
                            Component.literal(nickname + "'s real username is reyzhia")));
            Component message = Component.empty()
                    .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUITER") + " "))
                    .append(Component.literal(nickname).withStyle(style))
                    .append(Component.literal(": test").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));

            Component decorated = DiscordRankChatDecorator.decorateGuildChat(
                    message, candidate -> candidate.equalsIgnoreCase("reyzhia") ? rank : null);
            List<ComponentTextEditor.Fragment> name = ComponentTextEditor.flatten(decorated).stream()
                    .filter(fragment -> "reyzhia".equals(fragment.style().getInsertion()))
                    .toList();

            assertEquals(nickname, name.stream().map(ComponentTextEditor.Fragment::text)
                    .reduce("", String::concat), nickname);
            assertEquals(0xFF0000, name.getFirst().style().getColor().getValue());
            assertEquals(0x0000FF, name.getLast().style().getColor().getValue());
            assertTrue(name.stream().allMatch(fragment -> fragment.style().isItalic()));
            assertTrue(name.stream().allMatch(fragment -> style.getHoverEvent().equals(fragment.style().getHoverEvent())));
            assertTrue(decorated.getString().endsWith(nickname + ": test"));
        }
    }

    @Test
    void nicknameTokenBoundariesIgnoreComponentStyleSplits() {
        Component message = Component.empty()
                .append(Component.literal("  "))
                .append(Component.literal("reyzhia").withStyle(Style.EMPTY.withItalic(true)))
                .append(Component.literal("nator").withStyle(Style.EMPTY.withBold(true)))
                .append(Component.literal(": test"));
        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(message);
        String text = ComponentTextEditor.textOf(fragments);

        assertEquals(text.indexOf(':'), DiscordRankChatDecorator.speakerNameEnd(
                fragments, text, 2, text.indexOf(':'), "reyzhia"));
    }

    @Test
    void colorsTheNicknameContainingTheAccountNameButPreservesItsSeparateReveal() {
        for (String reveal : List.of("(reyzhia)", "[ReYzHiA]")) {
            Component message = Component.empty()
                    .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUITER") + " "))
                    .append(Component.literal("reyzhianator")
                            .withStyle(Style.EMPTY.withColor(DARK_AQUA).withInsertion("reyzhia")))
                    .append(Component.literal(reveal).withStyle(Style.EMPTY.withColor(0xFF5555)))
                    .append(Component.literal(": test").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));
            Component decorated = DiscordRankChatDecorator.decorateGuildChat(
                    message, candidate -> candidate.equalsIgnoreCase("reyzhia") ? DRUID : null);
            List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(decorated);

            assertEquals(0xD7BCEA, colorOfFragmentContaining(fragments, "reyzhianator"));
            assertEquals(0xFF5555, colorOfFragmentContaining(fragments, reveal));
        }
    }

    @Test
    void resolvesAUsernameAfterASpacedClassNickname() {
        Component message = guildLine("RECRUITER", "I Burger/pat_crafter07", null, "test");

        assertEquals(
                List.of("druid"),
                pillLabels(DiscordRankChatDecorator.decorateGuildChat(
                        message, DiscordRankChatDecoratorTest::lookup)));
    }

    @Test
    void recolorsTheCompleteSlashSeparatedDisplayName() {
        String text = "  ArcLeRetour/EightySix: hi";
        int colon = text.indexOf(':');

        assertEquals(
                text.indexOf(':'),
                DiscordRankChatDecorator.speakerNameEnd(
                        ComponentTextEditor.flatten(Component.literal(text)), text, 2, colon, "ArcLeRetour"));
    }

    @Test
    void leavesTheNickRevealInItsOwnColour() {
        Component message = Component.empty()
                .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUITER") + " "))
                .append(Component.literal("EightySix")
                        .withStyle(Style.EMPTY.withColor(GUILD_AQUA).withInsertion("ArcLeRetour")))
                .append(Component.literal("(ArcLeRetour)").withStyle(Style.EMPTY.withColor(0xFF5555)))
                .append(Component.literal(": hi").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));

        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(
                DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup));

        assertEquals(0x4CB4FA, colorOfFragmentContaining(fragments, "EightySix"));
        assertEquals(0xFF5555, colorOfFragmentContaining(fragments, "(ArcLeRetour)"));
    }

    @Test
    void leavesTheNickRevealAloneEvenWhenItIsNotParenthesised() {
        // The reveal is protected by its colour, not by the shape of the text, so it
        // survives whatever delimiters Wynntils happens to render it with.
        Component message = Component.empty()
                .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUITER") + " "))
                .append(Component.literal("EightySix")
                        .withStyle(Style.EMPTY.withColor(GUILD_AQUA).withInsertion("ArcLeRetour")))
                .append(Component.literal("[ArcLeRetour]").withStyle(Style.EMPTY.withColor(0xFF5555)))
                .append(Component.literal(": hi").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));

        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(
                DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup));

        assertEquals(0x4CB4FA, colorOfFragmentContaining(fragments, "EightySix"));
        assertEquals(0xFF5555, colorOfFragmentContaining(fragments, "[ArcLeRetour]"));
    }

    @Test
    void leavesAnyDistinctlyColouredPartOfTheNameRegionAlone() {
        Component message = Component.empty()
                .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUITER") + " "))
                .append(Component.literal("ArcLeRetour")
                        .withStyle(Style.EMPTY.withColor(GUILD_AQUA).withInsertion("ArcLeRetour")))
                .append(Component.literal(" ★").withStyle(Style.EMPTY.withColor(0xFFAA00)))
                .append(Component.literal(": hi").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));

        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(
                DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup));

        assertEquals(0x4CB4FA, colorOfFragmentContaining(fragments, "ArcLeRetour"));
        assertEquals(0xFFAA00, colorOfFragmentContaining(fragments, "★"));
    }

    @Test
    void recolorsNothingWhenTheNameAndTheRevealShareOneFragment() {
        // No safe cut exists here, so the name stays plain rather than risking the
        // reveal being repainted.
        Component message = Component.empty()
                .append(Component.literal(WynnPillGlyphs.encodePlainPill("RECRUITER") + " "))
                .append(Component.literal("EightySix(ArcLeRetour)")
                        .withStyle(Style.EMPTY.withColor(GUILD_AQUA).withInsertion("ArcLeRetour")))
                .append(Component.literal(": hi").withStyle(Style.EMPTY.withColor(GUILD_AQUA)));

        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(
                DiscordRankChatDecorator.decorateGuildChat(message, DiscordRankChatDecoratorTest::lookup));

        assertEquals(GUILD_AQUA, colorOfFragmentContaining(fragments, "EightySix(ArcLeRetour)"));
    }

    @Test
    void speakerNameEndCutsAtTheFragmentHoldingTheRealUsername() {
        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(Component.empty()
                .append(Component.literal("  "))
                .append(Component.literal("EightySix"))
                .append(Component.literal("(ArcLeRetour)"))
                .append(Component.literal(": hi")));
        String text = ComponentTextEditor.textOf(fragments);

        assertEquals(
                text.indexOf('('),
                DiscordRankChatDecorator.speakerNameEnd(fragments, text, 2, text.indexOf(':'), "ArcLeRetour"));
    }

    @Test
    void speakerNameEndCoversTheWholeNameWhenThereIsNoNick() {
        List<ComponentTextEditor.Fragment> fragments =
                ComponentTextEditor.flatten(Component.literal("  ArcLeRetour: hi"));
        String text = ComponentTextEditor.textOf(fragments);

        assertEquals(
                text.indexOf(':'),
                DiscordRankChatDecorator.speakerNameEnd(fragments, text, 2, text.indexOf(':'), "ArcLeRetour"));
    }

    @Test
    void paintsThePillWithTheDiscordRoleColour() {
        assertEquals(0x4CB4FA, DiscordRankChatDecorator.colorFor(SAPLING).getValue());
        assertEquals(
                0x1B9056,
                DiscordRankChatDecorator.colorFor(presentation("rank.treant", "Treant", 104, 0x1B9056))
                        .getValue());
    }

    @Test
    void fallsBackToANeutralColourForUncolouredRanks() {
        RankPresentation uncoloured =
                new RankPresentation(new DiscordRank("rank.upper_strategist", "Upper Strategist", 96), ColorRamp.empty());

        assertNotNull(DiscordRankChatDecorator.colorFor(uncoloured));
        assertFalse(DiscordRankChatDecorator.rampFor(uncoloured).isEmpty(), "the pill still needs a colour");
    }

    @Test
    void drawsEveryPillLabelInTheSameDarkColour() {
        // Dryad is a near-white pastel, Yggdrasil a deep purple, Sapling a mid blue.
        // A label that followed its pill left the first washed out and made one rank
        // read differently from one member to the next.
        RankPresentation pale = presentation("rank.dryad", "Dryad", 112, 0xCDECE4);
        RankPresentation deep = presentation("rank.yggdrasil", "Ygg", 120, 0x7506D6);

        int onPale = pillLabelColors(DiscordRankChatDecorator.rankPill(pale, null)).getFirst();
        int onDeep = pillLabelColors(DiscordRankChatDecorator.rankPill(deep, null)).getFirst();
        int onMid = pillLabelColors(DiscordRankChatDecorator.rankPill(SAPLING, null)).getFirst();

        assertEquals(onPale, onDeep, "every role shares one label colour");
        assertEquals(onPale, onMid, "every role shares one label colour");
        assertTrue(luminanceOf(onPale) < 0.15d, "and a dark one, was " + Integer.toHexString(onPale));
        assertNotEquals(0x000000, onPale, "though short of pure black");
    }

    @Test
    void paintsAGradientRoleAcrossThePillInsteadOfFlatteningItToTheFirstStop() {
        RankPresentation gradient = presentation("rank.yggdrasil", "Ygg", 120, 0x000000, 0xFFFFFF);

        List<Integer> backgrounds = pillBackgroundColors(DiscordRankChatDecorator.rankPill(gradient, null));

        assertEquals(3, backgrounds.size(), "one background block per glyph");
        assertEquals(0x000000, backgrounds.getFirst());
        assertEquals(0xFFFFFF, backgrounds.getLast());
        assertTrue(
                backgrounds.get(1) > backgrounds.getFirst() && backgrounds.get(1) < backgrounds.getLast(),
                "the middle glyph must sit between the two stops, was " + backgrounds.get(1));
    }

    @Test
    void keepsASolidRoleFlatSoNothingChangesForNonGradientRanks() {
        List<Integer> backgrounds = pillBackgroundColors(DiscordRankChatDecorator.rankPill(SAPLING, null));

        assertEquals(List.of(0x4CB4FA, 0x4CB4FA, 0x4CB4FA), backgrounds.subList(0, 3));
    }

    @Test
    void pillTooltipShowsOnlyTheReplacedInGameRank() {
        Component pill = DiscordRankChatDecorator.rankPill(
                SAPLING, "recruiter", TextColor.fromRgb(GUILD_AQUA), "ArcLeRetour");

        assertEquals("In-game rank: Recruiter", hoverText(pill));
    }

    @Test
    void pillStillHasAHoverTargetWhenNoInGameRankMetadataExists() {
        Component pill = DiscordRankChatDecorator.rankPill(
                SAPLING, null, TextColor.fromRgb(GUILD_AQUA), "SomeoneElse");

        assertEquals("In-game rank: Unknown", hoverText(pill));
    }

    @Test
    void switchesARankPillBetweenIndividualAndRoleColors() {
        RankPresentation presentation = new RankPresentation(
                new DiscordRank("rank.sapling", "Sapling", 88),
                ColorRamp.of(0x4CB4FA),
                ColorRamp.of(0xFF00FF));
        Component pill = DiscordRankChatDecorator.rankPill(presentation, null);

        withPerUserColors(true, () -> assertEquals(
                Set.of(0xFF00FF), Set.copyOf(renderedPillBackgroundColors(pill))));
        withPerUserColors(false, () -> assertEquals(
                Set.of(0x4CB4FA), Set.copyOf(renderedPillBackgroundColors(pill))));
    }

    @Test
    void keepsOneLabelColourAcrossAGradient() {
        // Only the background is graded. Letters that shifted hue from one to the next
        // read as a rendering fault rather than as a gradient.
        RankPresentation gradient = presentation("rank.treant", "Treant", 104, 0x1B9056, 0x50C9A6);

        List<Integer> labels = pillLabelColors(DiscordRankChatDecorator.rankPill(gradient, null));

        assertEquals(1, Set.copyOf(labels).size(), "every letter shares one colour, was " + labels);
    }

    @Test
    void primaryColourHelperTakesTheFirstStopOfAGradientRole() {
        RankPresentation gradient = presentation("rank.yggdrasil", "Ygg", 120, 0x123456, 0xFFFFFF);

        assertEquals(0x123456, DiscordRankChatDecorator.colorFor(gradient).getValue());
    }

    @Test
    void primaryColourHelperAvoidsWynncraftShaderMarkers() {
        RankPresentation gradient = presentation("rank.yggdrasil", "Ygg", 120, 0x00F000, 0x72D400);

        assertEquals(0x00F400, DiscordRankChatDecorator.colorFor(gradient).getValue());
    }

    @Test
    void avoidsWynncraftShaderMarkersCrossedByAUsernameGradient() {
        String username = "ABCDEFGHIJKLMNOPQR";
        RankPresentation gradient = presentation("rank.yggdrasil", "Ygg", 120, 0x25FF00, 0x72D400);

        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(
                DiscordRankChatDecorator.colouredName(username, gradient, Style.EMPTY.withInsertion(username)));

        assertEquals(username, fragments.stream().map(ComponentTextEditor.Fragment::text).reduce("", String::concat));
        assertEquals(0x49EC00, fragments.get(8).style().getColor().getValue());
        assertEquals(0x4EEC00, fragments.get(9).style().getColor().getValue());
        assertTrue(fragments.stream().allMatch(fragment -> username.equals(fragment.style().getInsertion())));
    }

    @Test
    void avoidsMovementShadowMarkersCrossedByTheAlternateGreenGradient() {
        String username = "ABCDEFGHIJKLMNOPQR";
        RankPresentation gradient = presentation("rank.yggdrasil", "Ygg", 120, 0x40FF40, 0xC0E100);

        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(
                DiscordRankChatDecorator.colouredName(username, gradient, Style.EMPTY.withInsertion(username)));

        assertEquals(username, fragments.stream().map(ComponentTextEditor.Fragment::text).reduce("", String::concat));
        assertEquals(0x9AEC13, fragments.get(12).style().getColor().getValue());
        assertEquals(0xA2EC0F, fragments.get(13).style().getColor().getValue());
        assertTrue(fragments.stream().allMatch(fragment -> username.equals(fragment.style().getInsertion())));
    }

    @Test
    void paintsTheGuildSpeakerAcrossTheirGradientAndKeepsInsertionStyling() {
        RankPresentation gradient = presentation("rank.yggdrasil", "Ygg", 120, 0x123456, 0xFFFFFF);
        Component message = guildLine("RECRUITER", "ArcLeRetour", "ArcLeRetour", "hi");

        Component decorated = DiscordRankChatDecorator.decorateGuildChat(message, ignored -> gradient);
        List<ComponentTextEditor.Fragment> name = ComponentTextEditor.flatten(decorated).stream()
                .filter(fragment -> "ArcLeRetour".equals(fragment.style().getInsertion()))
                .toList();

        assertEquals("ArcLeRetour", name.stream().map(ComponentTextEditor.Fragment::text).reduce("", String::concat));
        assertEquals("A", name.getFirst().text());
        assertEquals("r", name.getLast().text());
        assertEquals(0x123456, name.getFirst().style().getColor().getValue());
        assertEquals(0xFFFFFF, name.getLast().style().getColor().getValue());
        assertTrue(
                name.get(1).style().getColor().getValue() > 0x123456,
                "the middle of the name must be sampled from inside the ramp");
    }

    @Test
    void switchesAUsernameFromItsSolidIndividualColorToItsGradientRolePalette() {
        RankPresentation presentation = new RankPresentation(
                new DiscordRank("rank.yggdrasil", "Ygg", 120),
                ColorRamp.of(List.of(0x000000, 0xFFFFFF)),
                ColorRamp.of(0xFF00FF));
        List<TextColor> stored = ComponentTextEditor.flatten(DiscordRankChatDecorator.colouredName(
                        "Name", presentation, Style.EMPTY.withColor(GUILD_AQUA)))
                .stream()
                .map(fragment -> fragment.style().getColor())
                .toList();

        withPerUserColors(true, () -> assertEquals(
                Set.of(0xFF00FF),
                Set.copyOf(stored.stream()
                        .map(RankGradientAnimation::animate)
                        .map(TextColor::getValue)
                        .toList())));
        withPerUserColors(false, () -> {
            List<Integer> rendered = stored.stream()
                    .map(RankGradientAnimation::animate)
                    .map(TextColor::getValue)
                    .toList();
            assertEquals(0x000000, rendered.getFirst());
            assertEquals(0xFFFFFF, rendered.getLast());
        });
    }

    @Test
    void keepsGradientRegistrationWhenItsEndStopMatchesTheBaseColor() {
        RankPresentation presentation = new RankPresentation(
                new DiscordRank("rank.treant", "Treant", 102),
                ColorRamp.of(0xFF00FF),
                ColorRamp.of(List.of(0xFF0000, 0xFFFFFF)));
        List<TextColor> stored = ComponentTextEditor.flatten(DiscordRankChatDecorator.colouredName(
                        "MrHmar", presentation, Style.EMPTY.withColor(0xFFFFFF)))
                .stream()
                .map(fragment -> fragment.style().getColor())
                .toList();

        assertEquals(0xFFFFFF, stored.getLast().getValue());
        withPerUserColors(false, () -> assertEquals(
                0xFF00FF,
                RankGradientAnimation.animate(stored.getLast()).getValue(),
                "the white endpoint must remain a registered gradient colour"));
    }

    @Test
    void keepsASolidSpeakerNameAsOneFragment() {
        MutableComponent name = DiscordRankChatDecorator.colouredName(
                "ArcLeRetour", SAPLING, Style.EMPTY.withInsertion("ArcLeRetour"));

        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(name);
        assertEquals(1, fragments.size());
        assertEquals(0x4CB4FA, fragments.getFirst().style().getColor().getValue());
        assertEquals("ArcLeRetour", fragments.getFirst().style().getInsertion());
    }

    @Test
    void bridgeInsigniaIsSuppressedWhileTheSettingIsOff() {
        // The bridge builds its own line, so unlike guild chat it never passes through
        // decorateGuildChat's early return and has to check the setting itself.
        withDiscordRanks(false, () -> {
            AtomicBoolean consulted = new AtomicBoolean();

            MutableComponent insignia = DiscordRankChatDecorator.bridgeInsignia("dix", name -> {
                consulted.set(true);
                return SeqBadgeTier.DIAMOND;
            });

            assertNull(insignia, "no insignia may be drawn with ranks turned off");
            assertFalse(consulted.get(), "and the roster must not even be consulted");
        });
    }

    @Test
    void bridgeInsigniaIsDrawnWhileTheSettingIsOn() {
        withDiscordRanks(
                true,
                () -> assertNotNull(DiscordRankChatDecorator.bridgeInsignia("dix", name -> SeqBadgeTier.DIAMOND)));
    }

    @Test
    void insigniaLeavesASpaceAfterTheUsername() {
        Component insignia = DiscordRankChatDecorator.insigniaBadge(SeqBadgeTier.DIAMOND);

        assertEquals(" \uF8E3", insignia.getString());
        assertEquals(" ", ComponentTextEditor.flatten(insignia).getFirst().text());
        assertEquals(FontDescription.DEFAULT, ComponentTextEditor.flatten(insignia).getFirst().style().getFont());
    }

    @Test
    void insigniaObeyTheChatSettingEvenWhileRanksStayOn() {
        withSettings(true, false, () -> {
            AtomicBoolean consulted = new AtomicBoolean();

            MutableComponent insignia = DiscordRankChatDecorator.bridgeInsignia("dix", name -> {
                consulted.set(true);
                return SeqBadgeTier.DIAMOND;
            });

            assertNull(insignia, "no insignia with the chat setting off");
            assertFalse(consulted.get(), "and the roster must not even be consulted");
        });
    }

    @Test
    void guildChatDropsTheInsigniaWhenTheChatSettingIsOff() {
        List<ComponentTextEditor.Fragment> fragments =
                ComponentTextEditor.flatten(Component.literal("Name: hi"));
        int colon = "Name".length();

        withSettings(true, false, () -> assertSame(
                fragments,
                DiscordRankChatDecorator.insertInsignia(fragments, colon, "ArcLeRetour"),
                "the line must come back untouched"));
    }

    private static void withDiscordRanks(boolean enabled, Runnable body) {
        withSettings(enabled, true, body);
    }

    private static void withRankColoring(boolean pills, boolean usernames, Runnable body) {
        Setting.BooleanSetting previousPills = SeqClient.colorRankPillsSetting;
        Setting.BooleanSetting previousUsernames = SeqClient.colorUsernamesSetting;
        try {
            SeqClient.colorRankPillsSetting = new Setting.BooleanSetting("color_rank_pills", "chat", pills);
            SeqClient.colorUsernamesSetting = new Setting.BooleanSetting("color_usernames", "chat", usernames);
            body.run();
        } finally {
            SeqClient.colorRankPillsSetting = previousPills;
            SeqClient.colorUsernamesSetting = previousUsernames;
        }
    }

    private static void withPerUserColors(boolean enabled, Runnable body) {
        Setting.BooleanSetting previous = SeqClient.usePerUserColorsSetting;
        try {
            SeqClient.usePerUserColorsSetting =
                    new Setting.BooleanSetting("use_per_user_colors", "chat", enabled);
            body.run();
        } finally {
            SeqClient.usePerUserColorsSetting = previous;
        }
    }

    private static void withSettings(boolean ranks, boolean insignia, Runnable body) {
        Setting.BooleanSetting previousRanks = SeqClient.showDiscordRanksSetting;
        Setting.BooleanSetting previousInsignia = SeqClient.showChatInsigniasSetting;
        try {
            SeqClient.showDiscordRanksSetting = new Setting.BooleanSetting("show_discord_ranks", "chat", ranks);
            SeqClient.showChatInsigniasSetting =
                    new Setting.BooleanSetting("show_chat_insignias", "chat", insignia);
            body.run();
        } finally {
            SeqClient.showDiscordRanksSetting = previousRanks;
            SeqClient.showChatInsigniasSetting = previousInsignia;
        }
    }

    @Test
    void nametagInsigniaSettingDoesNotControlChatInsignias() {
        Setting.BooleanSetting previousNametagInsignias = SeqClient.showInsigniaBadgesSetting;
        try {
            SeqClient.showInsigniaBadgesSetting =
                    new Setting.BooleanSetting("show_insignia_badges", "leaderboard_badges", false);
            withSettings(
                    true,
                    true,
                    () -> assertNotNull(
                            DiscordRankChatDecorator.bridgeInsignia("dix", name -> SeqBadgeTier.DIAMOND)));
        } finally {
            SeqClient.showInsigniaBadgesSetting = previousNametagInsignias;
        }
    }

    @Test
    void bridgeColoringRequiresBothTheInGameRankParentAndItsChildSetting() {
        Setting.BooleanSetting previousRanks = SeqClient.showDiscordRanksSetting;
        Setting.BooleanSetting previousBridgeColoring = SeqClient.colorDiscordBridgeSetting;
        try {
            SeqClient.showDiscordRanksSetting = new Setting.BooleanSetting("show_discord_ranks", "chat", false);
            SeqClient.colorDiscordBridgeSetting = new Setting.BooleanSetting("color_discord_bridge", "chat", true);
            assertFalse(DiscordRankChatDecorator.bridgeColoringEnabled());

            SeqClient.showDiscordRanksSetting.setValue(true);
            SeqClient.colorDiscordBridgeSetting.setValue(false);
            assertFalse(DiscordRankChatDecorator.bridgeColoringEnabled());

            SeqClient.colorDiscordBridgeSetting.setValue(true);
            assertTrue(DiscordRankChatDecorator.bridgeColoringEnabled());
        } finally {
            SeqClient.showDiscordRanksSetting = previousRanks;
            SeqClient.colorDiscordBridgeSetting = previousBridgeColoring;
        }
    }

    @Test
    void pillLabelIsUppercasedForTheWynncraftFont() {
        assertEquals("SAPLING", SAPLING.pillLabel());
    }

    @Test
    void usesAnIconThenAnAlignedBarForConsecutiveBridgeMessages() {
        DiscordRankChatDecorator.decorateGuildChat(Component.literal("ordinary chat"));

        Component firstPrefix = DiscordRankChatDecorator.bridgePrefix();
        Component bridgeLine = Component.literal("bridged line");
        DiscordRankChatDecorator.displayUndecorated(bridgeLine, () -> {});
        // Another mod may hand the line back late; it must not close its own block.
        DiscordRankChatDecorator.decorateGuildChat(bridgeLine);
        Component continuationPrefix = DiscordRankChatDecorator.bridgePrefix();

        assertEquals(DiscordRankChatDecorator.BRIDGE_ICON_GLYPH + " ", firstPrefix.getString());
        assertEquals(
                DiscordRankChatDecorator.BRIDGE_CONTINUATION_GLYPH + " ",
                continuationPrefix.getString());
        assertEquals(
                new FontDescription.Resource(Identifier.fromNamespaceAndPath("seq", "discord_bridge")),
                ComponentTextEditor.flatten(firstPrefix).getFirst().style().getFont());

        DiscordRankChatDecorator.decorateGuildChat(Component.literal("interrupting chat"));
        assertEquals(
                DiscordRankChatDecorator.BRIDGE_ICON_GLYPH + " ",
                DiscordRankChatDecorator.bridgePrefix().getString());
    }

    @Test
    void consecutiveRelayedGuildMessagesUseTheBarAndKeepTheirSender() {
        GuildChatMarkers.reset();
        DiscordRankChatDecorator.resetChatSequences();
        try {
            Component first = ChatManager.guildSenderLine(
                    new ConnectionManager.GuildChatMessage("First", null, "hello"), "hello", null);
            assertTrue(first.getString().startsWith("➜ "));
            DiscordRankChatDecorator.displayGuildLine(first, () -> {});
            // A delayed display or a world-link rewrite must preserve the sequence and wrapping rail.
            Component rewritten = first.copy();
            DiscordRankChatDecorator.retainBridgeRail(first, rewritten);
            DiscordRankChatDecorator.decorateGuildChat(rewritten);
            Component next = ChatManager.guildSenderLine(
                    new ConnectionManager.GuildChatMessage("Second", null, "hi"), "hi", null);
            assertTrue(next.getString().startsWith("│ "));
            assertTrue(next.getString().contains("Second: hi"));
            DiscordRankChatDecorator.displayGuildLine(next, () -> {});
            assertEquals(DiscordRankChatDecorator.guildPrefix(true).getString(),
                    DiscordRankChatDecorator.bridgeContinuationPrefixFor(next).getString());
            Component continuation = ChatManager.guildContinuationLine("another line");
            DiscordRankChatDecorator.displayGuildLine(continuation, () -> {});
            assertEquals("│ ", DiscordRankChatDecorator.guildSenderPrefix().getString());
        } finally {
            DiscordRankChatDecorator.resetChatSequences();
            GuildChatMarkers.reset();
        }
    }

    @Test
    void otherChatAndChatClearingRestartTheRelayedGuildPrefix() {
        GuildChatMarkers.reset();
        DiscordRankChatDecorator.resetChatSequences();
        try {
            Component guild = Component.literal("relayed guild line");
            DiscordRankChatDecorator.displayGuildLine(guild, () -> {});
            assertEquals("│ ", DiscordRankChatDecorator.guildSenderPrefix().getString());
            DiscordRankChatDecorator.decorateGuildChat(Component.literal("ordinary chat"));
            assertEquals("➜ ", DiscordRankChatDecorator.guildSenderPrefix().getString());

            DiscordRankChatDecorator.displayGuildLine(guild, () -> {});
            DiscordRankChatDecorator.displayUndecorated(Component.literal("Discord chat"), () -> {});
            assertEquals("➜ ", DiscordRankChatDecorator.guildSenderPrefix().getString());
            DiscordRankChatDecorator.displayGuildLine(guild, () -> {});
            assertEquals(DiscordRankChatDecorator.BRIDGE_ICON_GLYPH + " ",
                    DiscordRankChatDecorator.bridgePrefix().getString());

            DiscordRankChatDecorator.resetChatSequences();
            assertEquals("➜ ", DiscordRankChatDecorator.guildSenderPrefix().getString());
        } finally {
            DiscordRankChatDecorator.resetChatSequences();
            GuildChatMarkers.reset();
        }
    }

    /** A bridged line stays railed after world links rebuild the component. */
    @Test
    void rewrittenBridgeLineKeepsItsRail() {
        Component bridgeLine = Component.literal("bridged line");
        DiscordRankChatDecorator.displayUndecorated(bridgeLine, () -> {});
        Component rewritten = Component.literal("bridged line");

        assertNull(DiscordRankChatDecorator.bridgeContinuationPrefixFor(rewritten));
        DiscordRankChatDecorator.retainBridgeRail(bridgeLine, rewritten);

        assertEquals(
                DiscordRankChatDecorator.bridgeContinuationPrefixFor(bridgeLine).getString(),
                DiscordRankChatDecorator.bridgeContinuationPrefixFor(rewritten).getString());
    }

    @Test
    void neutralBridgeMessageDoesNotOpenAColoredContinuationSequence() {
        DiscordRankChatDecorator.decorateGuildChat(Component.literal("ordinary chat"));
        Component neutralLine = Component.literal("neutral bridge line");

        DiscordRankChatDecorator.displayUndecorated(neutralLine, () -> {}, false);

        assertEquals(
                DiscordRankChatDecorator.BRIDGE_ICON_GLYPH + " ",
                DiscordRankChatDecorator.bridgePrefix().getString());
    }

    /** A rank with the colours already resolved for the member, as the service returns it. */
    private static RankPresentation presentation(String key, String label, int position, int... colors) {
        return new RankPresentation(
                new DiscordRank(key, label, position),
                ColorRamp.of(Arrays.stream(colors).boxed().toList()));
    }

    /** Mirrors {@code DiscordRankService}, which indexes members under lowercase names. */
    private static RankPresentation lookup(String username) {
        return RANKS.get(username.toLowerCase(Locale.ROOT));
    }

    /**
     * A guild line shaped like the real thing, per {@code latest.log}: timestamp, the
     * guild icon in its own font, badge geometry and letters in the badge font, then
     * the speaker with the nick reveal appended as a legacy {@code §c} run.
     */
    private static Component wynncraftGuildLine(String rank, String nick, String username, String body) {
        return Component.empty()
                .append(Component.literal("[19:25:32] "))
                .append(Component.literal(ICON_GLYPHS).withStyle(Style.EMPTY.withFont(ICON_FONT)))
                .append(Component.literal(" "))
                .append(Component.literal(BADGE_GEOMETRY).withStyle(Style.EMPTY.withFont(BADGE_FONT)))
                .append(Component.literal(WynnPillGlyphs.encodePlainPill(rank))
                        .withStyle(Style.EMPTY.withFont(BADGE_FONT).withColor(0x000000)))
                .append(Component.literal(" "))
                .append(Component.literal(nick + LEGACY_RED + "(" + username + ")" + LEGACY_RESET)
                        .withStyle(Style.EMPTY.withColor(DARK_AQUA).withInsertion(username)))
                .append(Component.literal(":").withStyle(Style.EMPTY.withColor(DARK_AQUA)))
                .append(Component.literal(" " + body).withStyle(Style.EMPTY.withColor(GUILD_AQUA)));
    }

    /** Mirrors a Wynntils-rendered party line: timestamp, party marker and speaker. */
    private static Component partyLine(String displayedName, String insertion, String body) {
        return partyLine(PARTY_ICON_GLYPHS, displayedName, insertion, body);
    }

    private static Component partyLine(String marker, String displayedName, String insertion, String body) {
        Style nameStyle = Style.EMPTY.withColor(PARTY_SPEAKER_GOLD).withInsertion(insertion);
        return Component.empty()
                .append(Component.literal("[19:25:32] "))
                .append(Component.literal(marker)
                        .withStyle(Style.EMPTY.withFont(ICON_FONT).withColor(PARTY_YELLOW)))
                .append(Component.literal(" ").withStyle(Style.EMPTY.withColor(PARTY_YELLOW)))
                .append(Component.literal(displayedName).withStyle(nameStyle))
                .append(Component.literal(":").withStyle(Style.EMPTY.withColor(PARTY_SPEAKER_GOLD)))
                .append(Component.literal(" " + body).withStyle(Style.EMPTY.withColor(PARTY_YELLOW)));
    }

    /** Mirrors a Wynntils-rendered guild line: timestamp, rank pill, speaker, message. */
    private static Component guildLine(String rank, String displayedName, String insertion, String body) {
        Style nameStyle = insertion == null ? Style.EMPTY : Style.EMPTY.withInsertion(insertion);
        return Component.empty()
                .append(Component.literal("[09:33:28] "))
                .append(Component.literal(WynnPillGlyphs.encodePlainPill(rank) + " "))
                .append(Component.literal(displayedName).withStyle(nameStyle))
                .append(Component.literal(": " + body));
    }

    private static String layeredGuildRankPill(String rank, int advance) {
        StringBuilder badge = new StringBuilder().appendCodePoint(0xE060);
        rank.codePoints().forEach(letter -> badge.appendCodePoint(0xCFFFF)
                .appendCodePoint(0xE030 + letter - 'a'));
        badge.appendCodePoint(0xCFFFF).appendCodePoint(0xE062).appendCodePoint(advance);
        rank.codePoints().forEach(letter -> badge.appendCodePoint(0xE000 + letter - 'a'));
        return badge.appendCodePoint(0xD0002).toString();
    }

    private static int colorOfFragmentContaining(List<ComponentTextEditor.Fragment> fragments, String needle) {
        return fragments.stream()
                .filter(fragment -> fragment.text().contains(needle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no fragment holds " + needle))
                .style()
                .getColor()
                .getValue();
    }

    private static String hoverText(Component component) {
        return ComponentTextEditor.flatten(component).stream()
                .map(fragment -> fragment.style().getHoverEvent())
                .filter(HoverEvent.ShowText.class::isInstance)
                .map(HoverEvent.ShowText.class::cast)
                .map(event -> event.value().getString())
                .findFirst()
                .orElse(null);
    }

    /**
     * Colours of the pill's background blocks, one per glyph, in order. The corners
     * are excluded: they carry the end stops and would mask a flat middle.
     */
    private static List<Integer> pillBackgroundColors(Component pill) {
        return ComponentTextEditor.flatten(pill).stream()
                .filter(fragment -> fragment.text().indexOf(WynnPillGlyphs.BACKGROUND) >= 0)
                .map(fragment -> fragment.style().getColor().getValue())
                .toList();
    }

    private static List<Integer> renderedPillBackgroundColors(Component pill) {
        return ComponentTextEditor.flatten(pill).stream()
                .filter(fragment -> fragment.text().indexOf(WynnPillGlyphs.BACKGROUND) >= 0)
                .map(fragment -> RankGradientAnimation.animate(fragment.style().getColor()))
                .map(TextColor::getValue)
                .toList();
    }

    /** Rec. 709 relative luminance in {@code [0, 1]}, to argue about readability with. */
    private static double luminanceOf(int rgb) {
        return 0.2126d * ((rgb >> 16) & 0xFF) / 255d
                + 0.7152d * ((rgb >> 8) & 0xFF) / 255d
                + 0.0722d * (rgb & 0xFF) / 255d;
    }

    /** Colours of the pill's letter glyphs, one per glyph, in order. */
    private static List<Integer> pillLabelColors(Component pill) {
        return ComponentTextEditor.flatten(pill).stream()
                .filter(fragment -> fragment.text().indexOf(WynnPillGlyphs.TEXT_OFFSET) >= 0)
                .map(fragment -> fragment.style().getColor().getValue())
                .toList();
    }

    private static List<String> pillLabels(Component component) {
        return WynnPillGlyphs.findPills(component.getString()).stream()
                .map(pill -> pill.label().toLowerCase(Locale.ROOT))
                .toList();
    }
}
