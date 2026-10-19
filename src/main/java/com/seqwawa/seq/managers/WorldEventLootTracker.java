package com.seqwawa.seq.managers;

import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.integrations.WynntilsWorldEventAccess;
import com.seqwawa.seq.integrations.WynntilsWorldStateAccess;
import com.seqwawa.seq.loot.WorldEventLootCapture;
import com.seqwawa.seq.loot.WorldEventLootSample;
import com.seqwawa.seq.loot.WorldEventRewardClassifier;
import com.seqwawa.seq.loot.WorldEventIdentity;
import com.seqwawa.seq.loot.WorldEventLootOutbox;
import com.seqwawa.seq.map.WorldEventService;
import com.seqwawa.seq.network.ApiClient;
import com.seqwawa.seq.network.WynncraftServerPolicy;
import com.seqwawa.seq.utils.PacketTextNormalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

/** Native event-reward packet collection, independent of screen rendering and optional Wynntils UI. */
public final class WorldEventLootTracker {
    private static final WorldEventLootTracker INSTANCE = new WorldEventLootTracker();
    private final WorldEventLootCapture capture = new WorldEventLootCapture();
    private final WorldEventLootOutbox outbox = new WorldEventLootOutbox();
    private final WorldEventIdentity identity = new WorldEventIdentity();
    private Object level;
    private long nextIdentityScan;
    private long nextUpload;
    private boolean uploading;
    private String loggedIdentity;
    private String wynntilsStatus = "not_scanned";
    private List<String> lastNearbyLabels = List.of();

    public static WorldEventLootTracker getInstance() { return INSTANCE; }

    private boolean enabled() {
        return preferenceEnabled() && WynncraftServerPolicy.currentScope() == WynncraftServerPolicy.Scope.MAIN;
    }

    private boolean preferenceEnabled() {
        return SeqClient.collectWorldEventLootSetting != null && SeqClient.collectWorldEventLootSetting.getValue();
    }

    public void tick(Minecraft client) {
        UUID currentAccount = client.getUser().getProfileId();
        if (outbox.update(currentAccount, preferenceEnabled())) {
            capture.reset();
            identity.leave();
            uploading = false;
            nextUpload = 0;
        }
        if (!enabled()) {
            capture.reset();
            identity.leave();
            return;
        }
        if (level != client.level) {
            capture.reset();
            identity.leave();
            level = client.level;
        }
        if (client.player == null || client.level == null) return;
        WorldEventService.getInstance().requestRefresh();
        long now = System.currentTimeMillis();
        if (now >= nextIdentityScan) {
            nextIdentityScan = now + 1000;
            identify(client, now);
        }
        int id = capture.containerId();
        if (id >= 0 && client.player.containerMenu.containerId != id) freeze(id);
        upload(client, now);
    }

    public void onChat(Component message) {
        if (!enabled()) return;
        String text = clean(message.getString());
        if (text.equals("You are now within the event radius")) {
            Minecraft client = Minecraft.getInstance();
            long now = System.currentTimeMillis();
            if (client.player == null) return;
            identify(client, now); // Fresh labels take precedence over a staged pre-entry observation.
            identity.enter(now, client.player.getX(), client.player.getZ());
            logIdentity(now);
        } else if (text.equals("Event Completed")) {
            long now = System.currentTimeMillis();
            // A tick-cached identity avoids depending on another mod's chat injection ordering.
            String state = identity.state(now);
            long sessionAge = identity.sessionAgeMs(now);
            long lastSeenAge = identity.lastSeenAgeMs(now);
            String source = identity.source();
            String eventName = identity.complete(now);
            if (eventName != null) {
                capture.completed(eventName, WynntilsWorldStateAccess.currentWorldName().orElse(null), now);
                SeqClient.LOGGER.info("[WorldEventLoot] Completed event={} source={} sessionAgeMs={} lastSeenAgeMs={}",
                        eventName, source, sessionAge, lastSeenAge);
            } else {
                SeqClient.LOGGER.warn(
                        "[WorldEventLoot] Skipped completion reason={} sessionAgeMs={} lastSeenAgeMs={} wynntils={} catalogCount={} catalogStatus={} nearbyLabels={}",
                        state, sessionAge, lastSeenAge, wynntilsStatus,
                        WorldEventService.getInstance().snapshot().events().size(),
                        WorldEventService.getInstance().status(), lastNearbyLabels);
            }
            logIdentity(now);
        } else if (text.equals("Event Failed") || text.equals("You are no longer within the event radius")
                || text.equals("You did not enter the event radius in time")) {
            identity.leave();
            logIdentity(System.currentTimeMillis());
        }
    }

    private void identify(Minecraft client, long now) {
        if (client.player == null || client.level == null) return;
        List<WorldEventIdentity.Candidate> candidates = new ArrayList<>();
        var wynntils = WynntilsWorldEventAccess.observe();
        wynntilsStatus = wynntils.status();
        if (wynntils.name() != null && !wynntils.name().isBlank()) {
            candidates.add(new WorldEventIdentity.Candidate(wynntils.name(), "wynntils", client.player.getX(), client.player.getZ()));
        }
        // Exact visible world-event name labels provide a native fallback. No proximity-only attribution.
        Set<String> known = new HashSet<>();
        WorldEventService.getInstance().snapshot().events().forEach(event -> known.add(event.name()));
        List<String> nearbyLabels = new ArrayList<>();
        for (var entity : client.level.entitiesForRendering()) {
            double dx = entity.getX() - client.player.getX();
            double dz = entity.getZ() - client.player.getZ();
            if (dx * dx + dz * dz > 64 * 64) continue;
            for (String name : eventLabels(entity)) {
                if (known.contains(name)) {
                    candidates.add(new WorldEventIdentity.Candidate(name, "native_label", entity.getX(), entity.getZ()));
                }
                if (nearbyLabels.size() < 8) nearbyLabels.add(name.length() <= 96 ? name : name.substring(0, 96));
            }
        }
        lastNearbyLabels = List.copyOf(nearbyLabels);
        identity.observe(candidates, now, client.player.getX(), client.player.getZ());
        logIdentity(now);
    }

    private void logIdentity(long now) {
        String summary = identity.description(now) + " wynntils=" + wynntilsStatus;
        if (!Objects.equals(summary, loggedIdentity)) {
            loggedIdentity = summary;
            SeqClient.LOGGER.info("[WorldEventLoot] Identity {} catalogCount={}", summary,
                    WorldEventService.getInstance().snapshot().events().size());
        }
    }

    static List<String> eventLabels(Entity entity) {
        Component text = entity instanceof Display.TextDisplay display ? display.getText() : entity.getCustomName();
        if (text == null) return List.of();
        return text.getString().lines().map(WorldEventLootTracker::clean).filter(line -> !line.isEmpty()).toList();
    }

    public void opened(int id, Component title, MenuType<?> type) {
        if (!enabled()) return;
        if (capture.containerId() >= 0) freeze(capture.containerId());
        capture.opened(id, clean(title.getString()), type == MenuType.GENERIC_9x3, System.currentTimeMillis());
        if ("Event Rewards".equals(clean(title.getString()))) {
            SeqClient.LOGGER.info("[WorldEventLoot] Reward chest opened container={} accepted={} threeRows={}",
                    id, capture.accepts(id), type == MenuType.GENERIC_9x3);
        }
    }

    public void contents(int id, List<ItemStack> items) {
        if (!enabled() || !capture.accepts(id) || items.size() < WorldEventLootCapture.CAPACITY) return;
        List<WorldEventLootSample.Reward> rewards = new ArrayList<>();
        for (int slot = 0; slot < WorldEventLootCapture.CAPACITY; slot++) {
            var reward = reward(slot, items.get(slot));
            if (reward != null) rewards.add(reward);
        }
        capture.contents(id, rewards, System.currentTimeMillis());
    }

    public void slot(int id, int slot, ItemStack stack) {
        if (!enabled() || !capture.accepts(id) || slot < 0 || slot >= WorldEventLootCapture.CAPACITY) return;
        capture.slot(id, slot, reward(slot, stack), System.currentTimeMillis());
    }

    public void freeze(int id) {
        if (!enabled()) return;
        WorldEventLootSample sample = capture.freeze(id, System.currentTimeMillis());
        if (sample == null) return;
        outbox.add(sample);
        SeqClient.LOGGER.info("[WorldEventLoot] Queued chest event={} sample={} complete={} occupied={}/{}",
                sample.eventName(), sample.sampleId(), sample.complete(), sample.rewards().size(), sample.capacity());
    }

    private static WorldEventLootSample.Reward reward(int slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String rawName = stack.getHoverName().getString();
        String name = clean(rawName);
        var itemLore = stack.get(DataComponents.LORE);
        List<String> lore = itemLore == null ? List.of()
                : itemLore.lines().stream().map(line -> clean(line.getString())).toList();
        return new WorldEventLootSample.Reward(slot,
                WorldEventRewardClassifier.category(name, lore, stack.is(Items.POTION), rawName),
                name, stack.getCount(), lore, rawName);
    }

    private void upload(Minecraft client, long now) {
        if (uploading || outbox.first() == null || now < nextUpload || SeqClient.getAuthService() == null) return;
        var session = SeqClient.getAuthService().getCurrentSession();
        // Never upload game observations with a preserved operator/other-account session.
        if (session == null || !outbox.matchesAccount(session.minecraftUuid())) return;
        WorldEventLootSample sample = outbox.first();
        if (sample.completedAt().isBefore(java.time.Instant.now().minusSeconds(23 * 3600))) {
            outbox.remove(sample);
            return;
        }
        uploading = true;
        long requestGeneration = outbox.generation();
        nextUpload = now + 30_000;
        ApiClient.getInstance().recordWorldEventLoot(sample).whenComplete((response, error) -> client.execute(() -> {
            if (requestGeneration != outbox.generation()) return;
            uploading = false;
            Throwable cause = error;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            boolean permanent = cause instanceof ApiClient.ApiException api
                    && api.getStatusCode() >= 400 && api.getStatusCode() < 500
                    && api.getStatusCode() != 401 && api.getStatusCode() != 429;
            if (error == null || permanent) outbox.remove(sample);
            if (error == null) {
                SeqClient.LOGGER.info("[WorldEventLoot] Upload accepted event={} sample={} response={}",
                        sample.eventName(), sample.sampleId(), response);
            } else {
                int status = cause instanceof ApiClient.ApiException api ? api.getStatusCode() : -1;
                SeqClient.LOGGER.warn("[WorldEventLoot] Upload failed event={} sample={} status={} retryable={}",
                        sample.eventName(), sample.sampleId(), status, !permanent);
                SeqClient.LOGGER.debug("[WorldEventLoot] Upload failure details", error);
            }
        }));
    }

    static String clean(String text) {
        return PacketTextNormalizer.normalizeForParsing(text.replaceAll("§#[0-9a-fA-F]{8}", ""));
    }
}
