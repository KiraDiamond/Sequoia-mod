package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MediaFramesTest {
    private static final int W = 17, H = 19;

    /** Big-endian ARGB bytes as the helper emits them, with alpha 0, 127 and 255. */
    private static byte[] argbFrame(int frame) {
        byte[] bytes = new byte[W * H * 4];
        for (int i = 0; i < W * H; i++) {
            bytes[4 * i] = (byte) new int[] {0, 127, 255}[i % 3];
            bytes[4 * i + 1] = (byte) (i * 71 + frame * 9);
            bytes[4 * i + 2] = (byte) (i * 31 + frame * 11);
            bytes[4 * i + 3] = (byte) (i * 17 + frame * 19);
        }
        return bytes;
    }

    private static MediaFrames encode(ByteBudget.Lease lease, int... durations) throws IOException {
        try (var writer = new MediaFrames.Writer(W, H, durations.length, lease)) {
            for (int f = 0; f < durations.length; f++) writer.add(argbFrame(f), durations[f]);
            return writer.finish();
        }
    }

    private static void assertRgbaMatches(byte[] argb, byte[] rgba) {
        for (int i = 0; i < W * H; i++) {
            assertEquals(argb[4 * i + 1], rgba[4 * i], "red " + i);
            assertEquals(argb[4 * i + 2], rgba[4 * i + 1], "green " + i);
            assertEquals(argb[4 * i + 3], rgba[4 * i + 2], "blue " + i);
            assertEquals(argb[4 * i], rgba[4 * i + 3], "alpha " + i);
        }
    }

    @Test
    void framesRoundTripExactly() throws Exception {
        try (var lease = new ByteBudget(1 << 20).open()) {
            var media = encode(lease, 20, 55, 200, 35);
            assertEquals(310, media.cycleMs());
            assertEquals(200, media.duration(2));
            try (var reader = media.reader()) {
                for (int f = 0; f < 4; f++) assertRgbaMatches(argbFrame(f), reader.next());
            }
        }
    }

    @Test
    void staticAnimationStaysFarSmallerThanItsExpandedFrames() throws Exception {
        try (var lease = new ByteBudget(1 << 20).open();
                var writer = new MediaFrames.Writer(W, H, 50, lease)) {
            for (int f = 0; f < 50; f++) writer.add(argbFrame(0), 40);
            var media = writer.finish();
            assertTrue(media.bytes() * 10 < (long) media.frameBytes() * 50, "" + media.bytes());
        }
    }

    @Test
    void tamperedFrameFailsItsIntegrityCheck() throws Exception {
        try (var lease = new ByteBudget(1 << 20).open()) {
            var media = encode(lease, 20, 20);
            var field = MediaFrames.class.getDeclaredField("chunks");
            field.setAccessible(true);
            byte[][] chunks = (byte[][]) field.get(media);
            chunks[1][chunks[1].length / 2] ^= 0x40;
            try (var reader = media.reader()) {
                reader.next();
                assertThrows(IOException.class, reader::next);
            }
        }
    }

    @Test
    void malformedStreamsAreRejected() throws Exception {
        try (var lease = new ByteBudget(1 << 20).open()) {
            try (var writer = new MediaFrames.Writer(W, H, 2, lease)) {
                writer.add(argbFrame(0), 20);
                assertThrows(IOException.class, writer::finish, "missing frame");
            }
            try (var writer = new MediaFrames.Writer(W, H, 1, lease)) {
                assertThrows(IOException.class, () -> writer.add(argbFrame(0), 0), "zero timing");
                assertThrows(IOException.class, () -> writer.add(argbFrame(0), 655_351), "overlong timing");
                assertThrows(IOException.class, () -> writer.add(new byte[4], 20), "short frame");
                writer.add(argbFrame(0), 20);
                assertThrows(IOException.class, () -> writer.add(argbFrame(1), 20), "extra frame");
            }
            assertThrows(IOException.class, () -> new MediaFrames.Writer(257, 1, 1, lease));
            assertThrows(IOException.class, () -> new MediaFrames.Writer(256, 256, 400, lease));
        }
    }

    @Test
    void budgetCoversScratchAndRetainedFramesUntilTheLeaseCloses() throws Exception {
        var budget = new ByteBudget(1 << 20);
        MediaFrames media;
        try (var lease = budget.open()) {
            media = encode(lease, 20, 30);
            assertEquals(media.bytes(), budget.used(), "only finished frames stay reserved");
        }
        assertEquals(0, budget.used());
        var small = new ByteBudget(W * H * 4);
        try (var lease = small.open()) {
            assertThrows(ByteBudget.Exhausted.class, () -> encode(lease, 20));
        }
        assertEquals(0, small.used());
    }

    @Test
    void readersWaitForPublishedFramesAndAreReleasedByFailure() throws Exception {
        try (var lease = new ByteBudget(1 << 20).open()) {
            var writer = new MediaFrames.Writer(W, H, 3, lease);
            var frames = writer.frames();
            writer.add(argbFrame(0), 20);
            assertEquals(1, frames.available());
            assertTrue(frames.awaitFrame(0));
            var waiter = Executors.newVirtualThreadPerTaskExecutor().submit(() -> frames.awaitFrame(1));
            writer.add(argbFrame(1), 20);
            assertTrue(waiter.get(5, TimeUnit.SECONDS));
            try (var reader = frames.reader()) {
                assertRgbaMatches(argbFrame(0), reader.next());
                assertRgbaMatches(argbFrame(1), reader.next());
                assertThrows(IOException.class, reader::next, "frame 2 is not published");
            }
            writer.close();
            assertFalse(frames.awaitFrame(2), "a failed stream must not block readers");
            assertFalse(frames.complete());
        }
    }

    @Test
    void frameLookupMatchesALinearScan() throws Exception {
        var random = new Random(7);
        int[] durations = new int[400];
        try (var lease = new ByteBudget(1 << 20).open();
                var writer = new MediaFrames.Writer(1, 1, durations.length, lease)) {
            for (int i = 0; i < durations.length; i++) writer.add(new byte[4], durations[i] = 1 + random.nextInt(300));
            var media = writer.finish();
            for (long t = -5_000; t < 3 * media.cycleMs(); t += 7) {
                long time = Math.floorMod(t, media.cycleMs());
                int expected = 0;
                while (time >= durations[expected]) time -= durations[expected++];
                assertEquals(expected, media.frameAt(t), "t=" + t);
            }
        }
    }

    @Test
    void everyValidAnimationFitsOneAtlasWithoutOverlap() throws Exception {
        for (int w = 1; w <= 256; w++)
            for (int h = 1; h <= 256; h++) {
                int frames = (int) Math.min(400, 12_000_000L / (w * h));
                var atlas = FrameAtlas.of(w, h, frames, FrameAtlas.MAX_SIDE);
                assertTrue(atlas.width() <= 4096 && atlas.height() <= 4096, w + "x" + h);
                assertTrue(atlas.bytes() <= PreviewCache.TEXTURE_BYTES, "any one atlas fits the texture budget");
                int last = frames - 1;
                assertTrue(atlas.x(last) + w <= atlas.width() && atlas.y(last) + h <= atlas.height());
            }
        var atlas = FrameAtlas.of(100, 50, 7, 4096);
        assertEquals(0, atlas.x(0));
        assertEquals(600, atlas.x(6));
        assertEquals(0, atlas.y(6));
        assertThrows(IOException.class, () -> FrameAtlas.of(256, 256, 183, 2048));
    }
}
