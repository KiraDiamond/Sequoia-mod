package com.seqwawa.seq.model;

import com.google.gson.annotations.SerializedName;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;

public record GuildRaidProgress(
        @SerializedName("schema_version") int schemaVersion,
        Map<String, Entry> progress,
        List<String> announcements,
        List<Category> categories) {

    public GuildRaidProgress(int schemaVersion, Map<String, Entry> progress, List<String> announcements) {
        this(schemaVersion, progress, announcements, null);
    }

    public GuildRaidProgress(int schemaVersion, Map<String, Entry> progress) {
        this(schemaVersion, progress, List.of());
    }

    private static final String TOTAL_KEY = "TOTAL";

    public static final GuildRaidProgress EMPTY = new GuildRaidProgress(0, Map.of());

    public record Entry(int count, String tier) {

        public Entry(int count) {
            this(count, null);
        }
    }

    /** Sierra owns category order, current rank, targets and completion. Null value means unknown. */
    public record Category(
            String key,
            String kind,
            String label,
            Long value,
            boolean unknown,
            @SerializedName("tier_label") String tierLabel,
            String tier,
            Milestone next,
            boolean completed,
            @SerializedName("max_value") Long maxValue,
            @SerializedName("asset_key") String assetKey) {
        public Category(String key, String kind, String label, Long value, boolean unknown,
                String tierLabel, String tier, Milestone next, boolean completed) {
            this(key, kind, label, value, unknown, tierLabel, tier, next, completed, null, null);
        }
    }

    public record Milestone(String id, String label, long at, String tier) {}

    public GuildRaidProgress {
        progress = normalizeKeys(progress);
        announcements = announcements == null ? List.of() : List.copyOf(announcements);
        // Null means a legacy/count-only response; [] means Sierra hid every category.
        categories = categories == null ? null : List.copyOf(categories);
    }

    public int count(SeqRaid raid) {
        return count(progress.get(raid.code()));
    }

    public SeqTier tier(SeqRaid raid) {
        return tier(progress.get(raid.code()));
    }

    public SeqTier totalTier() {
        return tier(progress.get(TOTAL_KEY));
    }

    public int totalCount() {
        Entry total = progress.get(TOTAL_KEY);
        if (total != null && total.count() >= 0) {
            return total.count();
        }
        int sum = 0;
        for (SeqRaid raid : SeqRaid.values()) {
            sum += count(raid);
        }
        return sum;
    }

    private static int count(Entry entry) {
        return entry == null ? 0 : Math.max(0, entry.count());
    }

    private static SeqTier tier(Entry entry) {
        return entry == null ? null : SeqTier.fromKey(entry.tier());
    }

    private static Map<String, Entry> normalizeKeys(Map<String, Entry> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, Entry> cleaned = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                cleaned.put(key.trim().toUpperCase(Locale.ROOT), value);
            }
        });
        return Map.copyOf(cleaned);
    }
}
