package com.seqwawa.seq.images;

import net.minecraft.client.gui.GuiGraphics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Previews keyed by chat link. Owned by the render thread; workers only download and decode.
 *
 * <p>Three independent budgets bound memory: compressed frames of ready previews, bytes held by
 * loads still in progress, and GPU atlases. A preview drawn within the last second is active
 * and is never evicted, so it cannot be dropped and fetched again while on screen.
 */
final class PreviewCache {
    static final long RETAINED_BYTES = 64L * 1024 * 1024;
    static final long IN_FLIGHT_BYTES = 96L * 1024 * 1024;
    static final long TEXTURE_BYTES = 96L * 1024 * 1024;
    static final int MAX_ENTRIES = 32;
    static final long ACTIVE_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final long IDLE_TEXTURE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final Logger LOG = LoggerFactory.getLogger("Sequoia Images");

    /** Downloads and decodes one link on a worker thread. */
    @FunctionalInterface
    interface Loader {
        /**
         * @param firstFrame receives the animation once its first frame can be shown
         * @return the completed animation
         */
        MediaFrames load(
                URI link,
                MediaCancellation cancellation,
                ByteBudget.Lease lease,
                Consumer<MediaFrames> firstFrame)
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

        PreviewState state() {
            return state;
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

    }

    private final LinkedHashMap<String, Preview> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final List<PreviewPlayer> retiredPlayers = new ArrayList<>();
    private final ThreadPoolExecutor workers;
    private final Executor mainThread;
    private final Loader loader;
    private final ByteBudget inFlight = new ByteBudget(IN_FLIGHT_BYTES);
    private final LongSupplier clock;
    private long retainedBytes;
    private long textureBytes;

    /**
     * @param mainThread runs load results on the render thread
     * @param clock monotonic nanoseconds; the only time source for activity and expiry
     */
    PreviewCache(
            ThreadPoolExecutor workers, Executor mainThread, Loader loader, LongSupplier clock) {
        this.workers = workers;
        this.mainThread = mainThread;
        this.loader = loader;
        this.clock = clock;
    }

    long now() {
        return clock.getAsLong();
    }

    /** Returns the preview for {@code link}, starting a load only when none is usable. */
    Preview get(URI link) {
        long now = now();
        String key = link.toString();
        URI source = BridgeMedia.resolve(link);
        Preview found = entries.get(key);
        if (found != null && source.equals(found.source)) {
            found.lastUsed = now;
            return found;
        }
        // A delayed signed Discord URL changes the source; replace the stale preview.
        if (found != null) evict(found);
        var preview = new Preview(key, link, source, now);
        entries.put(key, preview);
        load(preview);
        trim(now);
        return preview;
    }

    private void load(Preview preview) {
        var cancellation = new MediaCancellation();
        var task = new FutureTask<Void>(() -> run(preview, cancellation), null);
        preview.cancellation = cancellation;
        preview.task = task;
        try {
            workers.execute(task);
        } catch (RejectedExecutionException busy) {
            preview.task = null;
            preview.state = new PreviewState.Failed(PreviewState.BUSY);
        }
    }

    private void run(Preview preview, MediaCancellation cancellation) {
        var lease = inFlight.open();
        MediaFrames frames = null;
        Exception failure = null;
        try {
            cancellation.check();
            frames =
                    loader.load(
                            preview.link,
                            cancellation,
                            lease,
                            first -> post(() -> show(preview, cancellation, first)));
            cancellation.check();
        } catch (Exception error) {
            failure = error;
        }
        if (cancellation.isCancelled()) {
            lease.close();
            return;
        }
        var loaded = frames;
        var error = failure;
        if (!post(
                () -> {
                    try {
                        finish(preview, cancellation, loaded, error);
                    } finally {
                        lease.close();
                    }
                })) lease.close();
    }

    private boolean post(Runnable task) {
        try {
            mainThread.execute(task);
            return true;
        } catch (RejectedExecutionException stopping) {
            return false;
        }
    }

    /** Starts showing an animation whose remaining frames are still being compressed. */
    private void show(Preview preview, MediaCancellation cancellation, MediaFrames frames) {
        if (entries.get(preview.key) != preview
                || cancellation.isCancelled()
                || !(preview.state instanceof PreviewState.Loading)) return;
        preview.state = new PreviewState.Ready(frames);
    }

    private void finish(
            Preview preview, MediaCancellation cancellation, MediaFrames frames, Exception error) {
        if (entries.get(preview.key) != preview || cancellation.isCancelled()) return;
        preview.task = null;
        long now = now();
        if (error == null) {
            preview.state = new PreviewState.Ready(frames);
            preview.retained = frames.bytes();
            retainedBytes += preview.retained;
            trim(now);
        } else {
            retirePlayer(preview);
            preview.state = PreviewState.failedWith(error, now);
            LOG.debug("Image preview failed: {}", preview.status(now), error);
        }
    }

    /**
     * Draws a ready preview, creating its atlas on first use.
     *
     * @return false if the preview is not ready or just failed
     */
    boolean draw(Preview preview, GuiGraphics graphics, int x, int y, int w, int h) {
        long now = now();
        var frames = preview.frames();
        if (frames == null || entries.get(preview.key) != preview) return false;
        preview.lastUsed = now;
        try {
            if (preview.player == null) {
                preview.player = new PreviewPlayer(frames, now);
                textureBytes += preview.player.textureBytes();
            }
            preview.player.draw(graphics, x, y, w, h, now);
            return true;
        } catch (java.io.IOException | RuntimeException failure) {
            LOG.debug("Image preview could not be displayed", failure);
            release(preview);
            return false;
        }
    }

    /** Client tick: frees idle textures and enforces every budget. */
    void maintain() {
        long now = now();
        closeRetiredPlayers();
        for (var preview : entries.values())
            if (preview.player != null
                    && !preview.active(now)
                    && now - preview.player.lastDrawn() > IDLE_TEXTURE_NANOS) retirePlayer(preview);
        for (var iterator = entries.values().iterator();
                textureBytes > TEXTURE_BYTES && iterator.hasNext(); ) {
            var preview = iterator.next();
            if (preview.player != null && !preview.active(now)) retirePlayer(preview);
        }
        trim(now);
        closeRetiredPlayers();
    }

    /** Evicts least recently used inactive previews until every limit holds. */
    private void trim(long now) {
        Iterator<Preview> eldest = entries.values().iterator();
        while ((entries.size() > MAX_ENTRIES || retainedBytes > RETAINED_BYTES)
                && eldest.hasNext()) {
            var preview = eldest.next();
            if (preview.active(now)) continue;
            eldest.remove();
            release(preview);
        }
    }

    private void evict(Preview preview) {
        entries.remove(preview.key);
        release(preview);
    }

    private void release(Preview preview) {
        retainedBytes -= preview.retained;
        preview.retained = 0;
        if (preview.cancellation != null) preview.cancellation.cancel();
        if (preview.task != null) {
            preview.task.cancel(true);
            workers.remove(preview.task);
            preview.task = null;
        }
        retirePlayer(preview);
        preview.state = new PreviewState.Failed(PreviewState.BLOCKED);
    }

    private void retirePlayer(Preview preview) {
        if (preview.player == null) return;
        textureBytes -= preview.player.textureBytes();
        retiredPlayers.add(preview.player);
        preview.player = null;
    }

    private void closeRetiredPlayers() {
        retiredPlayers.forEach(PreviewPlayer::close);
        retiredPlayers.clear();
    }

    /** Cancels all work and frees everything. Call outside rendering. */
    void clear() {
        entries.values().forEach(this::release);
        entries.clear();
        closeRetiredPlayers();
        retainedBytes = 0;
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
