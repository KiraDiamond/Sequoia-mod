package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.Test;

class GuildRankEventParserTest {
    @Test
    void parsesActualMultilineNoChangeMessageWithoutInventingUsername() {
        var event = GuildRankEventParser.parse(Component.literal(
                "hardcoremaxxer has set NotReyz guild rank from \n Strategist to Strategist"), "ActualAccount");
        assertNotNull(event);
        assertEquals("hardcoremaxxer", event.actor().displayName());
        assertNull(event.actor().username());
        assertNull(event.target().username());
        assertEquals("unresolved", event.actor().source());
        assertEquals("Strategist", event.oldRank());
        assertEquals("Strategist", event.newRank());
    }

    @Test
    void parsesLiveDecoratedPromotionAndDemotionPackets() {
        String decoration = "󏿼󐀆";
        var promotion = GuildRankEventParser.parse(Component.literal(
                decoration + " harcoremaxxer has set NotReyz guild rank from Recruiter\n"
                        + decoration + " to Captain"), "reyzhia");
        assertNotNull(promotion);
        assertEquals("Recruiter", promotion.oldRank());
        assertEquals("Captain", promotion.newRank());
        assertEquals("harcoremaxxer", promotion.actor().displayName());
        assertNull(promotion.actor().username());

        var demotion = GuildRankEventParser.parse(Component.literal(
                decoration + " harcoremaxxer has set NotReyz guild rank from \n"
                        + decoration + " Strategist to Captain"), "reyzhia");
        assertNotNull(demotion);
        assertEquals("Strategist", demotion.oldRank());
        assertEquals("Captain", demotion.newRank());
        assertNull(demotion.actor().username());
    }

    @Test
    void resolvesActorAndTargetIndependentlyFromComponentMetadata() {
        Component message = Component.empty()
                .append(Component.literal("hardcoremaxxer").withStyle(Style.EMPTY.withInsertion("ActualAccount")))
                .append(Component.literal(" has set "))
                .append(Component.literal("Target Nickname").withStyle(Style.EMPTY.withInsertion("NotReyz")))
                .append(Component.literal(" guild rank from Strategist to Chief"));
        var event = GuildRankEventParser.parse(message, "Observer");
        assertEquals("actualaccount", event.actor().username());
        assertEquals("notreyz", event.target().username());
        assertEquals("component", event.actor().source());
        assertEquals("Chief", event.newRank());
    }

    @Test
    void rejectsOrdinaryChatAndUnknownRanks() {
        assertNull(GuildRankEventParser.parse(Component.literal(
                "Troll: hardcoremaxxer has set NotReyz guild rank from Recruit to Chief"), "Observer"));
        assertNull(GuildRankEventParser.parse(Component.literal(
                "hardcoremaxxer has set NotReyz guild rank from Admin to Chief"), "Observer"));
        assertNull(GuildRankEventParser.parse(Component.literal("/guild promote NotReyz"), "Observer"));
    }

    @Test
    void acceptsPrefixAndDemotionAndMapsYouOnlyToLocalAccount() {
        var event = GuildRankEventParser.parse(Component.literal(
                "[Guild] You has set NotReyz guild rank from Chief to Recruit."), "ActualAccount");
        assertEquals("ActualAccount", event.actor().username());
        assertEquals("local_player", event.actor().source());
        assertEquals("Recruit", event.newRank());
    }

    @Test
    void doesNotUseNicknameCachePrefixAsEvidence() {
        IdentityTestSupport.remember("hardcoremaxxer extra", "UnrelatedPlayer");
        var event = GuildRankEventParser.parse(Component.literal(
                "hardcoremaxxer has set NotReyz guild rank from Recruit to Chief"), "Observer");
        assertNull(event.actor().username());
    }

    @Test
    void resolvesHoverMetadataButRejectsConflictingInsertion() {
        Style style = Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(
                Component.literal("Real username: ActualAccount")));
        Component message = Component.empty()
                .append(Component.literal("Nickname").withStyle(style))
                .append(Component.literal(" has set Target guild rank from Recruit to Captain"));
        var event = GuildRankEventParser.parse(message, "Observer");
        assertNotNull(event);
        assertEquals("actualaccount", event.actor().username());
        assertNull(event.target().username());

        Component conflict = Component.empty()
                .append(Component.literal("Nickname").withStyle(style.withInsertion("OtherAccount")))
                .append(Component.literal(" has set Target guild rank from Recruit to Captain"));
        assertNull(GuildRankEventParser.parse(conflict, "Observer").actor().username());
    }

    @Test
    void identicalDisplaysInDifferentRolesKeepTheirOwnMetadata() {
        Component message = Component.empty()
                .append(Component.literal("hardcoremaxxer").withStyle(Style.EMPTY.withInsertion("FirstAccount")))
                .append(Component.literal(" has set "))
                .append(Component.literal("hardcoremaxxer").withStyle(Style.EMPTY.withInsertion("SecondAccount")))
                .append(Component.literal(" guild rank from Recruit to Chief"));
        var event = GuildRankEventParser.parse(message, "Observer");
        assertEquals("firstaccount", event.actor().username());
        assertEquals("secondaccount", event.target().username());
    }
}
