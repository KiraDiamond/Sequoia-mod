package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.seqwawa.seq.model.RankProfilesResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class RankProfileBadgeAssetCacheTest {
    @Test
    void acceptsOnlyHashedPngAssetsFromTheAssetHost() throws Exception {
        byte[] bytes = "badge".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        RankProfilesResponse.AssetDefinition asset = new RankProfilesResponse.AssetDefinition(
                "badge.wtp.gold",
                "https://assets.seqwawa.com/v1/" + digest + "/wtp_gold.png",
                "image/png",
                digest);

        assertTrue(RankProfileBadgeAssetCache.isValid(asset));
        assertTrue(RankProfileBadgeAssetCache.matchesDigest(bytes, digest));
        assertFalse(RankProfileBadgeAssetCache.matchesDigest("other".getBytes(StandardCharsets.UTF_8), digest));
    }

    @Test
    void rejectsAssetsFromUnexpectedOrigins() {
        RankProfilesResponse.AssetDefinition asset = new RankProfilesResponse.AssetDefinition(
                "badge.wtp.gold",
                "https://example.com/wtp_gold.png",
                "image/png",
                "a".repeat(64));

        assertFalse(RankProfileBadgeAssetCache.isValid(asset));
    }
    @Test
    void selectsTheUntieredTccAsset() {
        var asset = new RankProfilesResponse.AssetDefinition("badge.tcc",
                "https://assets.seqwawa.com/v1/" + "a".repeat(64) + "/tcc.png", "image/png", "a".repeat(64));
        var catalog = new RankProfilesResponse.Catalog(java.util.List.of(), java.util.List.of(
                new RankProfilesResponse.AwardDefinition("raid.tcc", "raid_badge", "TCC", null, "badge.tcc")),
                java.util.List.of(asset));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Map.of(
                new com.seqwawa.seq.model.SeqBadge(com.seqwawa.seq.model.SeqBadgeType.TCC,
                        com.seqwawa.seq.model.SeqBadgeTier.SINGLE), asset),
                RankProfileBadgeAssetCache.supportedAssets(catalog));
    }

}
