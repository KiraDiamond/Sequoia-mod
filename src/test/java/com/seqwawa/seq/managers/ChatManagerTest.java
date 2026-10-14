package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.config.Setting;
import com.seqwawa.seq.model.DiscordRank;
import com.seqwawa.seq.model.RankPresentation;
import com.seqwawa.seq.network.ConnectionManager;
import com.seqwawa.seq.utils.ColorRamp;
import com.seqwawa.seq.utils.ComponentTextEditor;
import com.seqwawa.seq.utils.RankGradientAnimation;
import com.seqwawa.seq.utils.WynnPillGlyphs;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.seqwawa.seq.integrations.WynntilsGuildRankAccess;

class ChatManagerTest {

    @Test
    void queueCommandsExtractOnlyLowercaseGuildShorthand() {
        assertEquals("hello", ChatManager.queueGuildCommandMessage("g hello"));
        assertEquals("chat hello", ChatManager.queueGuildCommandMessage("g chat hello"));
        assertEquals("hello  guild", ChatManager.queueGuildCommandMessage("g \t hello  guild  "));
        assertEquals("hello", ChatManager.queueGuildCommandMessage("g\thello"));
        for (String command : List.of("g", "g ", "g\t  ", "G hello", "guild chat hello", "gc hello", "ghello", " g hello", "seq connect")) {
            assertNull(ChatManager.queueGuildCommandMessage(command), command);
        }
        assertNull(ChatManager.queueGuildCommandMessage(null));
    }

    @Test
    void queuedGuildCommandsSendOnceAndCancelEvenWhenSendingFails() {
        var sent = new java.util.ArrayList<String>();
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        assertFalse(ChatManager.allowQueueGuildCommand("g hello", true, message -> {
            sent.add(message);
            return true;
        }, failures::incrementAndGet));
        assertEquals(List.of("hello"), sent);
        assertEquals(0, failures.get());

        assertFalse(ChatManager.allowQueueGuildCommand("g another", true, message -> {
            sent.add(message);
            return false;
        }, failures::incrementAndGet));
        assertEquals(List.of("hello", "another"), sent);
        assertEquals(1, failures.get());

        assertTrue(ChatManager.allowQueueGuildCommand("g normal", false, message -> {
            throw new AssertionError("Non-queue commands must pass through");
        }, failures::incrementAndGet));
        for (String command : List.of("g", "g ", "guild chat hello", "seq connect")) {
            assertTrue(ChatManager.allowQueueGuildCommand(command, true, message -> {
                throw new AssertionError("Unmatched commands must pass through");
            }, failures::incrementAndGet));
        }
        assertEquals(1, failures.get());
    }

    @Test
    void guildStylingIsIndependentOfDiscordSettingsAndHonorsGuildDecorationGate() {
        var previousRanks = SeqClient.showDiscordRanksSetting;
        var previousChat = SeqClient.showDiscordChatSetting;
        var previousBridge = SeqClient.colorDiscordBridgeSetting;
        try {
            SeqClient.showDiscordRanksSetting = new Setting.BooleanSetting("show_discord_ranks", "chat", false);
            SeqClient.showDiscordChatSetting = new Setting.BooleanSetting("show_discord_chat", "chat", false);
            SeqClient.colorDiscordBridgeSetting = new Setting.BooleanSetting("color_discord_bridge", "chat", false);
            assertNull(DiscordRankChatDecorator.guildRank("RealName"));
            assertNull(DiscordRankChatDecorator.guildInsignia("RealName"));
            var line = ChatManager.guildSenderLine(new ConnectionManager.GuildChatMessage("RealName", null, "hello"), "hello", null);
            assertTrue(line.getString().contains("RealName: hello"));
            assertFragmentColor(line, "hello", 0x55FFFF);
        } finally {
            SeqClient.showDiscordRanksSetting = previousRanks;
            SeqClient.showDiscordChatSetting = previousChat;
            SeqClient.colorDiscordBridgeSetting = previousBridge;
        }
    }

    @Test
    void guildPrefixesPreserveCapturedFontAndStyle() {
        GuildChatMarkers.reset();
        try {
            var style = Style.EMPTY.withFont(new net.minecraft.network.chat.FontDescription.Resource(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("test", "guild")))
                    .withColor(ChatFormatting.AQUA);
            GuildChatMarkers.observe(ComponentTextEditor.flatten(Component.literal("abcd").withStyle(style)), 4);
            GuildChatMarkers.observe(ComponentTextEditor.flatten(Component.literal("x").withStyle(style)), 1);
            assertEquals("abcd ", DiscordRankChatDecorator.guildPrefix(false).getString());
            assertEquals("x", DiscordRankChatDecorator.guildPrefix(true).getString());
            assertEquals(style, ComponentTextEditor.flatten(DiscordRankChatDecorator.guildPrefix(false)).getFirst().style());
        } finally {
            GuildChatMarkers.reset();
        }
    }

    @Test
    void forwardedGuildChatUsesGuildColorAndRealNicknameIdentity() {
        var previous = SeqClient.inGameGuildChatTextColorSetting;
        try {
            SeqClient.inGameGuildChatTextColorSetting = new Setting.ColorSetting("in_game_guild_chat_text_color", "chat", 0xA1B2C3);
            var line = ChatManager.guildSenderLine(new ConnectionManager.GuildChatMessage("RealName", "Nickname", "hello"), "hello", null);
            assertTrue(line.getString().contains("Nickname: hello"));
            assertTrue(WynnPillGlyphs.findPills(line.getString()).isEmpty());
            var identity = ComponentTextEditor.flatten(line).stream().filter(f -> f.text().equals("Nickname")).findFirst().orElseThrow().style();
            assertEquals("RealName", identity.getInsertion());
            assertEquals("RealName's nickname is Nickname",
                    ((HoverEvent.ShowText) identity.getHoverEvent()).value().getString());
            assertFragmentColor(line, "hello", 0xA1B2C3);
            assertFragmentColor(ChatManager.guildContinuationLine("continued"), "continued", 0xA1B2C3);
        } finally {
            SeqClient.inGameGuildChatTextColorSetting = previous;
        }
    }

    @Test
    void guildContinuationSpacingMatchesTheFullPrefixAndPreservesTheMarkerFont() {
        GuildChatMarkers.reset();
        java.util.function.ToIntFunction<Component> width = component -> component.getString().codePoints()
                .map(glyph -> switch (glyph) {
                    case '➜', ' ' -> 4;
                    case '\uF8FE' -> -1;
                    default -> 1;
                }).sum();
        try {
            Component fallbackArrow = DiscordRankChatDecorator.guildPrefix(false);
            var fallbackBar = DiscordRankChatDecorator.alignPrefixToReference(
                    DiscordRankChatDecorator.guildPrefix(true), fallbackArrow, width);
            assertEquals(width.applyAsInt(fallbackArrow), width.applyAsInt(fallbackBar));
            assertEquals("│ " + "\uF8FD".repeat(3), fallbackBar.getString());

            var style = Style.EMPTY.withFont(new net.minecraft.network.chat.FontDescription.Resource(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("test", "guild")))
                    .withColor(ChatFormatting.AQUA);
            GuildChatMarkers.observe(ComponentTextEditor.flatten(Component.literal("abcd").withStyle(style)), 4);
            GuildChatMarkers.observe(ComponentTextEditor.flatten(Component.literal("x").withStyle(style)), 1);
            Component arrow = DiscordRankChatDecorator.guildPrefix(false);
            var bar = DiscordRankChatDecorator.alignPrefixToReference(
                    DiscordRankChatDecorator.guildPrefix(true), arrow, width);
            assertEquals(width.applyAsInt(arrow), width.applyAsInt(bar));
            assertEquals("x" + "\uF8FD".repeat(7), bar.getString());
            var fragments = ComponentTextEditor.flatten(bar);
            assertEquals(style, fragments.getFirst().style());
            assertEquals(new net.minecraft.network.chat.FontDescription.Resource(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("seq", "discord_bridge")),
                    fragments.getLast().style().getFont());
        } finally {
            GuildChatMarkers.reset();
        }
    }

    @Test
    void forwardedGuildChatUsesRankAndBlankNicknameFallsBackToUsername() {
        RankPresentation rank = new RankPresentation(new DiscordRank("rank.sapling", "Sapling", 88), ColorRamp.of(0x4CB4FA));
        var line = ChatManager.guildSenderLine(new ConnectionManager.GuildChatMessage("RealName", " ", "hello"), "hello", rank);
        assertEquals("sapling", WynnPillGlyphs.findPills(line.getString()).getFirst().label());
        assertTrue(line.getString().contains("RealName: hello"));
        assertTrue(ComponentTextEditor.flatten(line).stream().anyMatch(f -> "RealName".equals(f.style().getInsertion())));
    }

    @Test
    void forwardedGuildLineKeepsWrappingPrefixWithoutDiscordSequenceOrRedecoration() {
        var line = ChatManager.guildSenderLine(new ConnectionManager.GuildChatMessage("RealName", null, "hello"), "hello", null);
        DiscordRankChatDecorator.displayGuildLine(line, () -> {
            assertTrue(line == DiscordRankChatDecorator.decorateGuildChat(line));
        });
        assertEquals(DiscordRankChatDecorator.guildPrefix(true).getString(),
                DiscordRankChatDecorator.bridgeContinuationPrefixFor(line).getString());
        var expected = DiscordRankChatDecorator.bridgePrefix().getString();
        DiscordRankChatDecorator.displayUndecorated(Component.literal("neutral"), () -> {}, false);
        assertEquals(expected, DiscordRankChatDecorator.bridgePrefix().getString());
    }

    @Test
    void parseGuildMessageHandlesPacketGlyphsAndNicknameRealNameSplit() {
        Component message = Component.empty()
                .append(Component.literal("󏿼󐀆 "))
                .append(Component.literal("󏿿󏿿󏿿󏿿󏿿󏿿󏿢󐀂 "))
                .append(Component.literal("Emanant Force").withStyle(Style.EMPTY.withInsertion("Purprated")))
                .append(Component.literal(": any raids?"));

        ChatManager.ParsedMessage parsed = ChatManager.parseGuildMessage(message);

        assertNotNull(parsed);
        assertEquals("Purprated", parsed.username());
        assertEquals("Emanant Force", parsed.nickname());
        assertEquals("any raids?", parsed.message());
    }

    @Test
    void parseGuildMessageHandlesWynnPrestigePrefixBeforeSpeaker() {
        Component message = Component.empty()
                .append(Component.literal("󏿼󐀆 "))
                .append(Component.literal("󏿿󏿿󏿿󏿿󏿿󏿿󏿿󏿿󏿿󏿿󏿿󏿄"))
                .append(Component.literal("󐀂 "))
                .append(Component.literal("<1>Commander Lilacs").withStyle(Style.EMPTY.withInsertion("RealLilacs")))
                .append(Component.literal(": tna/wtp 1/4"));

        ChatManager.ParsedMessage parsed = ChatManager.parseGuildMessage(message);

        assertNotNull(parsed);
        assertEquals("RealLilacs", parsed.username());
        assertEquals("Commander Lilacs", parsed.nickname());
        assertEquals("tna/wtp 1/4", parsed.message());
    }

    @Test
    void parseGuildMessageHandlesDirectUsernameWithoutNickname() {
        ChatManager.ParsedMessage parsed = ChatManager.parseGuildMessage(Component.literal(
                "󏿼󐀆 xmattypazox: 3/4 tna"));

        assertNotNull(parsed);
        assertEquals("xmattypazox", parsed.username());
        assertNull(parsed.nickname());
        assertEquals("3/4 tna", parsed.message());
    }

    @Test
    void parseGuildMessageHandlesMultilineContentAndWeirdSpacing() {
        Component message = Component.empty()
                .append(Component.literal("󏿼󐀆 "))
                .append(Component.literal("teslaco").withStyle(Style.EMPTY.withInsertion("a3pki")))
                .append(Component.literal(": tna tna tna 3/\n󏿼󐀆 4 3 out of 4"));

        ChatManager.ParsedMessage parsed = ChatManager.parseGuildMessage(message);

        assertNotNull(parsed);
        assertEquals("a3pki", parsed.username());
        assertEquals("teslaco", parsed.nickname());
        assertEquals("tna tna tna 3/4 3 out of 4", parsed.message());
    }

    @Test
    void parseGuildMessagePreservesWrappedWynnBuilderUrl() {
        Component message = Component.empty()
                .append(Component.literal("󏿼󐀆 "))
                .append(Component.literal("Purprated").withStyle(Style.EMPTY.withInsertion("Purprated")))
                .append(Component.literal(
                        ": https://wynnbuilder.github.io/builder/#CW0R3\n"
                                + "󏿼󐀆 FvSpicp9HS4xaJbHgCI15MFoupRwBzpB+rKNFq0"));

        ChatManager.ParsedMessage parsed = ChatManager.parseGuildMessage(message);

        assertNotNull(parsed);
        assertEquals(
                "https://wynnbuilder.github.io/builder/#CW0R3"
                        + "FvSpicp9HS4xaJbHgCI15MFoupRwBzpB+rKNFq0",
                parsed.message());
    }

    @Test
    void parseGuildMessageHandlesHoverBasedRealNameFallback() {
        Style style = Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(
                Component.literal("teslaco's real name is a3pki")));
        Component message = Component.empty()
                .append(Component.literal("󏿼󐀆 "))
                .append(Component.literal("teslaco").withStyle(style))
                .append(Component.literal(": meow"));

        ChatManager.ParsedMessage parsed = ChatManager.parseGuildMessage(message);

        assertNotNull(parsed);
        assertEquals("a3pki", parsed.username());
        assertEquals("teslaco", parsed.nickname());
        assertEquals("meow", parsed.message());
    }

    @Test
    void parseGuildMessageHandlesUnrevealedSpacedNicknameWithRealUsernameHover() {
        Style style = Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(
                Component.literal("§fI Burger§7's real username is §fpat_crafter07")));
        Component message = Component.empty()
                .append(Component.literal("󏿼󐀆 "))
                .append(Component.literal("I Burger").withStyle(style))
                .append(Component.literal(": hello"));

        ChatManager.ParsedMessage parsed = ChatManager.parseGuildMessage(message);

        assertNotNull(parsed);
        assertEquals("pat_crafter07", parsed.username());
        assertEquals("I Burger", parsed.nickname());
        assertEquals("hello", parsed.message());
    }

    @Test
    void parseGuildMessageHandlesUnrevealedNicknameWithReversedWynntilsHover() {
        Style style = Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(
                Component.literal("§fnunohover§7's nickname is §fSkybound nunohover")));
        Component message = Component.empty()
                .append(Component.literal("󏿼󐀆 "))
                .append(Component.literal("Skybound nunohover").withStyle(style))
                .append(Component.literal(": hello"));

        ChatManager.ParsedMessage parsed = ChatManager.parseGuildMessage(message);

        assertNotNull(parsed);
        assertEquals("nunohover", parsed.username());
        assertEquals("Skybound nunohover", parsed.nickname());
        assertEquals("hello", parsed.message());
    }

    @Test
    void observesNicknameMappingFromNonChatPacketMetadata() {
        Component message = Component.empty()
                .append(Component.literal("DrBavaro").withStyle(Style.EMPTY.withInsertion("xmattypazox")))
                .append(Component.literal(" has given you 20% resistance."));

        ChatManager.observeNicknameMappings(message);

        assertEquals("xmattypazox", NicknameResolverCache.resolveUsername("DrBavaro"));
    }

    @Test
    void observesInlineNicknameMappingFromNonChatMessages() {
        ChatManager.observeNicknameMappings(
                Component.literal("DrBavaro(xmattypazox) has given you 20% resistance."));

        assertEquals("xmattypazox", NicknameResolverCache.resolveUsername("DrBavaro"));
    }

    @Test
    void parseGuildMessageIgnoresGuildSystemMessagesWithoutSpeakerColon() {
        assertNull(ChatManager.parseGuildMessage(Component.literal(
                "󏿼󏿿󏿾 Territory Gelibord is producing more resources than it\n󏿼󐀆 can store!")));
        assertNull(ChatManager.parseGuildMessage(Component.literal(
                "󏿼󐀆 Purprated rewarded 1024 Emeralds to cinfrascitizen")));
    }

    @Test
    void parseAllianceUpdateHandlesFormedSystemMessage() {
        ChatManager.ParsedAllianceUpdate parsed = ChatManager.parseAllianceUpdate(Component.literal(
                "󏿼󏿿󏿾 Sequoia formed an alliance with Silk Road"));

        assertNotNull(parsed);
        assertEquals("formed", parsed.action());
        assertEquals("Silk Road", parsed.guildName());
    }

    @Test
    void parseAllianceUpdateHandlesOtherGuildRevokedHomeGuild() {
        ChatManager.ParsedAllianceUpdate parsed = ChatManager.parseAllianceUpdate(Component.literal(
                "󏿼󐀆 Anime Lovers revoked the alliance with Sequoia"));

        assertNotNull(parsed);
        assertEquals("revoked", parsed.action());
        assertEquals("Anime Lovers", parsed.guildName());
    }

    @Test
    void parseAllianceUpdateHandlesHomeGuildPlayerRevokedOtherGuild() {
        ChatManager.ParsedAllianceUpdate parsed = ChatManager.parseAllianceUpdate(Component.literal(
                "󏿼󏿿󏿾 GaztheCat revoked the alliance with Chiefs Of Corkus"));

        assertNotNull(parsed);
        assertEquals("revoked", parsed.action());
        assertEquals("Chiefs Of Corkus", parsed.guildName());
    }

    @Test
    void parseAllianceUpdateIgnoresUnrelatedFormedAllianceSystemMessage() {
        assertNull(ChatManager.parseAllianceUpdate(Component.literal(
                "󏿼󏿿󏿾 Tannslee formed an alliance with Radiant Roses")));
    }

    @Test
    void parseAllianceUpdateRejectsPlayerAuthoredChat() {
        assertNull(ChatManager.parseAllianceUpdate(Component.literal(
                "Frieren: Sequoia formed an alliance with Silk Road")));
        assertNull(ChatManager.parseAllianceUpdate(Component.literal(
                "󏿼󐀆 Frieren: Sequoia formed an alliance with Silk Road")));
    }

    @Test
    void parsesActorFirstGuildInvite() {
        ChatManager.ParsedGuildMembershipEvent parsed = ChatManager.parseGuildMembershipEvent(
                Component.literal("󏿼󐀆 GaztheCat invited NewMember to the guild."), "Observer");

        assertNotNull(parsed);
        assertEquals("invited", parsed.action());
        assertEquals("GaztheCat", parsed.actor());
        assertEquals("NewMember", parsed.target());
    }

    @Test
    void ignoresGuildMemberKicksAndRemovals() {
        assertNull(ChatManager.parseGuildMembershipEvent(
                Component.literal("NewMember has been kicked from the guild by GaztheCat."), "Observer"));
        assertNull(ChatManager.parseGuildMembershipEvent(
                Component.literal("GaztheCat removed NewMember from the guild."), "Observer"));
    }

    @Test
    void resolvesGuildMembershipNicknameFromPacketMetadata() {
        Component message = Component.empty()
                .append(Component.literal("Commander Lilacs").withStyle(Style.EMPTY.withInsertion("RealLilacs")))
                .append(Component.literal(" invited NewMember to your guild!"));

        ChatManager.ParsedGuildMembershipEvent parsed =
                ChatManager.parseGuildMembershipEvent(message, "Observer");

        assertNotNull(parsed);
        assertEquals("RealLilacs", parsed.actor());
        assertEquals("NewMember", parsed.target());
    }

    @Test
    void resolvesYouInGuildCommandConfirmationToLocalPlayer() {
        ChatManager.ParsedGuildMembershipEvent parsed = ChatManager.parseGuildMembershipEvent(
                Component.literal("[Guild] You have successfully uninvited NewMember from the guild"),
                "GaztheCat");

        assertNotNull(parsed);
        assertEquals("uninvited", parsed.action());
        assertEquals("GaztheCat", parsed.actor());
        assertEquals("NewMember", parsed.target());
    }

    @Test
    void guildMembershipParserRejectsPlayerAuthoredChatAndMissingLocalActor() {
        assertNull(ChatManager.parseGuildMembershipEvent(
                Component.literal("Frieren: GaztheCat invited NewMember to the guild"), "Observer"));
        assertNull(ChatManager.parseGuildMembershipEvent(
                Component.literal("You invited NewMember to the guild"), null));
    }

    @Test
    void detectsUpdatedWynncraftWelcomeBanner() {
        Component message = Component.literal(
                "\n󐁙Welcome to Wynncraft!\n󐀻play.wynncraft.com -/- wynncraft.com\n\n󐁄WYNNCRAFT: FRUMA EXPANSION\n󐂁OUT NOW!\n󐂚\n󐀲Discover Fruma: wynncraft.com/fruma");

        assertTrue(ChatManager.isWynncraftWelcomeMessage(message));
    }

    @Test
    void doesNotTreatOrdinaryChatMentionAsWelcomeBanner() {
        Component message = Component.literal("Frieren: Welcome to Wynncraft! meet me on EU7");

        assertFalse(ChatManager.isWynncraftWelcomeMessage(message));
    }

    @Test
    void dropsGuildChatWhenWynntilsMembershipIsUnavailable() {
        WynntilsGuildRankAccess.GuildMembership membership =
                new WynntilsGuildRankAccess.GuildMembership(false, false, null);

        assertFalse(ChatManager.shouldRelayForGuild(membership));
    }

    @Test
    void dropsGuildChatWhenWynntilsMembershipIsMissing() {
        assertFalse(ChatManager.shouldRelayForGuild(null));
    }

    @Test
    void relaysGuildChatForExpectedWynntilsGuild() {
        WynntilsGuildRankAccess.GuildMembership membership =
                new WynntilsGuildRankAccess.GuildMembership(true, true, "Sequoia");

        assertTrue(ChatManager.shouldRelayForGuild(membership));
    }

    @Test
    void dropsGuildChatForOtherKnownWynntilsGuild() {
        WynntilsGuildRankAccess.GuildMembership membership =
                new WynntilsGuildRankAccess.GuildMembership(true, false, "Other Guild");

        assertFalse(ChatManager.shouldRelayForGuild(membership));
    }

    @Test
    void detectsGuildChatWhenOnlyLeadingFragmentIsGuildColored() {
        Component message = Component.empty()
                .append(Component.literal("󏿼󐀆 ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("ilyhug: what").withStyle(ChatFormatting.DARK_AQUA));

        assertTrue(ChatManager.hasLeadingGuildChatColor(message));
    }

    @Test
    void rejectsInfoChatWithLaterGuildColoredFragment() {
        Component message = Component.empty()
                .append(Component.literal("󏿼󏿿󏿾 Party Finder: ").withStyle(ChatFormatting.DARK_PURPLE))
                .append(Component.literal("The Nameless Anomaly").withStyle(ChatFormatting.AQUA));

        assertFalse(ChatManager.hasLeadingGuildChatColor(message));
    }

    @Test
    void splitsABridgedMessageIntoOneLinePerNewline() {
        // Minecraft draws an embedded break flush against the left margin, which loses
        // the marker column, so each line is displayed on its own instead.
        assertEquals(
                List.of("multi", "line", "message?"),
                ChatManager.splitMessageLines("multi\nline\nmessage?"));
        assertEquals(List.of("a", "b"), ChatManager.splitMessageLines("a\r\nb"));
    }

    @Test
    void dropsTrailingBlankLinesButKeepsInnerOnes() {
        // A rail drawn under nothing looks like a rendering fault; a deliberate blank
        // line inside a message does not.
        assertEquals(List.of("only"), ChatManager.splitMessageLines("only\n\n"));
        assertEquals(List.of("top", "", "bottom"), ChatManager.splitMessageLines("top\n\nbottom"));
        assertEquals(List.of(""), ChatManager.splitMessageLines(""));
        assertEquals(List.of(""), ChatManager.splitMessageLines(null));
    }

    @Test
    void uncolouredBridgeMessageUsesLegacySequoiaPillAndDefaultDiscordTextColor() {
        MutableComponent line = ChatManager.bridgeSenderLine(
                new ConnectionManager.DiscordChatMessage("MrHmar", "hello", "215820027700576258"),
                "hello",
                null);

        assertEquals("sequoia", WynnPillGlyphs.findPills(line.getString()).getFirst().label());
        assertFragmentColor(line, "MrHmar", ChatFormatting.WHITE);
        assertFragmentColor(line, ": ", ChatFormatting.GRAY);
        assertFragmentColor(line, "hello", 0x55FFFF);
    }

    @Test
    void colouredBridgeSenderUsesTheCompleteAnimatedGradientRamp() {
        RankPresentation gradient = new RankPresentation(
                new DiscordRank("rank.yggdrasil", "Yggdrasil", 120),
                ColorRamp.of(List.of(0x123456, 0xFFFFFF)));

        MutableComponent line = ChatManager.bridgeSenderLine(
                new ConnectionManager.DiscordChatMessage("Name", "hello", "215820027700576258"),
                "hello",
                gradient);
        List<ComponentTextEditor.Fragment> name = ComponentTextEditor.flatten(line).stream()
                .filter(fragment -> "Name".equals(fragment.style().getInsertion()))
                .toList();

        assertEquals("Name", name.stream().map(ComponentTextEditor.Fragment::text).reduce("", String::concat));
        assertEquals(0x123456, name.getFirst().style().getColor().getValue());
        assertEquals(0xFFFFFF, name.getLast().style().getColor().getValue());
    }

    @Test
    void rankedBridgeSenderOmitsTheRoleLabelAlreadyShownInThePill() {
        RankPresentation treant = new RankPresentation(
                new DiscordRank("rank.treant", "Treant", 80), ColorRamp.of(0x55AA55));

        MutableComponent line = ChatManager.bridgeSenderLine(
                new ConnectionManager.DiscordChatMessage("Treant OwORawr", "wharffff"),
                "Replying to a3pki/rice field worker: wharffff",
                treant);

        assertTrue(line.getString().endsWith(" OwORawr: Replying to a3pki/rice field worker: wharffff"));
        assertFalse(line.getString().contains(" Treant OwORawr:"));
        assertEquals(
                "OwORawr",
                ComponentTextEditor.flatten(line).stream()
                        .filter(fragment -> "OwORawr".equals(fragment.text()))
                        .findFirst()
                        .orElseThrow()
                        .style()
                        .getInsertion());
    }

    @Test
    void bridgeDisplayNameOnlyRemovesACompleteMatchingRankPrefix() {
        RankPresentation treant = new RankPresentation(
                new DiscordRank("rank.treant", "Treant", 80), ColorRamp.of(0x55AA55));

        assertEquals("OwORawr", ChatManager.bridgeDisplayName("treant OwORawr", treant));
        assertEquals("Treantor", ChatManager.bridgeDisplayName("Treantor", treant));
        assertEquals("Treant", ChatManager.bridgeDisplayName("Treant", treant));
    }

    @Test
    void rankedBridgeUsernameReturnsToWhiteWhenRoleColoringIsDisabled() {
        Setting.BooleanSetting previous = SeqClient.colorUsernamesSetting;
        try {
            SeqClient.colorUsernamesSetting = new Setting.BooleanSetting("color_usernames", "chat", false);
            RankPresentation rank = new RankPresentation(
                    new DiscordRank("rank.sapling", "Sapling", 88), ColorRamp.of(0x4CB4FA));

            MutableComponent line = ChatManager.bridgeSenderLine(
                    new ConnectionManager.DiscordChatMessage("Name", "hello", "215820027700576258"),
                    "hello",
                    rank);
            TextColor stored = ComponentTextEditor.flatten(line).stream()
                    .filter(fragment -> "Name".equals(fragment.text()))
                    .findFirst()
                    .orElseThrow()
                    .style()
                    .getColor();

            assertEquals(0x4CB4FA, stored.getValue());
            assertEquals(ChatFormatting.WHITE.getColor(), RankGradientAnimation.animate(stored).getValue());
        } finally {
            SeqClient.colorUsernamesSetting = previous;
        }
    }

    @Test
    void rankedBridgePillReturnsToConfiguredDiscordChatColor() {
        Setting.BooleanSetting previousPills = SeqClient.colorRankPillsSetting;
        Setting.ColorSetting previousTextColor = SeqClient.discordChatTextColorSetting;
        try {
            SeqClient.colorRankPillsSetting = new Setting.BooleanSetting("color_rank_pills", "chat", false);
            SeqClient.discordChatTextColorSetting =
                    new Setting.ColorSetting("discord_chat_text_color", "chat", 0xA1B2C3);
            RankPresentation rank = new RankPresentation(
                    new DiscordRank("rank.sapling", "Sapling", 88), ColorRamp.of(0x4CB4FA));

            MutableComponent line = ChatManager.bridgeSenderLine(
                    new ConnectionManager.DiscordChatMessage("Name", "hello", "215820027700576258"),
                    "hello",
                    rank);
            TextColor stored = ComponentTextEditor.flatten(line).stream()
                    .filter(fragment -> fragment.text().indexOf(WynnPillGlyphs.BACKGROUND) >= 0)
                    .findFirst()
                    .orElseThrow()
                    .style()
                    .getColor();

            assertEquals(0x4CB4FA, stored.getValue());
            assertEquals(0xA1B2C3, RankGradientAnimation.animate(stored).getValue());
        } finally {
            SeqClient.colorRankPillsSetting = previousPills;
            SeqClient.discordChatTextColorSetting = previousTextColor;
        }
    }

    @Test
    void uncolouredBridgeContinuationKeepsLegacyPillAndDefaultDiscordTextColor() {
        MutableComponent line = ChatManager.bridgeContinuationLine("continued", false);

        assertEquals("sequoia", WynnPillGlyphs.findPills(line.getString()).getFirst().label());
        assertFragmentColor(line, "continued", 0x55FFFF);
    }

    @Test
    void bridgeMessagesUseConfiguredDiscordTextColor() {
        Setting.ColorSetting previous = SeqClient.discordChatTextColorSetting;
        try {
            SeqClient.discordChatTextColorSetting =
                    new Setting.ColorSetting("discord_chat_text_color", "chat", 0xA1B2C3);

            MutableComponent sender = ChatManager.bridgeSenderLine(
                    new ConnectionManager.DiscordChatMessage("MrHmar", "hello", "215820027700576258"),
                    "hello",
                    null);
            MutableComponent continuation = ChatManager.bridgeContinuationLine("continued", false);

            assertFragmentColor(sender, "hello", 0xA1B2C3);
            assertFragmentColor(continuation, "continued", 0xA1B2C3);
        } finally {
            SeqClient.discordChatTextColorSetting = previous;
        }
    }

    private static void assertFragmentColor(Component component, String text, ChatFormatting expected) {
        Style style = ComponentTextEditor.flatten(component).stream()
                .filter(fragment -> fragment.text().equals(text))
                .findFirst()
                .orElseThrow()
                .style();
        assertNotNull(style.getColor());
        assertEquals(expected.getColor(), style.getColor().getValue());
    }

    private static void assertFragmentColor(Component component, String text, int expectedRgb) {
        Style style = ComponentTextEditor.flatten(component).stream()
                .filter(fragment -> fragment.text().equals(text))
                .findFirst()
                .orElseThrow()
                .style();
        assertNotNull(style.getColor());
        assertEquals(expectedRgb, style.getColor().getValue());
    }
}
