package com.seqwawa.seq.images;

import com.seqwawa.seq.client.SeqClient;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Previews keyed by chat link. Owned by the render thread; workers only download and decode.
 *
 * <p>Separate budgets bound compressed frames of ready previews, bytes held by loads in progress and
 * GPU atlases. The preview on screen is never evicted; others go least recently used first.
 */
final class PreviewCache {
    static final long RETAINED_BYTES = 64L * 1024 * 1024;
    static final long IN_FLIGHT_BYTES = 96L * 1024 * 1024;
    static final long TEXTURE_BYTES = 96L * 1024 * 1024;
    static final int MAX_ENTRIES = 32;
    static final long ACTIVE_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final long IDLE_TEXTURE_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** Downloads and decodes one media URL on a worker thread; firstFrame gets frames that can be shown early. */
    @FunctionalInterface
    interface Loader {
        MediaFrames load(
                URI media, MediaCancellation cancellation, ByteBudget.Lease lease, Consumer<MediaFrames> firstFrame)
                throws Exception;
    }

    /** One link's preview and the resources it owns. */
    static final class Preview {
        final String key;
        final URI link;
        final URI source;
        private PreviewState state = new PreviewState.Loading();
        private MediaCancellation cancellation;
        private FutureTask<Void> task;
        private PreviewPlayer player;
        private long lastUsed;
        // Bytes charged to the retained budget; frames still arriving count as in flight.
        private long retained;

        Preview(String key, URI link, URI source, long now) {
            this.key = key;
            this.link = link;
            this.source = source;
            this.lastUsed = now;
        }

        String status(long now) {
            return PreviewState.status(state, now);
        }

        /** Frames to show, possibly still arriving; null until the first frame exists. */
        MediaFrames frames() {
            return state instanceof PreviewState.Ready ready ? ready.frames() : null;
        }

        boolean active(long now) {
            return now - lastUsed < ACTIVE_NANOS;
        }

        /** @return bytes now charged to the retained budget */
        private long complete(MediaFrames frames) {
            task = null;
            state = new PreviewState.Ready(frames);
            retained = frames.bytes();
            return retained;
        }

        /** @return the player to retire, if any */
        private PreviewPlayer fail(PreviewState failure) {
            task = null;
            state = failure;
            return detachPlayer();
        }

        /** Cancels any work and forgets the media; returns the retained bytes released. */
        private long release(ThreadPoolExecutor workers) {
            if (cancellation != null) cancellation.cancel();
            if (task != null) {
                task.cancel(true);
                workers.remove(task);
                task = null;
            }
            state = new PreviewState.Failed(PreviewState.BLOCKED);
            long freed = retained;
            retained = 0;
            return freed;
        }

        private PreviewPlayer detachPlayer() {
            var detached = player;
            player = null;
            return detached;
        }
    }

    private final LinkedHashMap<String, Preview> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final List<PreviewPlayer> retiredPlayers = new ArrayList<>();
    private final ThreadPoolExecutor workers;
    private final Executor mainThread;
    private final Loader loader;
    private final ByteBudget inFlight = new ByteBudget(IN_FLIGHT_BYTES);
    private final Set<ByteBudget.Lease> postedLeases = ConcurrentHashMap.newKeySet();
    private final LongSupplier clock;
    // The only preview trim() keeps over the limits; at most one preview is drawn per frame.
    private Preview shown;
    private long retainedBytes;
    private long textureBytes;

    /** {@code mainThread} runs load results on the render thread; {@code clock} is monotonic nanoseconds. */
    PreviewCache(ThreadPoolExecutor workers, Executor mainThread, Loader loader, LongSupplier clock) {
        this.workers = workers;
        this.mainThread = mainThread;
        this.loader = loader;
        this.clock = clock;
    }

    long now() {
        return clock.getAsLong();
    }

    Preview get(URI link) {
        long now = now();
        String key = link.toString();
        URI source = BridgeMedia.resolve(link);
        Preview found = entries.get(key);
        if (found != null && source.equals(found.source)) {
            use(found, now);
            return found;
        }
        // A delayed signed Discord URL changes the source; replace the stale preview.
        if (found != null) evict(found);
        var preview = new Preview(key, link, source, now);
        entries.put(key, preview);
        use(preview, now);
        load(preview);
        trim(now);
        return preview;
    }

    private void load(Preview preview) {
        var cancellation = new MediaCancellation();
        var task = new FutureTask<Void>(() -> run(preview, cancellation), null);
        preview.task = task;
        preview.cancellation = cancellation;
        try {
            workers.execute(task);
        } catch (RejectedExecutionException busy) {
            retire(preview.fail(new PreviewState.Failed(PreviewState.BUSY)));
        }
    }

    private void run(Preview preview, MediaCancellation cancellation) {
        var lease = inFlight.open();
        boolean posted = false;
        try {
            MediaFrames frames = null;
            Throwable failure = null;
            try {
                cancellation.check();
                frames = loader.load(
                        preview.source,
                        cancellation,
                        lease,
                        first -> post(() -> {
                            if (!cancellation.isCancelled() && preview.state instanceof PreviewState.Loading)
                                preview.state = new PreviewState.Ready(first);
                        }));
                cancellation.check();
            } catch (Exception | Error error) { // An Error must still end the preview, not leave it loading.
                failure = error;
            }
            // Register before checking cancellation so a concurrent clear() cannot miss the lease.
            postedLeases.add(lease);
            if (cancellation.isCancelled()) return;
            var loaded = frames;
            var error = failure;
            // A posted result stays charged until the render thread installs it, or until the
            // next clear() if the client drops it while disconnecting.
            posted = post(() -> finish(preview, cancellation, loaded, error, lease));
        } finally {
            if (!posted) {
                postedLeases.remove(lease);
                lease.close();
            }
        }
    }

    private void use(Preview preview, long now) {
        preview.lastUsed = now;
        shown = preview;
    }

    private boolean post(Runnable task) {
        try {
            mainThread.execute(task);
            return true;
        } catch (RejectedExecutionException stopping) {
            return false;
        }
    }

    private void finish(
            Preview preview,
            MediaCancellation cancellation,
            MediaFrames frames,
            Throwable error,
            ByteBudget.Lease lease) {
        try {
            // Removing a preview cancels its load, so this also skips evicted previews.
            if (cancellation.isCancelled()) return;
            long now = now();
            if (error == null) {
                retainedBytes += preview.complete(frames);
                trim(now);
            } else {
                retire(preview.fail(PreviewState.failedWith(error, now)));
                SeqClient.LOGGER.debug("[Images] Preview failed: {}", preview.status(now), error);
            }
        } finally {
            postedLeases.remove(lease);
            lease.close();
        }
    }

    /**
     * Draws a ready preview, creating its atlas on first use. Returns false if the preview is not ready or
     * just failed; one waiting for retired atlases to close returns true and draws after the next tick.
     */
    boolean draw(Preview preview, GuiGraphics graphics, int x, int y, int w, int h) {
        long now = now();
        var frames = preview.frames();
        if (frames == null || entries.get(preview.key) != preview) return false;
        use(preview, now);
        try {
            if (preview.player == null) {
                var atlas = PreviewPlayer.atlasFor(frames);
                if (!makeTextureRoom(preview, atlas.bytes())) return true;
                preview.player = new PreviewPlayer(frames, atlas, now);
                textureBytes += preview.player.textureBytes();
            }
            preview.player.draw(graphics, x, y, w, h, now);
            return true;
        } catch (IOException | RuntimeException failure) {
            SeqClient.LOGGER.debug("[Images] Preview could not be displayed", failure);
            release(preview);
            return false;
        }
    }

    /**
     * Retires other previews' atlases, least recently drawn first, until {@code bytes} more fit the texture
     * budget. Retired atlases close on the next tick and count until then; returns false if the new atlas
     * must wait for that tick.
     */
    private boolean makeTextureRoom(Preview drawing, long bytes) {
        long closing = 0;
        for (var player : retiredPlayers) closing += player.textureBytes();
        while (textureBytes - closing + bytes > TEXTURE_BYTES) {
            Preview oldest = null;
            for (var preview : entries.values())
                if (preview != drawing
                        && preview.player != null
                        && (oldest == null || preview.player.lastDrawn() < oldest.player.lastDrawn())) oldest = preview;
            if (oldest == null) break;
            closing += oldest.player.textureBytes();
            retire(oldest.detachPlayer());
        }
        // Any one atlas fits the budget alone, so retiring every other atlas always makes room.
        return textureBytes + bytes <= TEXTURE_BYTES;
    }

    /**
     * Client tick: closes retired textures, frees idle ones and trims to the retained budget. The
     * texture budget is enforced when an atlas is created.
     */
    void maintain() {
        long now = now();
        for (var preview : entries.values())
            if (preview.player != null && !preview.active(now) && now - preview.player.lastDrawn() > IDLE_TEXTURE_NANOS)
                retire(preview.detachPlayer());
        trim(now);
        closeRetiredPlayers();
    }

    /** Evicts least recently used previews, except the one on screen, until every limit holds. */
    private void trim(long now) {
        Iterator<Preview> eldest = entries.values().iterator();
        while ((entries.size() > MAX_ENTRIES || retainedBytes > RETAINED_BYTES) && eldest.hasNext()) {
            var preview = eldest.next();
            if (preview == shown && preview.active(now)) continue;
            eldest.remove();
            release(preview);
        }
    }

    private void evict(Preview preview) {
        entries.remove(preview.key);
        release(preview);
    }

    private void release(Preview preview) {
        retainedBytes -= preview.release(workers);
        retire(preview.detachPlayer());
    }

    /** Defers closing a player's texture to the next tick, outside rendering. */
    private void retire(PreviewPlayer player) {
        if (player != null) retiredPlayers.add(player);
    }

    private void closeRetiredPlayers() {
        for (var player : retiredPlayers) {
            textureBytes -= player.textureBytes();
            player.close();
        }
        retiredPlayers.clear();
    }

    /** Cancels all work and frees everything. Call outside rendering. */
    void clear() {
        entries.values().forEach(this::release);
        entries.clear();
        shown = null;
        closeRetiredPlayers();
        retainedBytes = 0;
        // Results posted but never installed: dropped by a disconnect or now obsolete.
        postedLeases.removeIf(lease -> {
            lease.close();
            return true;
        });
    }

    long retainedBytes() {
        return retainedBytes;
    }

    long inFlightBytes() {
        return inFlight.used();
    }

    int size() {
        return entries.size();
    }
}
