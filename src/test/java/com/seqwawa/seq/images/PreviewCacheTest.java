package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PreviewCacheTest {
    private static final long FRAME = TimeUnit.MILLISECONDS.toNanos(16);

    private static URI link(String name) {
        return URI.create("https://static.klipy.com/ii/review/" + name + ".mp4");
    }

    /** Incompressible frames of exactly {@code frames} by 256 by 256 pixels, charged to {@code lease}. */
    private static MediaFrames noise(int frames, long seed, ByteBudget.Lease lease, Consumer<MediaFrames> first)
            throws IOException {
        var random = new Random(seed);
        byte[] pixels = new byte[256 * 256 * 4];
        try (var writer = new MediaFrames.Writer(256, 256, frames, lease)) {
            for (int f = 0; f < frames; f++) {
                random.nextBytes(pixels);
                writer.add(pixels, 40);
                if (f == 0) first.accept(writer.frames());
            }
            return writer.finish();
        }
    }

    private static MediaFrames noise(int frames, long seed) throws IOException {
        try (var lease = new ByteBudget(Long.MAX_VALUE).open()) {
            return noise(frames, seed, lease, ignored -> {});
        }
    }

    @Test
    void activeNearLimitClipIsDecodedOnceAndNeverEvicted() throws Exception {
        var decodes = new AtomicInteger();
        try (var harness = new PreviewCacheHarness((link, cancellation, lease, first) -> {
            decodes.incrementAndGet();
            return noise(183, 1, lease, first);
        })) {
            var cache = harness.cache();
            URI active = link("noise");
            var preview = cache.get(active);
            harness.runMain(); // first frame shown
            assertNotNull(preview.frames(), "the first frame must be shown before the rest decode");
            harness.runMain(); // all frames compressed
            assertTrue(preview.frames().complete(), preview.status(cache.now()));
            assertTrue(cache.retainedBytes() <= PreviewCache.RETAINED_BYTES);
            for (int i = 0; i < 10; i++) {
                harness.advance(FRAME);
                assertSame(preview, cache.get(active));
                cache.maintain();
            }
            assertEquals(1, decodes.get(), "The active clip was fetched or decoded again");
            assertEquals(0, harness.pendingMain());
            harness.awaitInFlightReleased();
        }
    }

    @Test
    void previewsHoveredWithinTheSameSecondStillFitTheRetainedBudget() throws Exception {
        var shared = noise(140, 9);
        assertTrue(
                3 * shared.bytes() > PreviewCache.RETAINED_BYTES,
                "the fixture must not fit three times in the retained budget");
        try (var harness = new PreviewCacheHarness((link, cancellation, lease, first) -> shared)) {
            var cache = harness.cache();
            for (int i = 0; i < 2; i++) {
                cache.get(link("passed-over-" + i));
                harness.advance(FRAME);
            }
            var shown = cache.get(link("shown"));
            for (int i = 0; i < 3; i++) harness.runMain();
            assertTrue(cache.retainedBytes() <= PreviewCache.RETAINED_BYTES, "over budget");
            assertNotNull(shown.frames(), "the preview on screen was evicted");
            assertEquals(2, cache.size(), "only the least recently used preview had to go");
        }
    }

    @Test
    void firstFrameShowsWhileTheRestIsChargedAsInFlight() throws Exception {
        var release = new CountDownLatch(1);
        try (var harness = new PreviewCacheHarness((link, cancellation, lease, first) -> {
            try (var writer = new MediaFrames.Writer(4, 4, 2, lease)) {
                writer.add(new byte[64], 30);
                first.accept(writer.frames());
                assertTrue(release.await(5, TimeUnit.SECONDS));
                writer.add(new byte[64], 30);
                return writer.finish();
            }
        })) {
            var cache = harness.cache();
            var preview = cache.get(link("progressive"));
            harness.runMain();
            var frames = preview.frames();
            assertNotNull(frames);
            assertEquals(1, frames.available());
            assertFalse(frames.complete());
            assertEquals(frames.available(), frames.frameAt(1_000), "unfinished playback must hold, not loop");
            assertEquals(0, cache.retainedBytes());
            assertTrue(cache.inFlightBytes() > 0);
            release.countDown();
            harness.runMain();
            assertTrue(frames.complete());
            assertEquals(frames.bytes(), cache.retainedBytes());
            harness.awaitInFlightReleased();
            assertEquals(0, frames.frameAt(60), "a finished animation loops");
        }
    }

    @Test
    void loadsExceedingTheInFlightBudgetFailWithoutLeakingReservations() throws Exception {
        try (var harness = new PreviewCacheHarness((link, cancellation, lease, first) -> {
            lease.reserve(PreviewCache.IN_FLIGHT_BYTES + 1);
            return null;
        })) {
            var preview = harness.cache().get(link("huge"));
            harness.runMain();
            assertEquals(PreviewState.BUSY, preview.status(harness.cache().now()));
            harness.awaitInFlightReleased();
        }
    }

    @Test
    void droppedCompletionsStillReturnTheirInFlightReservation() throws Exception {
        var shared = noise(4, 5);
        try (var harness = new PreviewCacheHarness((link, cancellation, lease, first) -> {
            lease.reserve(MediaFormat.MAX_BYTES);
            return shared;
        })) {
            harness.cache().get(link("dropped"));
            for (int i = 0; i < 2_000 && harness.pendingMain() == 0; i++) Thread.sleep(5);
            assertEquals(1, harness.pendingMain());
            assertTrue(harness.cache().inFlightBytes() > 0, "a posted result stays charged");
            // A disconnect drops queued tasks and then clears the previews.
            harness.dropMain();
            harness.cache().clear();
            harness.awaitInFlightReleased();
        }
    }

    @Test
    void rejectedCompletionsReturnTheirInFlightReservation() throws Exception {
        var shared = noise(1, 9);
        var workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
        // The client refuses every task, as it does while stopping.
        var cache = new PreviewCache(
                workers,
                task -> {
                    throw new RejectedExecutionException("Client stopping");
                },
                (link, cancellation, lease, first) -> {
                    lease.reserve(MediaFormat.MAX_BYTES);
                    return shared;
                },
                System::nanoTime);
        try {
            cache.get(link("rejected"));
            workers.shutdown();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(0, cache.inFlightBytes(), "A rejected result must return its reservation");
        } finally {
            workers.shutdownNow();
            cache.clear();
        }
    }

    @Test
    void resultsWaitingForAStalledRenderThreadStayInsideTheBudget() throws Exception {
        long share = 40L * 1024 * 1024;
        var shared = noise(1, 7);
        var reserved = new AtomicInteger();
        var finished = new CountDownLatch(3);
        try (var harness = new PreviewCacheHarness((link, cancellation, lease, first) -> {
            try {
                lease.reserve(share);
                reserved.incrementAndGet();
                return shared;
            } finally {
                finished.countDown();
            }
        })) {
            for (int i = 0; i < 3; i++) harness.cache().get(link("stalled-" + i));
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 200 && harness.pendingMain() < 3; i++) Thread.sleep(5);
            // Nothing has been installed, yet the queued results are still counted.
            assertEquals(2, reserved.get(), "the third result must not fit beside two waiting ones");
            assertEquals(2 * share, harness.cache().inFlightBytes());
            for (int i = 0; i < 3; i++) harness.runMain();
            harness.awaitInFlightReleased();
        }
    }
}
