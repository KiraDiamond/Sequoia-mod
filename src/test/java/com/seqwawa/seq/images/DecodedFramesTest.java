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
            {MAGIC, 256, 256, 400}, // over the pixel budget
            {MAGIC + 1, 1, 1, 1, 20, 0},
            {MAGIC, 1, 1, 1, 0, 0}, // zero duration
            {MAGIC, 1, 1, 1, 20}, // truncated pixels
            {MAGIC, 1, 1, 1, 20, 0xff123456, 0} // trailing data
        }) assertThrows(IOException.class, () -> read(stream(values), budget));
        assertEquals(0, budget.used(), "a rejected stream must release everything it reserved");
    }
}
