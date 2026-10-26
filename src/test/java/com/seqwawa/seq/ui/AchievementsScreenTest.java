package com.seqwawa.seq.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.seqwawa.seq.model.GuildRaidProgress;
import com.seqwawa.seq.model.GuildRaidProgress.Entry;
import com.seqwawa.seq.model.GuildRaidProgress.Category;
import com.seqwawa.seq.model.GuildRaidProgress.Milestone;
import com.seqwawa.seq.model.SeqRaid;
import com.seqwawa.seq.model.SeqTier;
import com.seqwawa.seq.ui.AchievementsScreen.Row;
import com.seqwawa.seq.ui.theme.UiColor;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AchievementsScreenTest {

    @Test
    void legacyProgressHasNoInventedTargetOrCompletion() {
        List<Row> rows = AchievementsScreen.buildRows(new GuildRaidProgress(1,
                Map.of("TNA", new Entry(9000, "platinum"))));
        Row tna = rows.stream().filter(row -> row.compactName().equals("TNA")).findFirst().orElseThrow();

        assertEquals(SeqRaid.values().length + 1, rows.size());
        assertEquals(SeqTier.PLATINUM, tna.tier());
        assertEquals(0, tna.nextAt());
        assertEquals(0f, AchievementsScreen.progressRatio(tna));
        assertEquals("Milestones unavailable", AchievementsScreen.targetLabel(tna));
    }

    @Test
    void sierraControlsOrderLabelsRankAndNonstandardTargets() {
        Category custom = new Category("war:completed", "war", "War veterans", 60L, false,
                "Champion", "diamond", new Milestone("elite", "Elite", 73, "bronze"), false);
        Row row = AchievementsScreen.buildRows(progress(custom)).getFirst();

        assertEquals("War veterans", row.name());
        assertEquals(SeqTier.DIAMOND, row.tier());
        assertEquals("Champion", AchievementsScreen.rankLabel(row));
        assertEquals(73, row.nextAt());
        assertEquals("Elite", row.nextLabel());
        assertEquals(60f / 73f, AchievementsScreen.progressRatio(row), 0.0001f);
        assertEquals("13 to Elite", AchievementsScreen.remainingLabel(row));
    }

    @Test
    void everySierraCategoryUsesItsOwnValueWording() {
        List<Row> rows = AchievementsScreen.buildRows(progress(
                category("war:completed", "war", 32L, false, false),
                category("profession:mining", "profession", 113L, false, false),
                category("role:insignia", "role", 2L, false, false),
                category("badge:helper", "badge", 1L, false, true)));

        assertEquals(4, rows.size());
        assertEquals("32 wars", AchievementsScreen.valueLabel(rows.get(0)));
        assertEquals("Level 113", AchievementsScreen.valueLabel(rows.get(1)));
        assertEquals("Role held", AchievementsScreen.valueLabel(rows.get(2)));
        assertEquals("Badge earned", AchievementsScreen.valueLabel(rows.get(3)));
        assertEquals(1f, AchievementsScreen.progressRatio(rows.get(3)));
    }

    @Test
    void unknownAndMissingTargetsNeverAppearComplete() {
        List<Row> rows = AchievementsScreen.buildRows(progress(
                category("profession:mining", "profession", null, true, false),
                category("war:completed", "war", 9999L, false, false)));

        assertEquals("Progress unknown", AchievementsScreen.valueLabel(rows.getFirst()));
        assertEquals("Unknown", AchievementsScreen.rankLabel(rows.getFirst()));
        for (Row row : rows) {
            assertEquals(0f, AchievementsScreen.progressRatio(row));
            assertTrue(!AchievementsScreen.targetLabel(row).equals("Complete"));
        }
    }

    @Test
    void anEmptyServerListDoesNotRestoreDisabledAchievements() {
        assertTrue(AchievementsScreen.buildRows(progress()).isEmpty());
    }

    @Test
    void unknownTierStylesPreserveTheServerLabel() {
        Row row = AchievementsScreen.buildRows(progress(new Category("raid:TNA", "raid", "TNA", 51L,
                false, "Veteran", "future-style", null, true))).getFirst();
        assertNull(row.tier());
        assertEquals("Veteran", AchievementsScreen.rankLabel(row));
        assertEquals(1f, AchievementsScreen.progressRatio(row));
    }

    @Test
    void everyTierHasItsOwnColour() {
        Set<UiColor> tokens = new HashSet<>();
        for (SeqTier tier : SeqTier.ordered()) {
            UiColor token = AchievementsScreen.tierToken(tier);
            assertNotNull(token, tier + " has no colour");
            assertTrue(tokens.add(token), tier + " reuses another tier's colour");
        }
    }

    @Test
    void largeCountsAreGrouped() {
        assertEquals("0", AchievementsScreen.formatCount(0));
        assertEquals("999", AchievementsScreen.formatCount(999));
        assertEquals("2,500", AchievementsScreen.formatCount(2500));
        assertEquals("10,000", AchievementsScreen.formatCount(10_000));
    }

    @Test
    void gridUsesScreenWidthAndAccountsForAnIncompleteLastLine() {
        List<Row> rows = AchievementsScreen.buildRows(new GuildRaidProgress(1, Map.of()));
        float narrowHeight = AchievementsScreen.contentHeight(400, rows);
        float wideHeight = AchievementsScreen.contentHeight(800, rows);
        assertEquals(1, AchievementsScreen.columnCount(400));
        assertEquals(2, AchievementsScreen.columnCount(484));
        assertEquals(3, AchievementsScreen.columnCount(794));
        assertTrue(narrowHeight > wideHeight);
        var placements = AchievementsScreen.cardPlacements(794, rows.subList(0, 5));
        assertEquals(placements.get(3).y(), placements.get(4).y());
        assertEquals(placements.get(4).y() + AchievementsScreen.cardHeight(0),
                AchievementsScreen.contentHeight(800, rows.subList(0, 5)));
        var panel = AchievementsScreen.panelLayout(640, 420, narrowHeight);
        assertTrue(panel.x() >= SequoiaSidebarNavigation.WIDTH + 8);
        assertTrue(panel.height() < narrowHeight);
        assertTrue(panel.bottom() <= 412);
    }

    @Test
    void sectionsPreserveSourceOrderAndKeepEveryCardInsideItsColumn() {
        List<Row> rows = AchievementsScreen.buildRows(progress(
                category("profession:mining", "profession", 113L, false, false),
                category("raid:TNA", "raid", 512L, false, false),
                category("profession:fishing", "profession", 132L, false, true),
                category("role:insignia", "role", 3L, false, false),
                category("badge:helper", "badge", 1L, false, true)));
        for (float width : new float[]{120, 264, 478, 798, 1600}) {
            var cards = AchievementsScreen.cardPlacements(width, rows);
            assertEquals(rows, cards.stream().map(AchievementsScreen.CardPlacement::row).toList());
            for (var card : cards) {
                assertTrue(card.x() >= 0);
                assertTrue(card.x() + card.width() <= width + 0.001f);
            }
            assertTrue(cards.get(2).y() > cards.get(1).y());
            if (width >= 470) assertEquals(cards.get(3).y(), cards.get(4).y());
            else assertTrue(cards.get(4).y() > cards.get(3).y());
        }
        assertEquals(0, AchievementsScreen.contentHeight(800, List.of()));
    }

    @Test
    void professionsWaitForArtworkAndProgressKeepsItsUnits() {
        Row mining = AchievementsScreen.buildRows(progress(new Category("profession:mining", "profession",
                "Localized label", 113L, false, "Level 110", null,
                new Milestone("120", "Level 120", 120, null), false))).getFirst();
        Row fishing = AchievementsScreen.buildRows(progress(
                category("profession:fishing", "profession", 132L, false, true))).getFirst();
        assertNull(AchievementBadgeRenderer.assetKey(mining));
        assertNull(AchievementBadgeRenderer.assetKey(fishing));
        assertEquals("Level 113 / 120", AchievementsScreen.progressValueLabel(mining));
        assertEquals("Level 132", AchievementsScreen.progressValueLabel(fishing));
    }

    @Test
    void insigniasUseTheMatchingBundledTierAndRolesKeepQuantitativeProgress() {
        List<Row> rows = AchievementsScreen.buildRows(progress(
                new Category("role:insignia", "role", "Insignia", 3L, false, "Gold", "gold",
                        new Milestone("diamond", "Diamond", 4, "diamond"), false),
                category("role:war_honour", "role", 3L, false, false)));
        assertEquals("Role held (3 / 4)", AchievementsScreen.progressValueLabel(rows.getFirst()));
        assertEquals("badge:insignia_gold", AchievementBadgeRenderer.assetKey(rows.getFirst()));
        assertNull(AchievementBadgeRenderer.assetKey(rows.getLast()));
    }

    @Test
    void awardAssetsKeepTheirOwnSeriesAndTierWithoutCollidingWithRaidArt() {
        for (String key : List.of("wtp_bronze", "wtp_silver", "wtp_gold", "wtp_diamond",
                "nol_bronze", "nol_silver", "nol_gold", "tcc",
                "insignia_bronze", "insignia_silver", "insignia_gold", "insignia_diamond")) {
            Row row = AchievementsScreen.buildRows(progress(
                    category("badge:" + key, "badge", 1L, false, true))).getFirst();
            assertEquals("badge:" + key, AchievementBadgeRenderer.assetKey(row));
            assertNotNull(getClass().getClassLoader().getResource("assets/seq/textures/badges/" + key + ".png"));
        }
        Row tcc = AchievementsScreen.buildRows(progress(category("raid:TCC", "raid", 0L, false, false))).getFirst();
        assertEquals("tcc", AchievementBadgeRenderer.assetKey(tcc));
    }

    @Test
    void raidingEventsShowEarnedAwardsWithoutOngoingProgress() {
        List<Row> rows = AchievementsScreen.buildRows(progress(
                new Category("event:wtp", "event", "Wartorn Palace Raiding Event", 4L, false,
                        "Diamond", "diamond", null, true, 4L, "badge.wtp.diamond"),
                new Category("event:nol", "event", "Nexus of Light Raiding Event", 1L, false,
                        "Bronze", "bronze", new Milestone("silver", "Silver", 2, "silver"),
                        false, 3L, "badge.nol.bronze"),
                new Category("event:wtp", "event", "Wartorn Palace Raiding Event", 0L, false,
                        "No badge", null, new Milestone("bronze", "Bronze", 1, "bronze"),
                        false, 4L, null),
                new Category("event:tcc", "event", "The Canyon Colossus Raiding Event", 1L, false,
                        "Earned", null, null, true, 1L, "badge.tcc")));
        assertEquals("Earned", AchievementsScreen.valueLabel(rows.get(0)));
        assertEquals("badge:wtp_diamond", AchievementBadgeRenderer.assetKey(rows.get(0)));
        assertEquals("Earned", AchievementsScreen.progressValueLabel(rows.get(1)));
        assertEquals("Raiding event award", AchievementsScreen.remainingLabel(rows.get(1)));
        assertEquals("No badge", AchievementsScreen.rankLabel(rows.get(2)));
        assertEquals("Not earned", AchievementsScreen.valueLabel(rows.get(2)));
        assertEquals("badge:tcc", AchievementBadgeRenderer.assetKey(rows.get(3)));
        for (Row row : rows) {
            assertEquals(0f, AchievementsScreen.progressRatio(row));
            assertEquals(AchievementsScreen.valueLabel(row), AchievementsScreen.targetLabel(row));
            assertEquals("Raiding event award", AchievementsScreen.remainingLabel(row));
        }
        assertEquals("Raiding events", AchievementsScreen.sectionLabel(rows.get(0)));
    }

    @Test
    void communityAndLegacyAwardKeysResolveToTheExistingArt() {
        for (String key : List.of("raid.wtp.diamond", "wartorn_palace_diamond", "wtp_diamond")) {
            Row row = AchievementsScreen.buildRows(progress(category("badge:" + key, "badge", 1L, false, true))).getFirst();
            assertEquals("badge:wtp_diamond", AchievementBadgeRenderer.assetKey(row));
        }
        Row nol = AchievementsScreen.buildRows(progress(category("badge:nexus_of_light_gold", "badge", 1L, false, true))).getFirst();
        assertEquals("badge:nol_gold", AchievementBadgeRenderer.assetKey(nol));
    }

    @Test
    void compactViewportKeepsThePanelInsideItsMargins() {
        AchievementsScreen.PanelLayout panel = AchievementsScreen.panelLayout(284.7f, 160, 345);

        assertTrue(panel.x() >= 14);
        assertTrue(panel.right() <= 284.7f - 8);
        assertTrue(panel.y() >= 0);
        assertTrue(panel.bottom() <= 160 - 8);
    }

    private static Category category(String key, String kind, Long value, boolean unknown, boolean complete) {
        return new Category(key, kind, key, value, unknown, null, null, null, complete);
    }

    private static GuildRaidProgress progress(Category... categories) {
        return new GuildRaidProgress(1, Map.of(), List.of(), List.of(categories));
    }
}
