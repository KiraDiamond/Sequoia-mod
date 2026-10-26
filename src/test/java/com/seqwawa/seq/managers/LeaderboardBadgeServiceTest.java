package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.seqwawa.seq.model.RankProfilesResponse;
import com.seqwawa.seq.model.SeqBadgeTier;
import com.seqwawa.seq.model.SeqBadgeType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LeaderboardBadgeServiceTest {
    private static final String PLAYER_UUID = "11111111-1111-1111-1111-111111111111";

    @Test
    void projectsOnlyCataloguedInsigniaAndSupportedRaidSeries() {
        RankProfilesResponse.Catalog catalog = new RankProfilesResponse.Catalog(
                List.of(new RankProfilesResponse.RoleDefinition(
                        "unstructured-role-key", "insignia", "diamond", "asset-insignia")),
                List.of(
                        new RankProfilesResponse.AwardDefinition(
                                "award-one", "raid_badge", "WTP", "bronze", "asset-wtp-bronze"),
                        new RankProfilesResponse.AwardDefinition(
                                "award-two", "raid_badge", "WTP", "gold", "asset-wtp-gold"),
                        new RankProfilesResponse.AwardDefinition(
                                "award-three", "raid_badge", "NOL", "silver", "asset-nol"),
                        new RankProfilesResponse.AwardDefinition(
                                "ignored-series", "raid_badge", "NOTG", "diamond", "asset-notg"),
                        new RankProfilesResponse.AwardDefinition(
                                "ignored-category", "cosmetic", "WTP", "diamond", "asset-cosmetic")),
                List.of(
                        asset("asset-insignia"),
                        asset("asset-wtp-bronze"),
                        asset("asset-wtp-gold"),
                        asset("asset-nol"),
                        asset("asset-notg"),
                        asset("asset-cosmetic")));
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                catalog,
                List.of(new RankProfilesResponse.Profile(
                        new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Player"),
                        List.of("unstructured-role-key"),
                        List.of("award-one", "award-two", "award-three", "ignored-series", "ignored-category"))));

        Map<String, Map<SeqBadgeType, SeqBadgeTier>> badges = LeaderboardBadgeService.parseProfiles(response);

        assertEquals(
                Map.of(
                        SeqBadgeType.INSIGNIA, SeqBadgeTier.DIAMOND,
                        SeqBadgeType.WTP, SeqBadgeTier.GOLD,
                        SeqBadgeType.NOL, SeqBadgeTier.SILVER),
                badges.get(PLAYER_UUID));
    }

    @Test
    void ignoresUnlinkedProfilesAndDefinitionsWithoutAssets() {
        RankProfilesResponse.Catalog catalog = new RankProfilesResponse.Catalog(
                List.of(new RankProfilesResponse.RoleDefinition(
                        "missing-asset-role", "insignia", "gold", "missing")),
                List.of(),
                List.of());
        RankProfilesResponse response = new RankProfilesResponse(
                1,
                catalog,
                List.of(
                        new RankProfilesResponse.Profile(null, List.of("missing-asset-role"), List.of()),
                        new RankProfilesResponse.Profile(
                                new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Player"),
                                List.of("missing-asset-role"),
                                List.of())));

        assertFalse(LeaderboardBadgeService.parseProfiles(response).containsKey(PLAYER_UUID));
    }

    private static RankProfilesResponse.AssetDefinition asset(String key) {
        return new RankProfilesResponse.AssetDefinition(key, "https://assets.example/" + key, "image/png", key);
    }
    @Test
    void projectsUntieredTccOnceAndRejectsInvalidTierCombinations() {
        var catalog = new RankProfilesResponse.Catalog(List.of(), List.of(
                new RankProfilesResponse.AwardDefinition("raid.tcc", "raid_badge", "TCC", null, "badge.tcc"),
                new RankProfilesResponse.AwardDefinition("invalid-tcc", "raid_badge", "TCC", "gold", "badge.tcc"),
                new RankProfilesResponse.AwardDefinition("invalid-nol", "raid_badge", "NOL", null, "badge.tcc"),
                new RankProfilesResponse.AwardDefinition("invalid-wtp", "raid_badge", "WTP", "single", "badge.tcc")),
                List.of(asset("badge.tcc")));
        var response = new RankProfilesResponse(1, catalog, List.of(new RankProfilesResponse.Profile(
                new RankProfilesResponse.MinecraftIdentity(PLAYER_UUID, "Player"), List.of(),
                List.of("raid.tcc", "raid.tcc", "invalid-tcc", "invalid-nol", "invalid-wtp"))));
        assertEquals(Map.of(SeqBadgeType.TCC, SeqBadgeTier.SINGLE),
                LeaderboardBadgeService.parseProfiles(response).get(PLAYER_UUID));
        assertEquals(Map.of(SeqBadgeType.TCC, SeqBadgeTier.SINGLE),
                LeaderboardBadgeService.parseProfilesByUsername(response).get("player"));
        assertEquals(java.util.Set.of("raid.tcc"), LeaderboardBadgeService.badgeDefinitions(catalog).keySet());
    }

}
