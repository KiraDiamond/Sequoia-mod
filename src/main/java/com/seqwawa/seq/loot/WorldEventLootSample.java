package com.seqwawa.seq.loot;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorldEventLootSample(
        @SerializedName("schema_version") int schemaVersion,
        @SerializedName("sample_id") UUID sampleId,
        @SerializedName("event_name") String eventName,
        String world,
        @SerializedName("completed_at") Instant completedAt,
        boolean complete,
        int capacity,
        @SerializedName("loot_bonus") Integer lootBonus,
        @SerializedName("loot_quality") Integer lootQuality,
        List<Reward> rewards) {
    public record Reward(int slot, String category, String name, int quantity, List<String> lore,
                         @SerializedName("raw_name") String rawName) {
        public Reward(int slot, String category, String name, int quantity, List<String> lore) {
            this(slot, category, name, quantity, lore, name);
        }
        public Reward {
            lore = List.copyOf(lore);
        }
    }
}
