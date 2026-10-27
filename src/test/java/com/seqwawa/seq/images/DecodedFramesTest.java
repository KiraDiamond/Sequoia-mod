package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** The helper's pixel stream: {@code MAGIC, width, height, count}, then per frame a duration and ARGB pixels. */
class DecodedFramesTest {
    private static final int MAGIC = DecodedFrames.MAGIC;

    private static InputStream stream(int... values) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        for (int value : values) out.writeInt(value);
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    private static MediaFrames read(InputStream input, ByteBudget budget) throws IOException {
        try (var lease = budget.open()) {
            return DecodedFrames.read(input, lease, frames -> {});
        }
    }

    @Test
    void handlesShortReadsAndKeepsEveryArgbBit() throws Exception {
        var input = new FilterInputStream(stream(MAGIC, 2, 1, 1, 55, 0x00123456, 0x80abcdef)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, 1));
            }
        };
        var frames = ImageDecoder.expand(read(input, new ByteBudget(1 << 20)));
        assertEquals(55, frames.frames().getFirst().durationMs());
        assertArrayEquals(
                new int[] {0x00123456, 0x80abcdef}, frames.frames().getFirst().argb());
    }

    @Test
    void rejectsBadHeadersTimingTruncationAndTrailingBytesWithoutHoldingMemory() throws Exception {
        var budget = new ByteBudget(64L * 1024 * 1024);
        try (var lease = budget.open()) {
            var bomb = stream(MAGIC, Integer.MAX_VALUE, Integer.MAX_VALUE, 400);
            assertThrows(IOException.class, () -> DecodedFrames.read(bomb, lease, frames -> {}));
            assertEquals(0, budget.used(), "nothing may be reserved for an invalid header");
        }
        for (int[] values : new int[][] {
            {MAGIC, 1025, 1, 1},
            {MAGIC, 1, 1025, 1},
            {MAGIC, 257, 1, 2}, // extra resolution is never allowed for multiple frames
            {MAGIC, 1, 257, 2},
            {MAGIC, 256, 256, 400}, // over the pixel budget
            {MAGIC + 1, 1, 1, 1, 20, 0},
            {MAGIC, 1, 1, 1, 0, 0}, // zero duration
            {MAGIC, 1, 1, 1, 20}, // truncated pixels
            {MAGIC, 1, 1, 1, 20, 0xff123456, 0} // trailing data
        }) assertThrows(IOException.class, () -> read(stream(values), budget));
        assertEquals(0, budget.used(), "a rejected stream must release everything it reserved");
    }

    @Test
    void highResolutionStillFitsTheExistingBudgets() throws Exception {
        var budget = new ByteBudget(PreviewCache.IN_FLIGHT_BYTES);
        byte[] pixels = new byte[1024 * 1024 * 4];
        try (var lease = budget.open(); var writer = new MediaFrames.Writer(1024, 1024, 1, lease)) {
            writer.add(pixels, 100);
            var media = writer.finish();
            try (var reader = media.reader()) {
                assertEquals(pixels.length, reader.next().length);
            }
            var atlas = FrameAtlas.of(1024, 1024, 1, 4096);
            assertEquals(4L * 1024 * 1024, atlas.bytes());
        }
        assertEquals(0, budget.used());
        var encoded = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(encoded)) {
            for (int value : new int[] {MAGIC, 1024, 1024, 1, 100}) output.writeInt(value);
            output.write(pixels);
        }
        try (var lease = budget.open()) {
            var media = DecodedFrames.read(new ByteArrayInputStream(encoded.toByteArray()), lease, first -> {});
            assertEquals(1024, media.width());
            assertEquals(1024, media.height());
        }
        assertEquals(0, budget.used(), "the stream reader's additional pixel buffer is also released");
    }
}
