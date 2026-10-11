package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class CacheCodecTest {
    private ImageDecoder.Animation sample(int count) {
        var frames = new ArrayList<ImageDecoder.Frame>();
        for (int f = 0; f < count; f++) {
            int[] pixels = new int[17 * 19];
            for (int i = 0; i < pixels.length; i++) {
                int alpha =
                        switch (i % 3) {
                            case 0 -> 0;
                            case 1 -> 127;
                            default -> 255;
                        };
                pixels[i] =
                        (alpha << 24)
                                | (((i * 71 + f * 9) & 255) << 16)
                                | (((i * 31 + f * 11) & 255) << 8)
                                | ((i * 17 + f * 19) & 255);
            }
            frames.add(new ImageDecoder.Frame(pixels, new int[] {20, 55, 200, 35}[f % 4]));
        }
        return new ImageDecoder.Animation(17, 19, List.copyOf(frames));
    }

    private void same(ImageDecoder.Animation expected, ImageDecoder.Animation actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        assertEquals(expected.frames().size(), actual.frames().size());
        for (int i = 0; i < expected.frames().size(); i++) {
            assertEquals(
                    expected.frames().get(i).durationMs(), actual.frames().get(i).durationMs());
            assertArrayEquals(
                    expected.frames().get(i).argb(), actual.frames().get(i).argb(), "frame " + i);
        }
    }

    @Test
    void stillCachePreservesEvenInvisibleRgb() throws Exception {
        var input = sample(1);
        var encoded = CacheCodec.encode(input);
        assertEquals(CacheCodec.Format.DEFLATE, encoded.format());
        same(input, CacheCodec.decode(encoded));
    }

    @Test
    void animationCachePreservesAlphaOddDimensionsAndVariableTiming() throws Exception {
        var input = sample(4);
        var encoded = CacheCodec.encode(input);
        assertEquals(CacheCodec.Format.DEFLATE, encoded.format());
        same(input, CacheCodec.decode(encoded));
    }

    @Test
    void actualSpongeBobCacheRoundTripsAllFramesWithLessMemory() throws Exception {
        byte[] bytes;
        try (var stream = getClass().getResourceAsStream("/klipy-report.mp4")) {
            bytes = stream.readAllBytes();
        }
        var input = ImageDecoder.decode(bytes);
        long start = System.nanoTime();
        var encoded = CacheCodec.encode(input);
        double encodeMs = (System.nanoTime() - start) / 1_000_000.0;
        start = System.nanoTime();
        var decoded = CacheCodec.decode(encoded);
        double decodeMs = (System.nanoTime() - start) / 1_000_000.0;
        same(input, decoded);
        assertTrue(encoded.bytes() < input.bytes());
        System.out.printf(
                java.util.Locale.ROOT,
                "CACHE_BENCH raw=%d compressed=%d encode=%.1fms decode=%.1fms%n",
                input.bytes(),
                encoded.bytes(),
                encodeMs,
                decodeMs);
    }

    @Test
    void corruptTruncatedAndOverexpandedCachesAreRejected() throws Exception {
        var original = CacheCodec.encode(sample(4));
        var truncated = java.util.Arrays.copyOf(original.data(), original.data().length - 1);
        assertThrows(
                java.io.IOException.class,
                () ->
                        CacheCodec.decode(
                                new CacheCodec.Encoded(
                                        CacheCodec.Format.DEFLATE,
                                        17,
                                        19,
                                        original.durations(),
                                        truncated)));
        byte[] trailing = java.util.Arrays.copyOf(original.data(), original.data().length + 1);
        assertThrows(
                java.io.IOException.class,
                () ->
                        CacheCodec.decode(
                                new CacheCodec.Encoded(
                                        CacheCodec.Format.DEFLATE,
                                        17,
                                        19,
                                        original.durations(),
                                        trailing)));
        assertThrows(
                java.io.IOException.class,
                () ->
                        CacheCodec.decode(
                                new CacheCodec.Encoded(
                                        CacheCodec.Format.DEFLATE,
                                        1,
                                        1,
                                        List.of(20),
                                        original.data())));
        assertThrows(
                java.io.IOException.class,
                () ->
                        CacheCodec.decode(
                                new CacheCodec.Encoded(
                                        CacheCodec.Format.DEFLATE,
                                        Integer.MAX_VALUE,
                                        Integer.MAX_VALUE,
                                        List.of(20),
                                        new byte[0])));
    }
}
