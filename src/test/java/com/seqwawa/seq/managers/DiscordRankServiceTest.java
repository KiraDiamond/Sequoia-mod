package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.seqwawa.seq.model.DiscordRank;
import com.seqwawa.seq.model.RankPresentation;
import com.seqwawa.seq.model.RankProfilesResponse;
import com.seqwawa.seq.utils.ColorRamp;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscordRankServiceTest {
    private static final String PLAYER_UUID = "95f1b342-d7f5-466e-b1c8-32f0b42214db";

    private static final RankProfilesResponse.Catalog CATALOG = new RankProfilesResponse.Catalog(
            List.of(
                    role("rank.sapling", "Sapling", "progression_rank", 88),
                    role("rank.dryad", "Dryad", "progression_rank", 94),
                    role("rank.treant", "Treant", "progression_rank", 104),
                    role("in_game.recruiter", "Recruiter", "in_game_rank", 40)),
            List.of(),
            List.of());

    private static final RankProfilesResponse.Catalog NICKNAME_CATALOG = new RankProfilesResponse.Catalog(
            List.of(
                    role("rank.sapling", "Sapling", "progression_rank", 88, "#4CB4FA"),
                    role("rank.spriggan", "Spriggan", "progression_rank", 96),
                    role("rank.treant", "Treant", "progression_rank", 104, "#1B9056")),
            List.of(),
            List.of());

    @Test
    void unrankedIdentityStillReservesItsLegacyAliasBeforeRankEligibility() {
        var ranked = new RankProfilesResponse.Profile(
                new RankProfilesResponse.DiscordIdentity("111", "ranked", "Shared", null),
                new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Shared"),
                List.of("rank.treant"), List.of(), summary("rank.treant"));
        var unranked = new RankProfilesResponse.Profile(
                new RankProfilesResponse.DiscordIdentity("222", "unranked", "Shared", null),
                null, List.of(), List.of(), summary(null));
        for (boolean reverse : List.of(false, true)) {
            var service = serviceWith(new RankProfilesResponse(1, NICKNAME_CATALOG,
                    reverse ? List.of(unranked, ranked) : List.of(ranked, unranked)));
            assertNull(service.rankForBridgeSender("Shared"));
            assertNull(service.presentationForBridgeSender("Shared", null));
            assertNull(service.insigniaForBridgeSender("Shared", null));
            assertNull(service.presentationForBridgeSender("Shared", "222"));
            assertNotNull(service.presentationForBridgeSender("Shared", "111"));
        }
    }

    @Test
    void knownUnrankedGameUuidCannotFallBackToAnotherNamesRank() {
        var ranked = new RankProfilesResponse.Profile(null,
                new RankProfilesResponse.MinecraftIdentity(null, "Ranked"),
                List.of("rank.treant"), List.of(), summary("rank.treant"));
        var unranked = new RankProfilesResponse.Profile(null,
                new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Unranked"),
                List.of(), List.of(), summary(null));
        var service = serviceWith(new RankProfilesResponse(1, NICKNAME_CATALOG, List.of(ranked, unranked)));
        assertNull(service.presentationForMinecraftAccount(java.util.UUID.fromString(PLAYER_UUID), "Ranked"));
    }

    @Test
    void conflictingVerifiedGameNamesDoNotDependOnProfileOrderOrRankEligibility() {
        var ranked = new RankProfilesResponse.Profile(null,
                new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Shared"),
                List.of("rank.treant"), List.of(), summary("rank.treant"));
        var unranked = new RankProfilesResponse.Profile(null,
                new RankProfilesResponse.MinecraftIdentity(java.util.UUID.randomUUID().toString(), "Shared"),
                List.of(), List.of(), summary(null));
        for (boolean reverse : List.of(false, true)) {
            var service = serviceWith(new RankProfilesResponse(1, NICKNAME_CATALOG,
                    reverse ? List.of(unranked, ranked) : List.of(ranked, unranked)));
            assertNull(service.presentationForVerifiedMinecraftUsername("Shared"));
        }
    }

    @Test
    void suppliedDiscordIdCannotBorrowAnotherNamesRankOrPalette() {
        var service = serviceWith(new RankProfilesResponse(1, NICKNAME_CATALOG, List.of(
                discordOnlyProfile("111", "first", "Collision", "rank.treant",
                        new RankProfilesResponse.RoleColors("#112233", null, null)),
                discordOnlyProfile("222", "second", "Other", "rank.sapling", null))));
        assertNull(service.presentationForBridgeSender("Collision", "unranked-id"));
        var own = service.presentationForBridgeSender("Collision", "222");
        assertEquals("Sapling", own.label());
        assertEquals(List.of(0x4CB4FA), own.colors().stops());
    }

    @Test
    void discordDisplayAliasesCannotImpersonateIdsOrAmbiguousNames() {
        for (boolean reverse : List.of(false, true)) {
            var actual = discordOnlyProfile("111", "first", "Shared", "rank.treant", null);
            var claim = discordOnlyProfile("222", "Shared", "111", "rank.sapling", null);
            var service = serviceWith(new RankProfilesResponse(1, NICKNAME_CATALOG,
                    reverse ? List.of(claim, actual) : List.of(actual, claim)));
            assertEquals("Treant", service.presentationForBridgeSender("anything", "111").label());
            assertNull(service.presentationForBridgeSender("Shared", null));
        }
    }

    @Test
    void guildChatLookupDoesNotPromoteADiscordNicknameToAVerifiedAccount() {
        var response = new RankProfilesResponse(1, NICKNAME_CATALOG,
                List.of(discordOnlyProfile("999", "claimant", "Sapling ClaimedName", "rank.sapling", null)));
        var service = serviceWith(response);
        assertNull(service.presentationForVerifiedMinecraftUsername("ClaimedName"));
    }

    @Test
    void indexesEveryIdentityAMemberCanAppearUnder() {
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                CATALOG,
                List.of(new RankProfilesResponse.Profile(
                        new RankProfilesResponse.DiscordIdentity(
                                "719729926802112553", "breadmusic", "Sapling dix", null),
                        new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "dix"),
                        List.of("rank.sapling", "in_game.recruiter"),
                        List.of(),
                        summary("rank.sapling"),
                        new RankProfilesResponse.RoleColors("#112233", "#445566", "#778899"))));

        DiscordRankService.Index index = DiscordRankService.parseProfiles(response);

        assertEquals(1, index.rankedProfiles());
        assertEquals("Sapling", index.byMinecraftUuid().get(PLAYER_UUID).label());
        assertEquals("Sapling", index.byMinecraftUsername().get("dix").label());
        assertEquals("Sapling", index.byDiscordIdentity().get("breadmusic").label());
        assertEquals("Sapling", index.byDiscordIdentity().get("719729926802112553").label());
        assertEquals("Sapling", index.byDiscordIdentity().get("sapling dix").label());
        // The bridge forwards the nickname without its rank prefix.
        assertEquals("Sapling", index.byDiscordIdentity().get("dix").label());
        for (String identity : List.of(
                PLAYER_UUID, "dix", "719729926802112553", "breadmusic", "sapling dix")) {
            assertEquals(
                    List.of(0x112233, 0x445566, 0x778899),
                    index.colorsByIdentity().get(identity).stops(),
                    identity);
        }
    }

    @Test
    void fallsBackToHighestProgressionRoleWhenTheSummaryIsMissing() {
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                CATALOG,
                List.of(new RankProfilesResponse.Profile(
                        null,
                        new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Player"),
                        List.of("rank.sapling", "rank.treant", "rank.dryad"),
                        List.of(),
                        null)));

        DiscordRankService.Index index = DiscordRankService.parseProfiles(response);

        assertEquals("Treant", index.byMinecraftUsername().get("player").label());
    }

    @Test
    void skipsMembersWithoutAProgressionRank() {
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                CATALOG,
                List.of(new RankProfilesResponse.Profile(
                        new RankProfilesResponse.DiscordIdentity("1", "guest", "Guest", null),
                        new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Guest"),
                        List.of("in_game.recruiter"),
                        List.of(),
                        summary(null))));

        DiscordRankService.Index index = DiscordRankService.parseProfiles(response);

        assertEquals(0, index.rankedProfiles());
        assertNull(index.byMinecraftUsername().get("guest"));
        assertNull(index.byDiscordIdentity().get("guest"));
    }

    @Test
    void readsTheDiscordRoleColour() {
        RankProfilesResponse.Catalog catalog = new RankProfilesResponse.Catalog(
                List.of(
                        role("rank.sapling", "Sapling", "progression_rank", 88, "#4CB4FA"),
                        role("rank.dryad", "Dryad", "progression_rank", 94, "CDECE4"),
                        role("rank.upper_strategist", "Upper Strategist", "progression_rank", 96, null),
                        role("rank.treant", "Treant", "progression_rank", 104, "not-a-colour")),
                List.of(),
                List.of());

        var colors = DiscordRankService.roleColors(catalog);

        assertEquals(0x4CB4FA, colors.get("rank.sapling").first());
        assertEquals(0xCDECE4, colors.get("rank.dryad").first(), "a missing # must still parse");
        assertNull(colors.get("rank.upper_strategist"), "an uncoloured role gets no ramp");
        assertNull(colors.get("rank.treant"), "malformed colours must not break the catalog");
    }

    @Test
    void readsEveryGradientStopInDiscordsOrder() {
        RankProfilesResponse.RoleColors colors =
                new RankProfilesResponse.RoleColors("#FF0000", "#00FF00", "#0000FF");

        assertEquals(List.of(0xFF0000, 0x00FF00, 0x0000FF), DiscordRankService.colorRamp(colors));
    }

    @Test
    void treatsASolidRoleAsASingleStopAndAnUncolouredOneAsNone() {
        assertEquals(
                List.of(0x4CB4FA),
                DiscordRankService.colorRamp(new RankProfilesResponse.RoleColors("#4CB4FA", null, null)));
        assertEquals(
                List.of(), DiscordRankService.colorRamp(new RankProfilesResponse.RoleColors(null, null, null)));
        assertEquals(List.of(), DiscordRankService.colorRamp(null));
    }

    @Test
    void dropsMalformedStopsWithoutLosingTheValidOnes() {
        RankProfilesResponse.RoleColors colors =
                new RankProfilesResponse.RoleColors("#FF0000", "not-a-colour", "0000FF");

        assertEquals(List.of(0xFF0000, 0x0000FF), DiscordRankService.colorRamp(colors));
    }

    @Test
    void exposesGradientRolesThroughTheCatalog() {
        RankProfilesResponse.Catalog catalog = new RankProfilesResponse.Catalog(
                List.of(new RankProfilesResponse.RoleDefinition(
                        "rank.yggdrasil",
                        "Yggdrasil",
                        "progression_rank",
                        null,
                        null,
                        120,
                        new RankProfilesResponse.RoleColors("#7506D6", "#CDECE4", null))),
                List.of(),
                List.of());

        DiscordRank yggdrasil = DiscordRankService.progressionRanks(catalog).get("rank.yggdrasil");
        ColorRamp colors = DiscordRankService.roleColors(catalog).get("rank.yggdrasil");

        assertEquals("Yggdrasil", yggdrasil.label(), "the rank itself carries no colour");
        assertTrue(colors.isGradient());
        assertEquals(List.of(0x7506D6, 0xCDECE4), colors.stops());
        assertEquals(0x7506D6, colors.first(), "the primary stop stays the single-colour answer");
    }

    @Test
    void anIndividualColourOverridesTheRoleColour() {
        // Two Saplings, one of whom the backend gives their own palette: they must
        // render differently, which is why colour is resolved apart from the rank.
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                colouredCatalog(),
                List.of(
                        new RankProfilesResponse.Profile(
                                null,
                                new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Plain"),
                                List.of("rank.sapling"),
                                List.of(),
                                summary("rank.sapling"),
                                null),
                        new RankProfilesResponse.Profile(
                                null,
                                new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Special"),
                                List.of("rank.sapling"),
                                List.of(),
                                summary("rank.sapling"),
                                new RankProfilesResponse.RoleColors("#FF00FF", "#00FFFF", "#FFFF00"))));

        DiscordRankService.Index index = DiscordRankService.parseProfiles(response);
        DiscordRankService service = DiscordRankService.withIndex(index);

        assertEquals(List.of(0xFF00FF, 0x00FFFF, 0xFFFF00), index.colorsByIdentity().get("special").stops());
        assertNull(index.colorsByIdentity().get("plain"), "an ordinary member falls back to their role");
        assertEquals(List.of(0x4CB4FA), index.colorsByRoleKey().get("rank.sapling").stops());
        RankPresentation plain = service.presentationForVerifiedMinecraftUsername("Plain");
        RankPresentation special = service.presentationForVerifiedMinecraftUsername("Special");
        assertEquals(List.of(0x4CB4FA), plain.colors().stops());
        assertEquals(List.of(0x4CB4FA), plain.roleColors().stops());
        assertTrue(plain.individualColors().isEmpty());
        assertEquals(
                List.of(0xFF00FF, 0x00FFFF, 0xFFFF00),
                special.colors().stops());
        assertEquals(List.of(0x4CB4FA), special.roleColors().stops());
        assertEquals(List.of(0xFF00FF, 0x00FFFF, 0xFFFF00), special.individualColors().stops());
    }

    @Test
    void emptyOrMalformedProfileDisplayColorsDoNotEraseTheCatalogFallback() {
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                colouredCatalog(),
                List.of(
                        new RankProfilesResponse.Profile(
                                null,
                                new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Empty"),
                                List.of("rank.sapling"),
                                List.of(),
                                summary("rank.sapling"),
                                new RankProfilesResponse.RoleColors(null, null, null)),
                        new RankProfilesResponse.Profile(
                                null,
                                new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Malformed"),
                                List.of("rank.sapling"),
                                List.of(),
                                summary("rank.sapling"),
                                new RankProfilesResponse.RoleColors("#FF00FF", "not-a-colour", null))));

        DiscordRankService service = serviceWith(response);

        for (String identity : List.of("Empty", "Malformed")) {
            assertNull(DiscordRankService.parseProfiles(response)
                    .colorsByIdentity()
                    .get(identity.toLowerCase()));
            assertEquals(
                    List.of(0x4CB4FA),
                    service.presentationForVerifiedMinecraftUsername(identity).colors().stops());
        }
    }

    @Test
    void resolvesTheRoleColourWhenTheMemberHasNoneOfTheirOwn() {
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                colouredCatalog(),
                List.of(new RankProfilesResponse.Profile(
                        null,
                        new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Plain"),
                        List.of("rank.sapling"),
                        List.of(),
                        summary("rank.sapling"),
                        null)));

        DiscordRankService.Index index = DiscordRankService.parseProfiles(response);
        DiscordRank sapling = index.byMinecraftUsername().get("plain");

        assertEquals(List.of(0x4CB4FA), index.colorsByRoleKey().get(sapling.key()).stops());
    }

    @Test
    void rankedDiscordNicknameOnlyDecoratesTheDiscordIdentity() {
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                NICKNAME_CATALOG,
                List.of(discordOnlyProfile(
                        "215820027700576258",
                        "afekvaknin",
                        "Spriggan MrHmar",
                        "rank.treant",
                        new RankProfilesResponse.RoleColors("#112233", "#445566", "#778899"))));
        DiscordRankService service = serviceWith(response);

        RankPresentation presentation = service.presentationForVerifiedMinecraftUsername("mrhMAR");

        assertNull(presentation);
        assertNull(service.rankForMinecraftUsername("MrHmar"));

        RankPresentation bridge = service.presentationForBridgeSender("MrHmar", "215820027700576258");
        assertNotNull(bridge);
        assertEquals("Treant", bridge.label());
        assertEquals(List.of(0x112233, 0x445566, 0x778899), bridge.colors().stops());
    }

    @Test
    void verifiedMinecraftIdentityWinsOverAConflictingDiscordNickname() {
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                NICKNAME_CATALOG,
                List.of(
                        new RankProfilesResponse.Profile(
                                null,
                                new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "ClaimedName"),
                                List.of("rank.sapling"),
                                List.of(),
                                summary("rank.sapling"),
                                new RankProfilesResponse.RoleColors("#010203", null, null)),
                        discordOnlyProfile(
                                "222",
                                "discordclaimant",
                                "Spriggan ClaimedName",
                                "rank.treant",
                                new RankProfilesResponse.RoleColors("#AABBCC", null, null))));
        DiscordRankService service = serviceWith(response);

        RankPresentation presentation = service.presentationForVerifiedMinecraftUsername("claimedname");

        assertNotNull(presentation);
        assertEquals("Sapling", presentation.label());
        assertEquals(
                List.of(0x010203),
                presentation.colors().stops(),
                "the Discord alias must not replace the verified profile palette");
    }

    @Test
    void duplicateNicknameAliasesAreAmbiguousRegardlessOfCase() {
        RankProfilesResponse.Profile first =
                discordOnlyProfile("301", "first", "Spriggan SharedName", "rank.treant", null);
        RankProfilesResponse.Profile second =
                discordOnlyProfile("302", "second", "Sapling sharedname", "rank.sapling", null);

        for (List<RankProfilesResponse.Profile> profiles : List.of(List.of(first, second), List.of(second, first))) {
            DiscordRankService service = serviceWith(new RankProfilesResponse(1, NICKNAME_CATALOG, profiles));
            assertNull(service.presentationForVerifiedMinecraftUsername("SharedName"));
            assertNull(service.presentationForVerifiedMinecraftUsername("sharedname"));
        }
    }

    @Test
    void invalidNicknameCandidatesAreIgnored() {
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                NICKNAME_CATALOG,
                List.of(
                        discordOnlyProfile("401", "too-short", "Spriggan ab", "rank.treant", null),
                        discordOnlyProfile("402", "punctuated", "Spriggan bad-name", "rank.treant", null),
                        discordOnlyProfile(
                                "403", "too-long", "Spriggan ThisNameIsFarTooLong", "rank.treant", null),
                        discordOnlyProfile("404", "spaced", "Spriggan Two Names", "rank.treant", null)));
        DiscordRankService service = serviceWith(response);

        for (String candidate : List.of("ab", "bad-name", "ThisNameIsFarTooLong", "Two Names")) {
            assertNull(service.presentationForVerifiedMinecraftUsername(candidate), candidate);
        }
    }

    @Test
    void unprefixedDisplayNamesAndRawDiscordUsernamesDoNotBecomeGameAliases() {
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                NICKNAME_CATALOG,
                List.of(discordOnlyProfile(
                        "501", "RawMatch", "UnprefixedName", "rank.treant", null)));
        DiscordRankService service = serviceWith(response);

        assertNull(service.presentationForVerifiedMinecraftUsername("RawMatch"));
        assertNull(service.presentationForVerifiedMinecraftUsername("UnprefixedName"));
        assertEquals("Treant", service.presentationForBridgeSender("RawMatch").label());
        assertEquals("Treant", service.presentationForBridgeSender("UnprefixedName").label());
    }

    @Test
    void nicknameAliasWithoutAProfilePaletteRemainsUnverified() {
        DiscordRankService service = serviceWith(new RankProfilesResponse(
                1,
                NICKNAME_CATALOG,
                List.of(discordOnlyProfile(
                        "601", "catalogue", "Spriggan CatalogName", "rank.treant", null))));

        RankPresentation presentation = service.presentationForVerifiedMinecraftUsername("CatalogName");

        assertNull(presentation);
    }

    private static RankProfilesResponse.Catalog colouredCatalog() {
        return new RankProfilesResponse.Catalog(
                List.of(role("rank.sapling", "Sapling", "progression_rank", 88, "#4CB4FA")), List.of(), List.of());
    }

    private static RankProfilesResponse.Profile discordOnlyProfile(
            String id,
            String username,
            String displayName,
            String progressionRank,
            RankProfilesResponse.RoleColors displayColors) {
        return new RankProfilesResponse.Profile(
                new RankProfilesResponse.DiscordIdentity(id, username, displayName, null),
                null,
                List.of(progressionRank),
                List.of(),
                summary(progressionRank),
                displayColors);
    }

    @Test
    void linksEveryDiscordIdentityBackToTheGameAccount() {
        // Badges are held against the Minecraft account, so a bridge sender has to be
        // mapped back to their game name before an insignia can be looked up.
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                CATALOG,
                List.of(new RankProfilesResponse.Profile(
                        new RankProfilesResponse.DiscordIdentity(
                                "719729926802112553", "breadmusic", "Sapling dix", null),
                        new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "dix"),
                        List.of("rank.sapling"),
                        List.of(),
                        summary("rank.sapling"))));

        var links = DiscordRankService.parseProfiles(response).minecraftUsernameByDiscordIdentity();

        assertEquals("dix", links.get("719729926802112553"));
        assertEquals("dix", links.get("breadmusic"));
        assertEquals("dix", links.get("sapling dix"));
    }

    @Test
    void prefersTheDiscordIdOverADisplayNameThatBelongsToSomeoneElse() {
        // Two members, where one has taken the other's game name as their Discord
        // display name: matching on the name alone would hand over the wrong rank.
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                CATALOG,
                List.of(
                        new RankProfilesResponse.Profile(
                                new RankProfilesResponse.DiscordIdentity("111", "real", "Impostor", null),
                                new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Impostor"),
                                List.of("rank.treant"),
                                List.of(),
                                summary("rank.treant")),
                        new RankProfilesResponse.Profile(
                                new RankProfilesResponse.DiscordIdentity("222", "other", "Someone", null),
                                new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Someone"),
                                List.of("rank.sapling"),
                                List.of(),
                                summary("rank.sapling"))));

        DiscordRankService service = serviceWith(response);

        assertEquals("Sapling", service.rankForBridgeSender("Impostor", "222").label(), "the id must win");
        assertEquals("Treant", service.rankForBridgeSender("Impostor", null).label(), "no id falls back to the name");
        assertNull(service.rankForBridgeSender("Impostor", "unknown-id"));
    }

    @Test
    void toleratesSendersItCannotIdentify() {
        // The bridge sends no id on older backends, and the index maps are immutable,
        // which throw on a null key instead of returning null.
        DiscordRankService service = serviceWith(new RankProfilesResponse(1, CATALOG, List.of()));

        assertNull(service.rankForBridgeSender(null, null));
        assertNull(service.rankForMinecraftUsername(null));
        assertNull(service.presentationForBridgeSender("nobody", null));
    }

    @Test
    void keepsDiscordOnlyMembersOutOfTheVerifiedMinecraftIndex() {
        // A ranked nickname can provide a display-only fallback, but must never enter
        // the verified game-name index used as an account association.
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                CATALOG,
                List.of(new RankProfilesResponse.Profile(
                        new RankProfilesResponse.DiscordIdentity("900", "discordonly", "Sapling ghost", null),
                        null,
                        List.of("rank.sapling"),
                        List.of(),
                        summary("rank.sapling"))));

        DiscordRankService.Index index = DiscordRankService.parseProfiles(response);

        assertEquals("Sapling", index.byDiscordIdentity().get("discordonly").label());
        assertEquals(1, index.rankedProfiles());
        assertNull(index.byMinecraftUsername().get("ghost"), "the nickname is not a verified game identity");
        assertNull(DiscordRankService.withIndex(index).presentationForVerifiedMinecraftUsername("ghost"));
    }

    @Test
    void onlyProgressionRolesEnterTheRankCatalog() {
        assertEquals(
                List.of("rank.dryad", "rank.sapling", "rank.treant"),
                DiscordRankService.progressionRanks(CATALOG).keySet().stream()
                        .sorted()
                        .toList());
    }

    private static RankProfilesResponse.RoleDefinition role(
            String key, String label, String category, int position) {
        return role(key, label, category, position, null);
    }

    private static RankProfilesResponse.RoleDefinition role(
            String key, String label, String category, int position, String primaryColor) {
        return new RankProfilesResponse.RoleDefinition(
                key,
                label,
                category,
                null,
                null,
                position,
                new RankProfilesResponse.RoleColors(primaryColor, null, null));
    }

    private static DiscordRankService serviceWith(RankProfilesResponse response) {
        return DiscordRankService.withIndex(DiscordRankService.parseProfiles(response));
    }

    private static RankProfilesResponse.Summary summary(String progressionRank) {
        return new RankProfilesResponse.Summary(progressionRank, null, null, null, null);
    }
}
