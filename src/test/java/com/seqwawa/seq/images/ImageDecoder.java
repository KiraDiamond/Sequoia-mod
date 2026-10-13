package com.seqwawa.seq.images;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Test view of a decode with every frame expanded to ARGB, read back through the production
 * {@link MediaFrames.Reader}, so exact-pixel assertions cover the helper, the stream validation and the
 * cache codec together.
 */
final class ImageDecoder {
    record Frame(int[] argb, int durationMs) {}

    record Animation(int width, int height, List<Frame> frames, MediaFrames media) {
        int frameAt(long elapsedMs) {
            return media.frameAt(elapsedMs);
        }
    }

    private ImageDecoder() {}

    static Animation decode(byte[] media) throws IOException {
        return expand(IsolatedDecoder.decode(media));
    }

    static Animation expand(MediaFrames media) throws IOException {
        var frames = new ArrayList<Frame>(media.frameCount());
        try (var reader = media.reader()) {
            for (int i = 0; i < media.frameCount(); i++) {
                byte[] rgba = reader.next();
                int[] argb = new int[rgba.length / 4];
                for (int p = 0; p < argb.length; p++)
                    argb[p] = (rgba[4 * p + 3] & 255) << 24
                            | (rgba[4 * p] & 255) << 16
                            | (rgba[4 * p + 1] & 255) << 8
                            | rgba[4 * p + 2] & 255;
                frames.add(new Frame(argb, media.duration(i)));
            }
        }
        return new Animation(media.width(), media.height(), List.copyOf(frames), media);
    }
}
