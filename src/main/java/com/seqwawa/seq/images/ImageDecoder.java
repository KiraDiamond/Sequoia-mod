package com.seqwawa.seq.images;

import java.io.IOException;
import java.util.List;

public final class ImageDecoder {
    public record Frame(int[] argb, int durationMs) {}

    public record Animation(int width, int height, List<Frame> frames) {
        public int frameAt(long elapsedMs) {
            long cycle = frames.stream().mapToLong(Frame::durationMs).sum();
            long time = Math.floorMod(elapsedMs, cycle);
            for (int i = 0; i < frames.size(); i++) {
                time -= frames.get(i).durationMs();
                if (time < 0) return i;
            }
            return 0;
        }

        public long bytes() {
            return (long) width * height * frames.size() * 4;
        }
    }

    private ImageDecoder() {}

    public static Animation decode(byte[] bytes) throws IOException {
        return IsolatedDecoder.decode(bytes);
    }
}
