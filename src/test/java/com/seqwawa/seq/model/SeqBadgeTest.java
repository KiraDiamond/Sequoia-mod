package com.seqwawa.seq.model;

import com.google.gson.Gson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SeqBadgeTest {
    private static final Gson GSON = new Gson();

    @Test
    void parsesLegacyCombinedBadgeName() {
        assertEquals(
                new SeqBadge(SeqBadgeType.WTP, SeqBadgeTier.GOLD),
                SeqBadge.parseLegacy("WTP_GOLD"));
    }

    @Test
    void rejectsUnknownLegacyBadge() {
        assertNull(SeqBadge.parseLegacy("UNKNOWN_GOLD"));
    }

    @Test
    void buildsTypeSpecificTexturePath() {
        assertEquals(
                "seq:textures/badges/wtp_gold.png",
                new SeqBadge(SeqBadgeType.WTP, SeqBadgeTier.GOLD).textureId().toString());
        assertEquals(
                "seq:textures/badges/nol_gold.png",
                new SeqBadge(SeqBadgeType.NOL, SeqBadgeTier.GOLD).textureId().toString());
        assertEquals(
                "seq:textures/badges/insignia_gold.png",
                new SeqBadge(SeqBadgeType.INSIGNIA, SeqBadgeTier.GOLD).textureId().toString());
    }

    @Test
    void rendersInsigniaAfterOtherBadgeTypes() {
        assertEquals(
                List.of(
                        new SeqBadge(SeqBadgeType.WTP, SeqBadgeTier.GOLD),
                        new SeqBadge(SeqBadgeType.NOL, SeqBadgeTier.SILVER),
                        new SeqBadge(SeqBadgeType.INSIGNIA, SeqBadgeTier.DIAMOND)),
                SeqBadge.sortForRender(List.of(
                        new SeqBadge(SeqBadgeType.INSIGNIA, SeqBadgeTier.DIAMOND),
                        new SeqBadge(SeqBadgeType.NOL, SeqBadgeTier.SILVER),
                        new SeqBadge(SeqBadgeType.WTP, SeqBadgeTier.GOLD))));
    }

    @Test
    void rankProfileFieldsUseSnakeCaseWireKeys() {
        String json = """
                {
                  "schema_version": 1,
                  "catalog": {"roles": [], "awards": [], "assets": []},
                  "profiles": [{
                    "minecraft": {"uuid": "00000000-0000-0000-0000-000000000000", "username": "Test"},
                    "role_keys": ["role-key"],
                    "award_keys": ["award-key"]
                  }]
                }
                """;

        RankProfilesResponse response = GSON.fromJson(json, RankProfilesResponse.class);

        assertEquals(1, response.schemaVersion());
        assertEquals(List.of("role-key"), response.profiles().getFirst().roleKeys());
        assertEquals(List.of("award-key"), response.profiles().getFirst().awardKeys());
    }

    @Test
    void profileDisplayColorsUseTheCanonicalWireKeyAndReadTheLegacyAlias() {
        String canonicalJson = """
                {
                  "display_colors": {
                    "primary": "#112233",
                    "secondary": "#445566",
                    "tertiary": "#778899"
                  }
                }
                """;
        RankProfilesResponse.Profile canonical = GSON.fromJson(canonicalJson, RankProfilesResponse.Profile.class);

        assertEquals(
                new RankProfilesResponse.RoleColors("#112233", "#445566", "#778899"),
                canonical.displayColors());

        RankProfilesResponse.Profile legacy = GSON.fromJson(
                """
                {"colors": {"primary": "#AABBCC", "secondary": null, "tertiary": null}}
                """,
                RankProfilesResponse.Profile.class);
        assertEquals("#AABBCC", legacy.displayColors().primary());

        var serialized = GSON.toJsonTree(legacy).getAsJsonObject();
        assertTrue(serialized.has("display_colors"), "new payloads must use the backend contract name");
    }

    @Test
    void supportsOnlyTheUntieredTccVariant() {
        assertEquals("seq:textures/badges/tcc.png",
                new SeqBadge(SeqBadgeType.TCC, SeqBadgeTier.SINGLE).textureId().toString());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SeqBadge(SeqBadgeType.TCC, SeqBadgeTier.GOLD));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SeqBadge(SeqBadgeType.WTP, SeqBadgeTier.SINGLE));
        assertNull(SeqBadge.parseLegacy("TCC_GOLD"));
        assertNull(SeqBadge.parseLegacy("NOL_SINGLE"));
        assertEquals(List.of(SeqBadgeType.WTP, SeqBadgeType.NOL, SeqBadgeType.TCC, SeqBadgeType.INSIGNIA),
                SeqBadge.sortForRender(List.of(
                        new SeqBadge(SeqBadgeType.INSIGNIA, SeqBadgeTier.GOLD),
                        new SeqBadge(SeqBadgeType.TCC, SeqBadgeTier.SINGLE),
                        new SeqBadge(SeqBadgeType.NOL, SeqBadgeTier.GOLD),
                        new SeqBadge(SeqBadgeType.WTP, SeqBadgeTier.GOLD)))
                        .stream().map(SeqBadge::type).toList());
    }

}
