package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

class VideoTest {
    @Test
    void decodesActualKlipyMediaInsteadOfItsAnimatedUnavailableCard() throws Exception {
        byte[] bytes;
        try (var stream = getClass().getResourceAsStream("/klipy-report.mp4")) {
            bytes = stream.readAllBytes();
        }
        var result = ImageDecoder.decode(bytes);
        assertEquals(256, Math.max(result.width(), result.height()));
        assertEquals(256, result.width());
        assertEquals(192, result.height());
        assertEquals(44, result.frames().size());
        assertEquals(1760, result.frames().stream().mapToInt(ImageDecoder.Frame::durationMs).sum());
        var pixels = ByteBuffer.allocate(result.frames().getFirst().argb().length * 4);
        for (int pixel : result.frames().getFirst().argb()) pixels.putInt(pixel);
        String hash =
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(pixels.array()));
        // Pin the real animation's first frame so the unavailable-card image cannot pass.
        assertEquals("fc875337ce28a9d0f909d87c9c9f0863059bf91ffe0ca457ee5ae2d8917e546c", hash);
        assertTrue(result.frames().stream().allMatch(frame -> frame.durationMs() > 0));
        assertFalse(
                java.util.Arrays.equals(
                        result.frames().getFirst().argb(), result.frames().getLast().argb()));
    }

    @Test
    void rejectsMalformedMp4BoxSizeBeforeDecoderAllocates() {
        byte[] bytes =
                ByteBuffer.allocate(16)
                        .putInt(Integer.MAX_VALUE)
                        .putInt(0x66747970)
                        .putLong(0)
                        .array();
        assertThrows(IOException.class, () -> ImageDecoder.decode(bytes));
    }

    @Test
    void rejectsMaliciousSampleCountBeforeAllocation() {
        byte[] bytes =
                ByteBuffer.allocate(36)
                        .putInt(16)
                        .putInt(0x66747970)
                        .putLong(0)
                        .putInt(20)
                        .putInt(0x7374737a)
                        .putInt(0)
                        .putInt(1)
                        .putInt(Integer.MAX_VALUE)
                        .array();
        assertThrows(IOException.class, () -> ImageDecoder.decode(bytes));
    }

    @Test
    void rejectsTruncatedMp4() throws Exception {
        byte[] bytes;
        try (var stream = getClass().getResourceAsStream("/klipy-report.mp4")) {
            bytes = stream.readNBytes(100);
        }
        assertThrows(IOException.class, () -> ImageDecoder.decode(bytes));
    }
}
