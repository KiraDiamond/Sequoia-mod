package com.seqwawa.seq.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.seqwawa.seq.model.GuildRaidProgress.Entry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GuildRaidProgressTest {

    private static final Gson GSON = new Gson();

    private static final String BACKEND_PAYLOAD =
            """
            {"schema_version":1,"progress":{"NOTG":{"count":0,"tier":null},\
            "TNA":{"count":19,"tier":null},"TCC":{"count":0,"tier":null},\
            "NOL":{"count":0,"tier":null},"TWP":{"count":0,"tier":null},\
            "total":{"count":19,"tier":null}}}
            """;

    @Test
    void readsSierraCategoriesAndNullableUnknownValues() {
        GuildRaidProgress progress = GSON.fromJson("""
                {"schema_version":1,"categories":[
                  {"key":"profession:mining","kind":"profession","label":"Mining","value":null,
                   "unknown":true,"completed":false},
                  {"key":"war:completed","kind":"war","label":"Wars","value":3000000000,
                   "unknown":false,"tier_label":"Veteran","tier":"gold",
                   "next":{"id":"elite","label":"Elite","at":4000000000,"tier":"diamond"},
                   "completed":false}]}
                """, GuildRaidProgress.class);
        assertNull(progress.categories().getFirst().value());
        assertTrue(progress.categories().getFirst().unknown());
        assertEquals(3_000_000_000L, progress.categories().getLast().value());
        assertEquals("Veteran", progress.categories().getLast().tierLabel());
        assertEquals(4_000_000_000L, progress.categories().getLast().next().at());
    }

    @Test
    void distinguishesLegacyResponsesFromAnEmptyCatalog() {
        assertNull(GSON.fromJson(BACKEND_PAYLOAD, GuildRaidProgress.class).categories());
        assertTrue(GSON.fromJson("{\"categories\":[]}", GuildRaidProgress.class).categories().isEmpty());
    }

    @Test
    void readsTheBackendPayload() {
        GuildRaidProgress progress = GSON.fromJson(BACKEND_PAYLOAD, GuildRaidProgress.class);

        assertEquals(1, progress.schemaVersion());
        assertEquals(19, progress.count(SeqRaid.TNA));
        assertEquals(0, progress.count(SeqRaid.NOTG));
        assertEquals(19, progress.totalCount());
    }

    @Test
    void theTierComesStraightFromTheBackend() {
        GuildRaidProgress progress = GSON.fromJson(
                "{\"schema_version\":1,\"progress\":{\"TNA\":{\"count\":19,\"tier\":\"platinum\"},"
                        + "\"total\":{\"count\":19,\"tier\":\"gold\"}}}",
                GuildRaidProgress.class);

        assertEquals(SeqTier.PLATINUM, progress.tier(SeqRaid.TNA));
        assertEquals(SeqTier.GOLD, progress.totalTier());
    }

    @Test
    void aNullTierReadsAsUnranked() {
        GuildRaidProgress progress = GSON.fromJson(BACKEND_PAYLOAD, GuildRaidProgress.class);

        assertNull(progress.tier(SeqRaid.TNA));
        assertNull(progress.totalTier());
    }

    @Test
    void aTierTheModDoesNotKnowReadsAsUnranked() {
        GuildRaidProgress progress = GSON.fromJson(
                "{\"schema_version\":1,\"progress\":{\"TNA\":{\"count\":19,\"tier\":\"titanium\"}}}",
                GuildRaidProgress.class);

        assertNull(progress.tier(SeqRaid.TNA));
    }

    @Test
    void whateverElseTheBackendSendsIsIgnored() {
        GuildRaidProgress progress = GSON.fromJson(
                "{\"schema_version\":2,\"progress\":{\"TNA\":{\"count\":19,\"tier\":\"bronze\",\"streak\":3}}}",
                GuildRaidProgress.class);

        assertEquals(19, progress.count(SeqRaid.TNA));
        assertEquals(SeqTier.BRONZE, progress.tier(SeqRaid.TNA));
    }

    @Test
    void aRaidWithNoEntryReadsAsZero() {
        GuildRaidProgress progress =
                GSON.fromJson("{\"schema_version\":1,\"progress\":{\"TNA\":{\"count\":5}}}", GuildRaidProgress.class);

        assertEquals(0, progress.count(SeqRaid.TWP));
        assertEquals(5, progress.totalCount());
    }

    @Test
    void theTotalFallsBackToTheSumWhenTheBackendOmitsIt() {
        GuildRaidProgress progress = new GuildRaidProgress(1, Map.of("TNA", new Entry(19), "TCC", new Entry(3)));

        assertEquals(22, progress.totalCount());
    }

    @Test
    void raidKeysAreReadWhateverTheirCase() {
        GuildRaidProgress progress = GSON.fromJson(
                "{\"schema_version\":1,\"progress\":{\"tna\":{\"count\":19,\"tier\":\"bronze\"},"
                        + "\"Total\":{\"count\":42,\"tier\":\"silver\"}}}",
                GuildRaidProgress.class);

        assertEquals(19, progress.count(SeqRaid.TNA));
        assertEquals(SeqTier.BRONZE, progress.tier(SeqRaid.TNA));
        assertEquals(42, progress.totalCount());
        assertEquals(SeqTier.SILVER, progress.totalTier());
    }

    @Test
    void aNegativeCountNeverLeaksIntoTheScreen() {
        GuildRaidProgress progress = new GuildRaidProgress(1, Map.of("TNA", new Entry(-4), "TOTAL", new Entry(-9)));

        assertEquals(0, progress.count(SeqRaid.TNA));
        assertEquals(0, progress.totalCount());
    }

    @Test
    void aNegativeBackendTotalFallsBackToTheRaidSum() {
        GuildRaidProgress progress = new GuildRaidProgress(
                1, Map.of("TNA", new Entry(19), "TCC", new Entry(3), "TOTAL", new Entry(-1)));

        assertEquals(22, progress.totalCount());
    }

    @Test
    void missingSectionsDoNotBlowUp() {
        GuildRaidProgress progress = GSON.fromJson("{\"schema_version\":1}", GuildRaidProgress.class);

        assertEquals(0, progress.count(SeqRaid.TNA));
        assertEquals(0, progress.totalCount());
    }

    @Test
    void emptyProgressIsSafeToRender() {
        assertEquals(0, GuildRaidProgress.EMPTY.totalCount());
        assertEquals(0, GuildRaidProgress.EMPTY.count(SeqRaid.NOL));
    }
}
