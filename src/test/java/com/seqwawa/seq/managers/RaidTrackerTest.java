package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RaidTrackerTest {

    @AfterEach
    void resetRaidSnapshot() {
        RaidPartySnapshotTracker.reset();
    }

    @Test
    void changingAnotherMembersDisplayCannotChangeAnIdentity() {
        for (String third : List.of("Carl", "Carl Jr")) {
            Component message = Component.empty()
                    .append(Component.literal("Alice").withStyle(Style.EMPTY.withInsertion("Bob")))
                    .append(", Other, and ").append(third)
                    .append(" finished The Canyon Colossus and claimed 2x Aspects, 2048x Emeralds, and +10367m Guild Experience");
            assertEquals("Bob", RaidTracker.parseRaidCompletion(message).partyMembers().getFirst());
        }
    }

    @Test
    void parseRaidCompletionHandlesSplitNamesAndRewards() {
        Component message = Component.empty()
                .append(Component.literal("󏿼󐀆 "))
                .append(Component.literal("bubblebouncy").withStyle(Style.EMPTY.withInsertion("Visroul")))
                .append(Component.literal(", xmattypazox, "))
                .append(Component.literal("death by choking").withStyle(Style.EMPTY.withInsertion("a3pki")))
                .append(Component.literal(", and divvy\n󏿼󐀆 "))
                .append(Component.literal("lunne").withStyle(Style.EMPTY.withInsertion("blousy")))
                .append(Component.literal(" finished The Nameless Anomaly and claimed 2x Aspects\n󏿼󐀆 , 2048x Emeralds, and +10367m Guild Experience"));

        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(message);

        assertNotNull(parsed);
        assertEquals(List.of("Visroul", "xmattypazox", "a3pki", "blousy"), parsed.partyMembers());
        assertEquals("The Nameless Anomaly", parsed.raidName());
        assertEquals(2, parsed.aspects());
        assertEquals(2048, parsed.emeralds());
        assertEquals(10.367, parsed.guildExp(), 0.000001);
        assertEquals(0, parsed.seasonalRating());
    }

    @Test
    void parseRaidCompletionHandlesSplitRaidNameAcrossLines() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󏿿󏿾 Tannslee, JeongSooMin, wisedrag, and D4MIT finished Nest\n"
                        + "󏿼󐀆 of the Grootslangs and claimed 2x Aspects, 2048x Emeralds\n"
                        + "󏿼󐀆 , and +10367m Guild Experience"));

        assertNotNull(parsed);
        assertEquals(List.of("Tannslee", "JeongSooMin", "wisedrag", "D4MIT"), parsed.partyMembers());
        assertEquals("Nest of the Grootslangs", parsed.raidName());
        assertEquals(2048, parsed.emeralds());
    }

    @Test
    void parseRaidCompletionHandlesSplitBeforeFinishedAndBeforeGuildExp() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󏿿󏿾 space527, krackeryuh, ArcLeRetour, and MrRickroll\n"
                        + "󏿼󐀆 finished The Nameless Anomaly and claimed 2x Aspects, \n"
                        + "󏿼󐀆 2048x Emeralds, and +10367m Guild Experience"));

        assertNotNull(parsed);
        assertEquals(List.of("space527", "krackeryuh", "ArcLeRetour", "MrRickroll"), parsed.partyMembers());
        assertEquals("The Nameless Anomaly", parsed.raidName());
        assertEquals(2, parsed.aspects());
    }

    @Test
    void parseRaidCompletionHandlesOrphionNameSplitAcrossLine() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󏿿󏿾 kittycat317, Glacade, a3pki, and 5up3rZ finished Orphion's\n"
                        + "󏿼󐀆 Nexus of Light and claimed 2x Aspects, 2048x Emeralds, and \n"
                        + "󏿼󐀆 +10367m Guild Experience"));

        assertNotNull(parsed);
        assertEquals("Orphion's Nexus of Light", parsed.raidName());
        assertEquals(List.of("kittycat317", "Glacade", "a3pki", "5up3rZ"), parsed.partyMembers());
    }

    @Test
    void parseRaidCompletionHandlesSeasonalRatingClause() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󐀆 AAA, BBB, CCC, and DDD finished The Nameless Anomaly and claimed 2x Aspects,\n"
                        + "󏿼󐀆 2048x Emeralds, and +10367m Guild Experience, and +410 Seasonal Rating"));

        assertNotNull(parsed);
        assertEquals(List.of("AAA", "BBB", "CCC", "DDD"), parsed.partyMembers());
        assertEquals(410, parsed.seasonalRating());
    }

    @Test
    void parseRaidCompletionHandlesWrongOrderAspectsAndEmeralds() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󐀆 AAA, BBB, CCC, and DDD finished The Nameless Anomaly and claimed 2048x Emeralds,\n"
                        + "󏿼󐀆 2x Aspects, and +10367m Guild Experience, and +410 Seasonal Rating"));

        assertNotNull(parsed);
        assertEquals(List.of("AAA", "BBB", "CCC", "DDD"), parsed.partyMembers());
        assertEquals(2048, parsed.emeralds());
        assertEquals(2, parsed.aspects());
    }

    @Test
    void parseRaidCompletionHandlesAllyGuildRaids() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󐀆 AAA, and BBB finished The Nameless Anomaly and claimed 2048x Emeralds,\n"
                        + "󏿼󐀆 2x Aspects, and +5183m Guild Experience, and +220 Seasonal Rating"));

        assertNotNull(parsed);
        assertEquals(List.of("AAA", "BBB"), parsed.partyMembers());
        assertEquals(5.183, parsed.guildExp());
    }

    @Test
    void parseRaidCompletionHandlesAllyNicknamesWithParenthesizedUsernames() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󏿿󏿾 TranSilver§c(owoSilver)§f, and bwoc§c(Dwoc)§f finished "
                        + "Orphion's Nexus of Light and claimed 2048x Emeralds, 2x Aspects, "
                        + "+5183m Guild Experience, and +220 Seasonal Rating"));

        assertNotNull(parsed);
        assertEquals(List.of("owoSilver", "Dwoc"), parsed.partyMembers());
        assertEquals("Orphion's Nexus of Light", parsed.raidName());
        assertEquals(2048, parsed.emeralds());
        assertEquals(2, parsed.aspects());
        assertEquals(5.183, parsed.guildExp());
        assertEquals(220, parsed.seasonalRating());
    }

    @Test
    void parseRaidCompletionDoesNotSplitAndInsideNickname() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󏿿󏿾 fire and ice§c(ActualOne)§f, and AllyTwo finished "
                        + "The Nameless Anomaly and claimed 2048x Emeralds, 2x Aspects, "
                        + "+5183m Guild Experience"));

        assertNotNull(parsed);
        assertEquals(List.of("ActualOne", "AllyTwo"), parsed.partyMembers());
    }

    @Test
    void parseRaidCompletionUsesMetadataAcrossLegacyFormattingCodes() {
        Component message = Component.empty()
                .append(Component.literal("death§c by§r choking")
                        .withStyle(Style.EMPTY.withInsertion("ActualOne")))
                .append(Component.literal(", and AllyTwo finished The Nameless Anomaly and claimed "
                        + "2048x Emeralds, 2x Aspects, +5183m Guild Experience"));

        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(message);

        assertNotNull(parsed);
        assertEquals(List.of("ActualOne", "AllyTwo"), parsed.partyMembers());
    }

    @Test
    void parseRaidCompletionAllowsAnnihilationSizedParties() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "One, Two, Three, Four, Five, Six, Seven, Eight, Nine, and Ten finished "
                        + "Prelude to Annihilation and claimed 2x Aspects, 2048x Emeralds, "
                        + "+10367m Guild Experience"));

        assertNotNull(parsed);
        assertEquals(
                List.of("One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten"),
                parsed.partyMembers());
        assertEquals(10, RaidTracker.maximumPartySize(parsed.raidName()));
    }

    @Test
    void parseRaidCompletionHandlesAspectlessRewardClause() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󐀆 AAA finished The Nameless Anomaly and claimed 2048x Emeralds,\n"
                        + "󏿼󐀆 and +5183m Guild Experience, and +220 Seasonal Rating"));

        assertNotNull(parsed);
        assertEquals(List.of("AAA"), parsed.partyMembers());
        assertEquals("The Nameless Anomaly", parsed.raidName());
        assertEquals(0, parsed.aspects());
        assertEquals(2048, parsed.emeralds());
        assertEquals(5.183, parsed.guildExp());
        assertEquals(220, parsed.seasonalRating());
    }

    @Test
    void parseRaidCompletionHandlesEmeraldlessRewardClause() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󐀆 AAA finished The Nameless Anomaly and claimed 2x Aspects,\n"
                        + "󏿼󐀆 and +5183m Guild Experience, and +220 Seasonal Rating"));

        assertNotNull(parsed);
        assertEquals(List.of("AAA"), parsed.partyMembers());
        assertEquals("The Nameless Anomaly", parsed.raidName());
        assertEquals(2, parsed.aspects());
        assertEquals(0, parsed.emeralds());
        assertEquals(5.183, parsed.guildExp());
        assertEquals(220, parsed.seasonalRating());
    }

    @Test
    void parseRaidCompletionHandlesOnlyGuildExperienceRewardClause() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󐀆 AAA finished The Nameless Anomaly and claimed +5183m Guild Experience"));

        assertNotNull(parsed);
        assertEquals(List.of("AAA"), parsed.partyMembers());
        assertEquals("The Nameless Anomaly", parsed.raidName());
        assertEquals(0, parsed.aspects());
        assertEquals(0, parsed.emeralds());
        assertEquals(5.183, parsed.guildExp());
        assertEquals(0, parsed.seasonalRating());
    }

    @Test
    void parseRaidCompletionHandlesLiteralNoRewardQualifiers() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󐀆 AAA finished The Nameless Anomaly and claimed no Emeralds,\n"
                        + "󏿼󐀆 no Aspects, and +5183m Guild Experience"));

        assertNotNull(parsed);
        assertEquals(List.of("AAA"), parsed.partyMembers());
        assertEquals("The Nameless Anomaly", parsed.raidName());
        assertEquals(0, parsed.aspects());
        assertEquals(0, parsed.emeralds());
        assertEquals(5.183, parsed.guildExp());
        assertEquals(0, parsed.seasonalRating());
    }

    @Test
    void parseRaidCompletionHandlesSingularRewardQualifier() {
        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󐀆 AAA finished The Nameless Anomaly and claimed 1x Aspect,\n"
                        + "󏿼󐀆 and +5183m Guild Experience"));

        assertNotNull(parsed);
        assertEquals(List.of("AAA"), parsed.partyMembers());
        assertEquals("The Nameless Anomaly", parsed.raidName());
        assertEquals(1, parsed.aspects());
        assertEquals(0, parsed.emeralds());
        assertEquals(5.183, parsed.guildExp());
        assertEquals(0, parsed.seasonalRating());
    }

    @Test
    void parseRaidCompletionUsesNameOwnedInsertionConsistently() {
        Component message = Component.empty()
                .append(Component.literal("Tannslee").withStyle(Style.EMPTY.withInsertion("eep")))
                .append(Component.literal(", 99922, wisedrag, and nessabarrett finished The Canyon Colossus and claimed "
                        + "2x Aspects, 2048x Emeralds, and +10367m Guild Experience"));

        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(message);

        assertNotNull(parsed);
        assertEquals(List.of("eep", "99922", "wisedrag", "nessabarrett"), parsed.partyMembers());
        assertEquals("The Canyon Colossus", parsed.raidName());
        assertEquals(10.367, parsed.guildExp(), 0.000001);
    }

    @Test
    void parseRaidCompletionPrefersHoverRealNamesOverUsernameLikeDisplayedNicknames() {
        Style nickStyle = Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(
                Component.literal("Tannslee's real name is eep")));
        Component message = Component.empty()
                .append(Component.literal("Tannslee").withStyle(nickStyle))
                .append(Component.literal(", 99922, wisedrag, and nessabarrett finished The Canyon Colossus and claimed "
                        + "2x Aspects, 2048x Emeralds, and +10367m Guild Experience"));

        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(message);

        assertNotNull(parsed);
        assertEquals(List.of("eep", "99922", "wisedrag", "nessabarrett"), parsed.partyMembers());
        assertEquals("The Canyon Colossus", parsed.raidName());
    }

    @Test
    void parseRaidCompletionPrefersHoverRealNameWhenInsertionContainsNickname() {
        Style nickStyle = Style.EMPTY
                .withInsertion("eep")
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("eep's real name is wisedrag")));
        Component message = Component.empty()
                .append(Component.literal("eep").withStyle(nickStyle))
                .append(Component.literal(", 99922, Tannslee, and nessabarrett finished The Canyon Colossus and claimed "
                        + "2x Aspects, 2048x Emeralds, and +10367m Guild Experience"));

        RaidTracker.ParsedRaidCompletion parsed = RaidTracker.parseRaidCompletion(message);

        assertNotNull(parsed);
        assertEquals(List.of("wisedrag", "99922", "Tannslee", "nessabarrett"), parsed.partyMembers());
        assertEquals("The Canyon Colossus", parsed.raidName());
    }

    @Test
    void parseRaidCompletionIgnoresNonRaidGuildMessages() {
        assertNull(RaidTracker.parseRaidCompletion(Component.literal("󏿼󐀆 xmattypazox: 3/4 tna")));
        assertNull(RaidTracker.parseRaidCompletion(Component.literal("󏿼󐀆 Purprated deposited 1x MR dagger [100%] to the Guild Bank (Everyone)")));
    }

    @Test
    void parseRaidCompletionIgnoresForwardedRaidCompletionChatMessages() {
        assertNull(RaidTracker.parseRaidCompletion(Component.literal(
                "󏿼󏿿󏿾 Orihme: Tannslee, melodzozina, wisedrag, and D4MIT finished The Wartorn Palace "
                        + "and claimed 2x Aspects, 2048x Emeralds, +10367m Guild Experience, and +440 Seasonal Rating")));
    }

    @Test
    void unrelatedGuildCompletionDoesNotConsumeActiveLocalRaidSnapshot() {
        RaidPartySnapshotTracker.setStateForTest(activeRaidState());
        RaidTracker.ParsedRaidCompletion remoteCompletion = completion(
                List.of("MrHmar", "Teslanator", "LoubiOP"), 3);

        RaidTracker.ResolvedRaidCompletion resolved =
                RaidTracker.resolveForClient(remoteCompletion, "LocalPlayer");
        AtomicBoolean celebrationTriggered = new AtomicBoolean();
        AtomicBoolean gambitsReset = new AtomicBoolean();
        AtomicBoolean progressRefreshed = new AtomicBoolean();
        RaidTracker.finishLocalCompletion(
                resolved,
                () -> celebrationTriggered.set(true),
                () -> gambitsReset.set(true),
                () -> progressRefreshed.set(true));

        assertFalse(resolved.localCompletion());
        assertFalse(celebrationTriggered.get());
        assertFalse(gambitsReset.get());
        assertFalse(progressRefreshed.get());
        assertEquals(List.of("MrHmar", "Teslanator", "LoubiOP"), resolved.partyMembers());
        assertEquals(RaidPartySnapshotTracker.Phase.ACTIVE,
                RaidPartySnapshotTracker.stateForTest().phase());
        assertEquals(
                List.of("ActualOne", "ActualTwo", "ActualThree", "LocalPlayer"),
                RaidPartySnapshotTracker.stateForTest().activeRaidParty().usernames());
    }

    @Test
    void localGuildOnlyCompletionUsesAndConsumesCompleteRaidSnapshot() {
        RaidPartySnapshotTracker.setStateForTest(activeRaidState());
        RaidTracker.ParsedRaidCompletion localCompletion = completion(List.of("LocalPlayer"), 1);

        RaidTracker.ResolvedRaidCompletion resolved =
                RaidTracker.resolveForClient(localCompletion, "localplayer");

        assertTrue(resolved.localCompletion());
        assertEquals(
                List.of("ActualOne", "ActualTwo", "ActualThree", "LocalPlayer"),
                resolved.partyMembers());

        AtomicBoolean celebrationTriggered = new AtomicBoolean();
        AtomicBoolean gambitsReset = new AtomicBoolean();
        AtomicBoolean progressRefreshed = new AtomicBoolean();
        RaidTracker.finishLocalCompletion(
                resolved,
                () -> celebrationTriggered.set(true),
                () -> gambitsReset.set(true),
                () -> progressRefreshed.set(true));

        assertTrue(celebrationTriggered.get());
        assertTrue(gambitsReset.get());
        assertTrue(progressRefreshed.get());
        assertEquals(RaidPartySnapshotTracker.Phase.FINISHED_WAITING_FOR_EXIT,
                RaidPartySnapshotTracker.stateForTest().phase());
        assertTrue(RaidPartySnapshotTracker.stateForTest().activeRaidParty().usernames().isEmpty());
    }

    @Test
    void recognizesVanillaRaidFailureTitleWithoutWynntils() {
        assertTrue(RaidTracker.isRaidFailedTitle(Component.literal("Raid Failed!")));
        assertTrue(RaidTracker.isRaidFailedTitle(Component.literal("§4Raid §cFailed!")));
    }

    @Test
    void ignoresUnrelatedTitles() {
        assertFalse(RaidTracker.isRaidFailedTitle(Component.literal("Challenge Failed!")));
        assertFalse(RaidTracker.isRaidFailedTitle(null));
    }

    private RaidPartySnapshotTracker.TrackerState activeRaidState() {
        RaidPartySnapshotTracker.PartySnapshot party = RaidPartySnapshotTracker.PartySnapshot.from(
                List.of(
                        new RaidPartySnapshotTracker.SnapshotMember("FirstNick", "ActualOne"),
                        new RaidPartySnapshotTracker.SnapshotMember("SecondNick", "ActualTwo"),
                        new RaidPartySnapshotTracker.SnapshotMember("ThirdNick", "ActualThree"),
                        new RaidPartySnapshotTracker.SnapshotMember("LocalNick", "LocalPlayer")),
                false,
                1_000);
        RaidPartySnapshotTracker.TrackerState state = RaidPartySnapshotTracker.TrackerState.empty()
                .observe(party, true, false, 1_000);
        return state.observe(
                RaidPartySnapshotTracker.PartySnapshot.from(
                        List.of(new RaidPartySnapshotTracker.SnapshotMember("LocalNick", "LocalPlayer")),
                        true,
                        1_001),
                false,
                true,
                1_001);
    }

    private RaidTracker.ParsedRaidCompletion completion(List<String> partyMembers, int displayedPartySize) {
        return new RaidTracker.ParsedRaidCompletion(
                partyMembers,
                displayedPartySize,
                "The Canyon Colossus",
                2,
                2048,
                2.591,
                110);
    }
}
