package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.seqwawa.seq.model.RankProfilesResponse;
import com.seqwawa.seq.model.SeqBadgeTier;
import com.seqwawa.seq.model.SeqBadgeType;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Parses a roster fixture shaped like a {@code scope=recognized} response, so the
 * assumptions the single-roster design rests on break loudly if either the
 * payload shape or our parsing changes.
 * <p>
 * The fixture is written by hand and contains no real members: invented names,
 * UUIDs and Discord ids, and {@code example.invalid} asset URLs. It covers one
 * profile of each shape the endpoint returns — both identities, Discord only,
 * Minecraft only, and no progression rank — plus solid, gradient and uncoloured
 * roles.
 */
class RankProfileRosterContractTest {

    private static RankProfilesResponse roster() {
        try (InputStream stream =
                RankProfileRosterContractTest.class.getResourceAsStream("/rank-profiles-sample.json")) {
            assertNotNull(stream, "the sample roster fixture must be on the test classpath");
            return new Gson()
                    .fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), RankProfilesResponse.class);
        } catch (Exception exception) {
            throw new AssertionError("could not read the sample roster", exception);
        }
    }

    @Test
    void theCatalogCarriesBothTheBadgeAndTheRankVocabulary() {
        // One response has to serve both features, so its catalog must describe both.
        RankProfilesResponse.Catalog catalog = roster().catalog();

        assertFalse(DiscordRankService.progressionRanks(catalog).isEmpty(), "progression ranks");
        assertFalse(LeaderboardBadgeService.badgeDefinitions(catalog).isEmpty(), "badge definitions");
        assertFalse(catalog.assets().isEmpty(), "badge art assets");
    }

    @Test
    void progressionRolesCarryLabelAndPosition() {
        DiscordRankService.progressionRanks(roster().catalog()).values().forEach(rank -> {
            assertFalse(rank.label().isBlank(), rank.key() + " needs a label");
            assertTrue(rank.position() > 0, rank.key() + " needs a position");
        });
    }

    @Test
    void readsSolidGradientAndUncolouredRolesFromOneCatalog() {
        var colors = DiscordRankService.roleColors(roster().catalog());

        assertFalse(colors.get("rank.sapling").isGradient(), "a solid role stays solid");
        assertTrue(colors.get("rank.yggdrasil").isGradient(), "a two-stop role reads as a gradient");
        assertNull(colors.get("rank.upper_strategist"), "an uncoloured role gets no ramp");
    }

    @Test
    void readsProfileDisplayColorsUnderEveryKnownIdentity() {
        DiscordRankService.Index index = DiscordRankService.parseProfiles(roster());

        for (String identity : new String[] {
            "336de99f-2ee1-c996-9656-d265a1812516",
            "alphaplayer",
            "900000000000000101",
            "yggdrasil alphaplayer"
        }) {
            assertEquals(
                    List.of(0x112233, 0x445566, 0x778899),
                    index.colorsByIdentity().get(identity).stops(),
                    identity);
        }

        DiscordRankService service = DiscordRankService.withIndex(index);
        assertEquals(
                List.of(0x4CB4FA),
                service.presentationForVerifiedMinecraftUsername("BetaPlayer").colors().stops(),
                "an absent display_colors field falls back to the progression catalog");
    }

    @Test
    void onlyProgressionRolesReachTheRankCatalog() {
        var ranks = DiscordRankService.progressionRanks(roster().catalog());

        assertTrue(ranks.containsKey("rank.sapling"));
        assertNull(ranks.get("in_game.recruiter"), "in-game ranks are a different category");
    }

    @Test
    void oneSnapshotFeedsBothIndexes() {
        RankProfilesResponse roster = roster();

        DiscordRankService.Index ranks = DiscordRankService.parseProfiles(roster);
        Map<String, Map<SeqBadgeType, SeqBadgeTier>> badgesByUuid = LeaderboardBadgeService.parseProfiles(roster);
        Map<String, Map<SeqBadgeType, SeqBadgeTier>> badgesByName =
                LeaderboardBadgeService.parseProfilesByUsername(roster);

        assertTrue(ranks.rankedProfiles() > 0, "the roster must yield ranks");
        assertFalse(badgesByUuid.isEmpty(), "the roster must yield badges by uuid");
        assertEquals(badgesByUuid.size(), badgesByName.size(), "both badge indexes cover the same members");
    }

    @Test
    void discordOnlyMembersAreReachableFromTheBridgeButNotFromGuildChat() {
        // Guild chat resolves speakers by game name, so a member without one must never
        // be matchable there, even though the roster carries them.
        DiscordRankService.Index index = DiscordRankService.parseProfiles(roster());

        assertNotNull(index.byDiscordIdentity().get("deltamember"), "reachable over the bridge");
        assertNull(index.byMinecraftUsername().get("deltamember"), "and never in guild chat");
    }

    @Test
    void membersWithoutAProgressionRankAreLeftAlone() {
        DiscordRankService.Index index = DiscordRankService.parseProfiles(roster());

        assertNull(index.byMinecraftUsername().get("zetaplayer"));
    }
}
