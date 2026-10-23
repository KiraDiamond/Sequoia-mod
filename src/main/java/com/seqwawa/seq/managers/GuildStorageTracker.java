package com.seqwawa.seq.managers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import com.seqwawa.seq.accessors.NotificationAccessor;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.config.Setting;
import com.seqwawa.seq.network.ConnectionManager;
import com.seqwawa.seq.utils.PacketTextNormalizer;

/**
 * Tracks guild reward storage snapshots from the open guild menu and keeps
 * emerald/aspect totals updated locally via raid announcements after seed.
 */
public final class GuildStorageTracker implements NotificationAccessor {
    static final long REWARD_BURST_IDLE_GAP_MS = 2_000L;
    static final long REWARD_BURST_MAX_WINDOW_MS = 30_000L;
    static final long REWARD_PACKET_OBSERVATION_WINDOW_MS = 2_000L;
    static final long LOCAL_SNAPSHOT_AUTHORITY_WINDOW_MS = 5_000L;
    static final long STORAGE_SNAPSHOT_PUBLISH_INTERVAL_MS = 1_000L;
    private static final int MAX_RECENT_REWARD_OBSERVATIONS = 256;
    private static final Pattern EMERALDS_PATTERN =
            Pattern.compile("(?i)^emeralds:\\s*([\\d, ]+)\\s*/\\s*([\\d, ]+)$");
    private static final Pattern ASPECTS_PATTERN =
            Pattern.compile("(?i)^aspects:\\s*([\\d, ]+)\\s*/\\s*([\\d, ]+)$");
    private static final Pattern REWARD_GRANT_PATTERN = Pattern.compile(
            "^(?<sender>.+?)\\s+rewarded\\s+(?:(?<emeraldAmount>[\\d, ]+)\\s+Emeralds?|(?<aspectAmount>an?|[\\d, ]+)\\s+Aspects?)\\s+to\\s+(?<recipient>.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final String REWARDS_UNAVAILABLE = "rewards are unavailable";

    private static GuildStorageTracker instance;

    private final Consumer<StorageSnapshot> snapshotPublisher;
    private final Consumer<RewardBurst> rewardPublisher;
    private final Consumer<String> notificationSender;
    private final BooleanSupplier trackingEnabled;
    private final IntSupplier emeraldThresholdPercent;
    private final IntSupplier aspectThresholdPercent;
    private final LongSupplier clockMsSupplier;
    private final RewardPacketObservationTracker rewardPacketObservationTracker;

    private StorageSnapshot currentSnapshot;
    private StorageSnapshot lastPublishedSnapshot;
    private long lastObservedSnapshotAtMs;
    private long lastPublishedSnapshotAtMs;
    private StorageSnapshot pendingSnapshot;
    private final Map<RewardBurstKey, RewardBurstAccumulator> pendingRewardBursts = new LinkedHashMap<>();

    public static synchronized GuildStorageTracker getInstance() {
        if (instance == null) {
            instance = new GuildStorageTracker(
                    snapshot -> ConnectionManager.getInstance().sendGuildStorageSnapshot(
                            snapshot.emeralds().current(),
                            snapshot.emeralds().max(),
                            snapshot.aspects().current(),
                            snapshot.aspects().max()),
                    reward -> ConnectionManager.getInstance().sendGuildStorageReward(
                            reward.senderUsername(),
                            reward.recipientUsername(),
                            reward.resourceType().wireValue(),
                            reward.amount(),
                            reward.count(),
                            reward.windowStartedAt()),
                    NotificationAccessor::notifyPlayer,
                    () -> {
                        Setting.BooleanSetting setting = SeqClient.getTrackGuildStorageSetting();
                        return setting == null || setting.getValue();
                    },
                    () -> {
                        Setting.IntSetting setting = SeqClient.getGuildStorageEmeraldNotifyValueSetting();
                        return setting == null ? 100 : setting.getValue();
                    },
                    () -> {
                        Setting.IntSetting setting = SeqClient.getGuildStorageAspectNotifyValueSetting();
                        return setting == null ? 100 : setting.getValue();
                    },
                    System::currentTimeMillis);
        }
        return instance;
    }

    GuildStorageTracker(
            Consumer<StorageSnapshot> snapshotPublisher,
            Consumer<RewardBurst> rewardPublisher,
            Consumer<String> notificationSender,
            BooleanSupplier trackingEnabled,
            IntSupplier emeraldThresholdPercent,
            IntSupplier aspectThresholdPercent,
            LongSupplier clockMsSupplier) {
        this.snapshotPublisher = Objects.requireNonNull(snapshotPublisher, "snapshotPublisher");
        this.rewardPublisher = Objects.requireNonNull(rewardPublisher, "rewardPublisher");
        this.notificationSender = Objects.requireNonNull(notificationSender, "notificationSender");
        this.trackingEnabled = Objects.requireNonNull(trackingEnabled, "trackingEnabled");
        this.emeraldThresholdPercent = Objects.requireNonNull(emeraldThresholdPercent, "emeraldThresholdPercent");
        this.aspectThresholdPercent = Objects.requireNonNull(aspectThresholdPercent, "aspectThresholdPercent");
        this.clockMsSupplier = Objects.requireNonNull(clockMsSupplier, "clockMsSupplier");
        this.rewardPacketObservationTracker = new RewardPacketObservationTracker(clockMsSupplier);
    }

    public void tick() {
        if (!trackingEnabled.getAsBoolean()) {
            clearPendingSnapshot();
            return;
        }

        if (!ConnectionManager.isConnected()) {
            lastPublishedSnapshot = null;
            clearPendingSnapshot();
        }

        flushReadyRewardBursts(false);
        flushPendingSnapshotIfReady(false, ConnectionManager.isConnected());

        StorageSnapshot observed = observeCurrentSnapshot();
        if (observed == null) {
            return;
        }

        applyObservedSnapshot(observed);
    }

    public void onSystemChat(Component message) {
        if (!trackingEnabled.getAsBoolean() || message == null) {
            return;
        }
        if (SeqClient.mc != null && !SeqClient.mc.isSameThread()) {
            return;
        }

        RewardMessageDescriptor descriptor = RewardMessageDescriptor.describe(message);
        RewardPacketObservation observation = null;
        if (descriptor != null) {
            observation = rewardPacketObservationTracker.observe(descriptor);
            logObservedRewardPacket(observation);
        }

        RaidTracker.ParsedRaidCompletion completion = RaidTracker.parseRaidCompletion(message);
        if (completion != null) {
            applyStorageDelta(completion.emeralds(), completion.aspects());
            return;
        }

        RewardGrant rewardGrant = parseRewardGrant(message);
        if (rewardGrant == null) {
            return;
        }

        StorageSnapshot before = currentSnapshot;
        applyStorageDelta(rewardGrant.emeraldDelta(), rewardGrant.aspectDelta());
        recordRewardGrant(rewardGrant);
        StorageSnapshot after = currentSnapshot;
        SeqClient.LOGGER.debug(
                "[GuildStorage] Reward grant parsed fingerprint={} occurrenceCount={} deltaSinceLastMs={} sender='{}' recipient='{}' resource='{}' amount={} seeded={} normalized='{}' before={} after={} burstCount={}",
                observation != null ? observation.fingerprint() : "unknown",
                observation != null ? observation.occurrenceCount() : 0,
                observation != null ? observation.deltaSinceLastMs() : -1,
                rewardGrant.senderUsername(),
                rewardGrant.recipientUsername(),
                rewardGrant.resourceType().wireValue(),
                rewardGrant.amount(),
                before != null,
                descriptor != null ? abbreviateForLog(descriptor.normalizedText()) : "unknown",
                formatSnapshot(before),
                formatSnapshot(after),
                currentBurstCount(rewardGrant));
    }

    public void reset() {
        flushReadyRewardBursts(true);
        currentSnapshot = null;
        lastPublishedSnapshot = null;
        lastPublishedSnapshotAtMs = 0L;
        lastObservedSnapshotAtMs = 0L;
        clearPendingSnapshot();
        pendingRewardBursts.clear();
    }

    void applyObservedSnapshot(StorageSnapshot snapshot) {
        lastObservedSnapshotAtMs = clockMsSupplier.getAsLong();
        applySnapshot(snapshot, true);
    }

    public void applyRemoteSnapshot(long emeraldCurrent, long emeraldMax, long aspectCurrent, long aspectMax) {
        StorageSnapshot snapshot = new StorageSnapshot(
                new ResourceSnapshot(emeraldCurrent, emeraldMax),
                new ResourceSnapshot(aspectCurrent, aspectMax));
        if (shouldIgnoreRemoteSnapshot(snapshot)) {
            SeqClient.LOGGER.info(
                    "[GuildStorage] Ignoring remote snapshot emerald={}/{} aspect={}/{} due to recent local GUI observation",
                    emeraldCurrent,
                    emeraldMax,
                    aspectCurrent,
                    aspectMax);
            return;
        }
        applySnapshot(snapshot, false);
    }

    private void applySnapshot(StorageSnapshot snapshot, boolean publishUpdate) {
        if (snapshot == null) {
            return;
        }

        StorageSnapshot previous = currentSnapshot;
        currentSnapshot = snapshot;

        if (previous != null && !previous.equals(snapshot)) {
            maybeNotifyThresholdCrossing(previous, snapshot, ResourceType.EMERALDS, emeraldThresholdPercent.getAsInt());
            maybeNotifyThresholdCrossing(previous, snapshot, ResourceType.ASPECTS, aspectThresholdPercent.getAsInt());
        }

        if (publishUpdate) {
            publishSnapshotIfNeeded(snapshot, ConnectionManager.isConnected());
        }
    }

    void applyRaidRewards(long emeraldDelta, long aspectDelta) {
        applyStorageDelta(emeraldDelta, aspectDelta);
    }

    void applyStorageDelta(long emeraldDelta, long aspectDelta) {
        if (currentSnapshot == null) {
            return;
        }
        if (emeraldDelta == 0 && aspectDelta == 0) {
            return;
        }

        ResourceSnapshot nextEmeralds = currentSnapshot.emeralds().addClampedToZero(emeraldDelta);
        ResourceSnapshot nextAspects = currentSnapshot.aspects().addClampedToZero(aspectDelta);
        logDepletionOvershoot(ResourceType.EMERALDS, currentSnapshot.emeralds(), emeraldDelta, nextEmeralds);
        logDepletionOvershoot(ResourceType.ASPECTS, currentSnapshot.aspects(), aspectDelta, nextAspects);

        StorageSnapshot next = new StorageSnapshot(
                nextEmeralds,
                nextAspects);
        StorageSnapshot previous = currentSnapshot;
        currentSnapshot = next;

        maybeNotifyThresholdCrossing(previous, next, ResourceType.EMERALDS, emeraldThresholdPercent.getAsInt());
        maybeNotifyThresholdCrossing(previous, next, ResourceType.ASPECTS, aspectThresholdPercent.getAsInt());
    }

    private void logDepletionOvershoot(
            ResourceType resourceType, ResourceSnapshot before, long attemptedDelta, ResourceSnapshot after) {
        if (before == null || attemptedDelta >= 0) {
            return;
        }
        if (before.current() + attemptedDelta >= 0) {
            return;
        }

        SeqClient.LOGGER.warn(
                "[GuildStorage][WARN] %s reward burst would overshoot tracked storage before=%d/%d attemptedDelta=%d after=%d/%d",
                resourceType.wireValue(),
                before.current(),
                before.max(),
                attemptedDelta,
                after.current(),
                after.max());
    }

    StorageSnapshot currentSnapshot() {
        return currentSnapshot;
    }

    static StorageSnapshot observeCurrentSnapshot() {
        if (SeqClient.mc == null) {
            return null;
        }
        Screen screen = SeqClient.mc.screen;
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return null;
        }
        return extractSnapshot(containerScreen.getMenu()).orElse(null);
    }

    static Optional<StorageSnapshot> extractSnapshot(AbstractContainerMenu menu) {
        if (menu == null) {
            return Optional.empty();
        }

        for (Slot slot : menu.slots) {
            if (slot == null || !slot.hasItem()) {
                continue;
            }
            StorageSnapshot snapshot = parseSnapshot(slot.getItem());
            if (snapshot != null) {
                return Optional.of(snapshot);
            }
        }

        return Optional.empty();
    }

    public static OptionalLong extractCurrentEmeralds(AbstractContainerMenu menu) {
        return extractSnapshot(menu).map(snapshot -> OptionalLong.of(snapshot.emeralds().current()))
                .orElseGet(OptionalLong::empty);
    }

    static StorageSnapshot parseSnapshot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) {
            return null;
        }

        return parseSnapshotLines(lore.lines().stream().map(Component::getString).toList());
    }

    static StorageSnapshot parseSnapshotLines(List<String> rawLines) {
        if (rawLines == null || rawLines.isEmpty()) {
            return null;
        }

        ResourceSnapshot emeralds = null;
        ResourceSnapshot aspects = null;

        for (String rawLine : rawLines) {
            String line = normalizeLine(rawLine);
            if (line.isEmpty()) {
                continue;
            }
            if (line.toLowerCase().contains(REWARDS_UNAVAILABLE)) {
                return null;
            }

            Matcher emeraldMatcher = EMERALDS_PATTERN.matcher(line);
            if (emeraldMatcher.matches()) {
                emeralds = new ResourceSnapshot(parseNumber(emeraldMatcher.group(1)), parseNumber(emeraldMatcher.group(2)));
                continue;
            }

            Matcher aspectMatcher = ASPECTS_PATTERN.matcher(line);
            if (aspectMatcher.matches()) {
                aspects = new ResourceSnapshot(parseNumber(aspectMatcher.group(1)), parseNumber(aspectMatcher.group(2)));
            }
        }

        if (emeralds == null || aspects == null || emeralds.max() <= 0 || aspects.max() <= 0) {
            return null;
        }

        return new StorageSnapshot(emeralds, aspects);
    }

    static RewardGrant parseRewardGrant(Component message) {
        if (message == null) {
            return null;
        }

        PacketNameResolver resolver = PacketNameResolver.from(message);
        String normalized = resolver.text();
        if (!normalized.toLowerCase(Locale.ROOT).contains(" rewarded ")
                || !normalized.toLowerCase(Locale.ROOT).contains(" to ")) {
            return null;
        }

        Matcher matcher = REWARD_GRANT_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }

        String senderDisplayed = matcher.group("sender");
        String recipientDisplayed = matcher.group("recipient");
        String senderUsername = resolver.resolve(matcher.start("sender"), matcher.end("sender"), PacketNameResolver.Policy.CHAT, "reward.sender").username();
        String recipientUsername =
                resolver.resolve(matcher.start("recipient"), matcher.end("recipient"), PacketNameResolver.Policy.CHAT, "reward.recipient").username();
        if (senderUsername == null || recipientUsername == null) {
            return null;
        }

        String emeraldAmount = matcher.group("emeraldAmount");
        if (emeraldAmount != null) {
            return new RewardGrant(senderUsername, recipientUsername, ResourceType.EMERALDS, parseNumber(emeraldAmount));
        }

        String aspectAmount = matcher.group("aspectAmount");
        long amount = parseAspectAmount(aspectAmount);
        if (amount <= 0) {
            return null;
        }
        return new RewardGrant(senderUsername, recipientUsername, ResourceType.ASPECTS, amount);
    }

    void publishSnapshotIfNeeded(StorageSnapshot snapshot, boolean connected) {
        if (!connected) {
            clearPendingSnapshot();
            return;
        }
        if (!pendingRewardBursts.isEmpty()) {
            pendingSnapshot = snapshot;
            return;
        }
        if (snapshot.equals(lastPublishedSnapshot)) {
            if (snapshot.equals(pendingSnapshot)) {
                pendingSnapshot = null;
            }
            return;
        }

        long now = clockMsSupplier.getAsLong();
        if (lastPublishedSnapshot == null
                || now - lastPublishedSnapshotAtMs >= STORAGE_SNAPSHOT_PUBLISH_INTERVAL_MS) {
            publishSnapshotNow(snapshot, now);
            return;
        }

        pendingSnapshot = snapshot;
    }

    void flushPendingSnapshotIfReady(boolean force, boolean connected) {
        if (pendingSnapshot == null) {
            return;
        }
        if (!connected) {
            clearPendingSnapshot();
            return;
        }
        if (!pendingRewardBursts.isEmpty()) {
            return;
        }

        long now = clockMsSupplier.getAsLong();
        if (!force && now - lastPublishedSnapshotAtMs < STORAGE_SNAPSHOT_PUBLISH_INTERVAL_MS) {
            return;
        }

        StorageSnapshot snapshot = pendingSnapshot;
        pendingSnapshot = null;
        if (!snapshot.equals(lastPublishedSnapshot)) {
            publishSnapshotNow(snapshot, now);
        }
    }

    private void publishSnapshotNow(StorageSnapshot snapshot, long now) {
        snapshotPublisher.accept(snapshot);
        lastPublishedSnapshot = snapshot;
        lastPublishedSnapshotAtMs = now;
        pendingSnapshot = null;
    }

    private void clearPendingSnapshot() {
        pendingSnapshot = null;
    }

    private void recordRewardGrant(RewardGrant rewardGrant) {
        long now = clockMsSupplier.getAsLong();
        RewardBurstKey key = new RewardBurstKey(
                rewardGrant.senderUsername(), rewardGrant.recipientUsername(), rewardGrant.resourceType(), rewardGrant.amount());
        RewardBurstAccumulator existing = pendingRewardBursts.get(key);
        if (existing == null) {
            pendingRewardBursts.put(key, new RewardBurstAccumulator(key, now));
            return;
        }

        if (existing.shouldStartNewBurst(now)) {
            publishRewardBurst(existing);
            pendingRewardBursts.put(key, new RewardBurstAccumulator(key, now));
            return;
        }

        existing.increment(now);
    }

    void flushReadyRewardBursts(boolean force) {
        if (pendingRewardBursts.isEmpty()) {
            return;
        }

        long now = clockMsSupplier.getAsLong();
        List<RewardBurstKey> keysToFlush = new ArrayList<>();
        for (Map.Entry<RewardBurstKey, RewardBurstAccumulator> entry : pendingRewardBursts.entrySet()) {
            if (force || entry.getValue().isReadyToFlush(now)) {
                keysToFlush.add(entry.getKey());
            }
        }

        for (RewardBurstKey key : keysToFlush) {
            RewardBurstAccumulator burst = pendingRewardBursts.remove(key);
            if (burst != null) {
                publishRewardBurst(burst);
            }
        }
    }

    private void publishRewardBurst(RewardBurstAccumulator burst) {
        // The backend applies rewards as deltas. Its previously seeded snapshot
        // is no longer authoritative until a later local observation follows.
        lastPublishedSnapshot = null;
        rewardPublisher.accept(burst.toRewardBurst());
    }

    private void logObservedRewardPacket(RewardPacketObservation observation) {
        String levelPrefix = observation.occurrenceCount() > 1 ? "[GuildStorage][WARN]" : "[GuildStorage]";
        String logMessage =
                "%s Reward candidate observed fingerprint=%s occurrenceCount=%d deltaSinceLastMs=%d parsed=%s sender='%s' recipient='%s' resource='%s' amount=%d normalized='%s'"
                        .formatted(
                                levelPrefix,
                                observation.fingerprint(),
                                observation.occurrenceCount(),
                                observation.deltaSinceLastMs(),
                                observation.parsedRewardKey() != null,
                                observation.parsedRewardKey() != null ? observation.parsedRewardKey().senderUsername() : "",
                                observation.parsedRewardKey() != null ? observation.parsedRewardKey().recipientUsername() : "",
                                observation.parsedRewardKey() != null
                                        ? observation.parsedRewardKey().resourceType().wireValue()
                                        : "",
                                observation.parsedRewardKey() != null ? observation.parsedRewardKey().amount() : 0L,
                                abbreviateForLog(observation.normalizedText()));
        if (observation.occurrenceCount() > 1) {
            SeqClient.LOGGER.warn(logMessage);
            return;
        }
        SeqClient.LOGGER.debug(logMessage);
    }

    private int currentBurstCount(RewardGrant rewardGrant) {
        RewardBurstAccumulator accumulator = pendingRewardBursts.get(new RewardBurstKey(
                rewardGrant.senderUsername(),
                rewardGrant.recipientUsername(),
                rewardGrant.resourceType(),
                rewardGrant.amount()));
        return accumulator == null ? 0 : accumulator.count;
    }

    private void maybeNotifyThresholdCrossing(
            StorageSnapshot previous, StorageSnapshot current, ResourceType resourceType, int thresholdPercent) {
        if (thresholdPercent < 0) {
            return;
        }

        ResourceSnapshot previousResource = previous.resourceFor(resourceType);
        ResourceSnapshot currentResource = current.resourceFor(resourceType);
        if (previousResource == null || currentResource == null || previousResource.max() <= 0 || currentResource.max() <= 0) {
            return;
        }

        boolean wasAboveThreshold = previousResource.fractionFilled() >= (thresholdPercent / 100.0);
        boolean isAboveThreshold = currentResource.fractionFilled() >= (thresholdPercent / 100.0);
        if (wasAboveThreshold || !isAboveThreshold) {
            return;
        }

        notificationSender.accept("%s storage reached %d%% (%d/%d)."
                .formatted(
                        resourceType.displayName(),
                        thresholdPercent,
                        currentResource.current(),
                        currentResource.max()));
    }

    private boolean shouldIgnoreRemoteSnapshot(StorageSnapshot snapshot) {
        if (snapshot == null || currentSnapshot == null || currentSnapshot.equals(snapshot)) {
            return false;
        }
        long ageMs = clockMsSupplier.getAsLong() - lastObservedSnapshotAtMs;
        return ageMs >= 0 && ageMs <= LOCAL_SNAPSHOT_AUTHORITY_WINDOW_MS;
    }

    private static String normalizeLine(String rawLine) {
        return PacketTextNormalizer.normalizeForParsing(rawLine);
    }

    private static long parseNumber(String rawValue) {
        return Long.parseLong(rawValue.replace(",", "").replace(" ", "").trim());
    }

    private static long parseAspectAmount(String rawValue) {
        if (rawValue == null) {
            return 0;
        }
        String trimmed = rawValue.trim().toLowerCase(Locale.ROOT);
        if (trimmed.equals("a") || trimmed.equals("an")) {
            return 1;
        }
        return parseNumber(rawValue);
    }

    private static String formatSnapshot(StorageSnapshot snapshot) {
        if (snapshot == null) {
            return "unseeded";
        }
        return "emeralds=%d/%d aspects=%d/%d"
                .formatted(
                        snapshot.emeralds().current(),
                        snapshot.emeralds().max(),
                        snapshot.aspects().current(),
                        snapshot.aspects().max());
    }

    private static String abbreviateForLog(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 512 ? value : value.substring(0, 512) + "...";
    }

    private static String extractFragmentText(Component fragment) {
        if (fragment.getContents() instanceof PlainTextContents plainTextContents) {
            return plainTextContents.text();
        }
        return fragment.getString();
    }

    enum ResourceType {
        EMERALDS("Emeralds", "emeralds"),
        ASPECTS("Aspects", "aspects");

        private final String displayName;
        private final String wireValue;

        ResourceType(String displayName, String wireValue) {
            this.displayName = displayName;
            this.wireValue = wireValue;
        }

        String displayName() {
            return displayName;
        }

        String wireValue() {
            return wireValue;
        }
    }

    record ResourceSnapshot(long current, long max) {
        ResourceSnapshot add(long delta) {
            return new ResourceSnapshot(current + delta, max);
        }

        ResourceSnapshot addClampedToZero(long delta) {
            return new ResourceSnapshot(Math.max(0L, current + delta), max);
        }

        double fractionFilled() {
            if (max <= 0) {
                return 0;
            }
            return (double) current / (double) max;
        }
    }

    record StorageSnapshot(ResourceSnapshot emeralds, ResourceSnapshot aspects) {
        ResourceSnapshot resourceFor(ResourceType resourceType) {
            return switch (resourceType) {
                case EMERALDS -> emeralds;
                case ASPECTS -> aspects;
            };
        }
    }

    record RewardGrant(String senderUsername, String recipientUsername, ResourceType resourceType, long amount) {
        long emeraldDelta() {
            return resourceType == ResourceType.EMERALDS ? -amount : 0;
        }

        long aspectDelta() {
            return resourceType == ResourceType.ASPECTS ? -amount : 0;
        }
    }

    private record RewardBurstKey(String senderUsername, String recipientUsername, ResourceType resourceType, long amount) {}

    record RewardBurst(
            String senderUsername,
            String recipientUsername,
            ResourceType resourceType,
            long amount,
            int count,
            Instant windowStartedAt) {}

    record RewardPacketObservation(
            String fingerprint,
            String normalizedText,
            RewardGrant parsedRewardKey,
            int occurrenceCount,
            long firstSeenAtMs,
            long lastSeenAtMs,
            long deltaSinceLastMs) {}

    private static final class RewardBurstAccumulator {
        private final RewardBurstKey key;
        private final long windowStartedAtMs;
        private long lastSeenAtMs;
        private int count;

        private RewardBurstAccumulator(RewardBurstKey key, long now) {
            this.key = key;
            this.windowStartedAtMs = now;
            this.lastSeenAtMs = now;
            this.count = 1;
        }

        private boolean shouldStartNewBurst(long now) {
            return now - lastSeenAtMs > REWARD_BURST_IDLE_GAP_MS || now - windowStartedAtMs >= REWARD_BURST_MAX_WINDOW_MS;
        }

        private boolean isReadyToFlush(long now) {
            return now - lastSeenAtMs > REWARD_BURST_IDLE_GAP_MS || now - windowStartedAtMs >= REWARD_BURST_MAX_WINDOW_MS;
        }

        private void increment(long now) {
            lastSeenAtMs = now;
            count++;
        }

        private RewardBurst toRewardBurst() {
            return new RewardBurst(
                    key.senderUsername(),
                    key.recipientUsername(),
                    key.resourceType(),
                    key.amount(),
                    count,
                    Instant.ofEpochMilli(windowStartedAtMs));
        }
    }

    static final class RewardPacketObservationTracker {
        private final LongSupplier clockMsSupplier;
        private final Map<String, RewardPacketObservationAccumulator> recentObservations = new LinkedHashMap<>();

        RewardPacketObservationTracker(LongSupplier clockMsSupplier) {
            this.clockMsSupplier = Objects.requireNonNull(clockMsSupplier, "clockMsSupplier");
        }

        RewardPacketObservation observe(RewardMessageDescriptor descriptor) {
            long now = clockMsSupplier.getAsLong();
            evictExpired(now);

            RewardPacketObservationAccumulator existing = recentObservations.get(descriptor.key());
            if (existing == null) {
                recentObservations.put(descriptor.key(), new RewardPacketObservationAccumulator(descriptor, now));
                trimToCapacity();
                return recentObservations.get(descriptor.key()).snapshot();
            }

            existing.observe(now);
            return existing.snapshot();
        }

        RewardPacketObservation lookup(RewardMessageDescriptor descriptor) {
            long now = clockMsSupplier.getAsLong();
            evictExpired(now);
            RewardPacketObservationAccumulator existing = recentObservations.get(descriptor.key());
            return existing == null ? null : existing.snapshot();
        }

        private void evictExpired(long now) {
            Iterator<Map.Entry<String, RewardPacketObservationAccumulator>> iterator = recentObservations.entrySet()
                    .iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, RewardPacketObservationAccumulator> entry = iterator.next();
                if (now - entry.getValue().lastSeenAtMs > REWARD_PACKET_OBSERVATION_WINDOW_MS) {
                    iterator.remove();
                }
            }
        }

        private void trimToCapacity() {
            while (recentObservations.size() > MAX_RECENT_REWARD_OBSERVATIONS) {
                Iterator<String> iterator = recentObservations.keySet().iterator();
                if (!iterator.hasNext()) {
                    return;
                }
                iterator.next();
                iterator.remove();
            }
        }
    }

    record RewardMessageDescriptor(String key, String fingerprint, String normalizedText, RewardGrant parsedRewardKey) {
        static RewardMessageDescriptor describe(Component message) {
            if (message == null) {
                return null;
            }

            String normalizedText = PacketTextNormalizer.normalizeForParsing(message.getString());
            RewardGrant parsedRewardKey = parseRewardGrant(message);
            boolean rewardCandidate = parsedRewardKey != null
                    || (normalizedText.toLowerCase(Locale.ROOT).contains(" rewarded ")
                            && normalizedText.toLowerCase(Locale.ROOT).contains(" to "));
            if (!rewardCandidate) {
                return null;
            }

            StringBuilder canonical = new StringBuilder(normalizedText.length() + 64);
            canonical.append(normalizedText);
            for (Component fragment : message.toFlatList()) {
                String fragmentText = extractFragmentText(fragment);
                if (fragmentText.isEmpty()) {
                    continue;
                }
                Style style = fragment.getStyle();
                canonical.append("|text=").append(PacketTextNormalizer.normalizeForParsing(fragmentText));
                canonical.append("|hover=").append(PacketNameResolver.extractHoverRealUsername(style));
                canonical.append("|insertion=").append(PacketNameResolver.extractInsertionUsername(style));
                canonical.append("|color=")
                        .append(style.getColor() != null ? style.getColor().getValue() : "none");
            }

            String key = canonical.toString();
            String fingerprint = Integer.toHexString(key.hashCode()) + ":" + key.length();
            return new RewardMessageDescriptor(key, fingerprint, normalizedText, parsedRewardKey);
        }
    }

    private static final class RewardPacketObservationAccumulator {
        private final RewardMessageDescriptor descriptor;
        private final long firstSeenAtMs;
        private long lastSeenAtMs;
        private long deltaSinceLastMs;
        private int occurrenceCount;

        private RewardPacketObservationAccumulator(RewardMessageDescriptor descriptor, long now) {
            this.descriptor = descriptor;
            this.firstSeenAtMs = now;
            this.lastSeenAtMs = now;
            this.occurrenceCount = 1;
            this.deltaSinceLastMs = 0L;
        }

        private void observe(long now) {
            deltaSinceLastMs = now - lastSeenAtMs;
            lastSeenAtMs = now;
            occurrenceCount++;
        }

        private RewardPacketObservation snapshot() {
            return new RewardPacketObservation(
                    descriptor.fingerprint(),
                    descriptor.normalizedText(),
                    descriptor.parsedRewardKey(),
                    occurrenceCount,
                    firstSeenAtMs,
                    lastSeenAtMs,
                    deltaSinceLastMs);
        }
    }
}
