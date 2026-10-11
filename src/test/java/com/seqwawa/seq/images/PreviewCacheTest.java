package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class PreviewCacheTest {
    private static final long FRAME = TimeUnit.MILLISECONDS.toNanos(16);

    private static byte[] resource(String name) throws IOException {
        try (var stream = PreviewCacheTest.class.getResourceAsStream("/" + name)) {
            return stream.readAllBytes();
        }
    }

    private static URI link(String name) {
        return URI.create("https://static.klipy.com/ii/review/" + name + ".mp4");
    }

    /** Incompressible frames of exactly {@code frames} by 256 by 256 pixels. */
    private static MediaFrames noise(int frames, long seed) throws IOException {
        var random = new Random(seed);
        byte[] pixels = new byte[256 * 256 * 4];
        try (var lease = new ByteBudget(Long.MAX_VALUE).open();
                var writer = new MediaFrames.Writer(256, 256, frames, lease)) {
            for (int f = 0; f < frames; f++) {
                random.nextBytes(pixels);
                writer.add(pixels, 40);
            }
            return writer.finish();
        }
    }

    @Test
    void activeNearLimitClipIsDecodedOnceAndNeverEvicted() throws Exception {
        byte[] media = resource("noise-183.mp4");
        var decodes = new AtomicInteger();
        try (var harness =
                new PreviewCacheHarness(
                        (link, cancellation, lease, first) -> {
                            decodes.incrementAndGet();
                            try (var session = IsolatedDecoder.start(cancellation)) {
                                return session.decode(media, lease, first);
                            }
                        })) {
            var cache = harness.cache();
            URI active = link("noise");
            var preview = cache.get(active);
            harness.runMain(); // first frame shown
            assertNotNull(preview.frames(), "the first frame must be shown before the rest decode");
            harness.runMain(); // all frames compressed
            var frames = preview.frames();
            assertTrue(frames.complete());
            assertNotNull(frames, preview.status(cache.now()));
            assertEquals(183, frames.frameCount());
            long expanded = (long) frames.frameBytes() * frames.frameCount();
            assertEquals(47_972_352L, expanded);
            // Previously pixels plus a compressed copy were both resident: 81,606,949 bytes.
            assertTrue(frames.bytes() < expanded, "retained " + frames.bytes());
            assertTrue(cache.retainedBytes() <= PreviewCache.RETAINED_BYTES);
            // Ten seconds of hovering, one request per rendered frame.
            for (int i = 0; i < 625; i++) {
                harness.advance(FRAME);
                assertSame(preview, cache.get(active));
                if (i % 60 == 0) cache.maintain();
            }
            assertEquals(1, decodes.get(), "The active clip was fetched or decoded again");
            assertEquals(0, harness.pendingMain());
            harness.awaitInFlightReleased();
        }
    }

    @Test
    void budgetPressureEvictsInactivePreviewsButKeepsTheActiveOne() throws Exception {
        var shared = noise(120, 3);
        var loads = new ConcurrentHashMap<URI, AtomicInteger>();
        try (var harness =
                new PreviewCacheHarness(
                        (link, cancellation, lease, first) -> {
                            loads.computeIfAbsent(link, ignored -> new AtomicInteger())
                                    .incrementAndGet();
                            return shared;
                        })) {
            var cache = harness.cache();
            URI active = link("active");
            cache.get(active);
            harness.runMain();
            for (int i = 0; i < 4; i++) {
                harness.advance(FRAME);
                cache.get(active);
                cache.get(link("other-" + i));
                harness.runMain();
                // Hovering moves on; only the active link stays in use.
                for (int t = 0; t < 70; t++) {
                    harness.advance(FRAME);
                    cache.get(active);
                }
                cache.maintain();
                assertTrue(cache.retainedBytes() <= PreviewCache.RETAINED_BYTES, "over budget");
                assertNotNull(cache.get(active).frames(), "active preview was evicted");
            }
            assertEquals(1, loads.get(active).get());
            assertTrue(cache.size() < 5, "inactive previews were not evicted");
        }
    }

    @Test
    void firstFrameShowsWhileTheRestIsChargedAsInFlight() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var harness =
                new PreviewCacheHarness(
                        (link, cancellation, lease, first) -> {
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
            assertEquals(
                    frames.available(),
                    frames.frameAt(1_000),
                    "unfinished playback must hold, not loop");
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
        try (var harness =
                new PreviewCacheHarness(
                        (link, cancellation, lease, first) -> {
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
        try (var harness =
                new PreviewCacheHarness((link, cancellation, lease, first) -> {
                    lease.reserve(MediaFormat.MAX_BYTES);
                    return shared;
                })) {
            harness.cache().get(link("dropped"));
            for (int i = 0; i < 2_000 && harness.pendingMain() == 0; i++) Thread.sleep(5);
            assertEquals(1, harness.pendingMain());
            harness.dropMain();
            harness.awaitInFlightReleased();
        }
    }
}
