package com.seqwawa.seq.images;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/** Validating reader for the helper's pixel stream. */
final class DecodedFrames {
    static final int MAGIC = 0x53494D31;
    static final int MAX_SIDE = 256;
    static final int MAX_FRAMES = 400;
    static final long MAX_PIXELS = 12_000_000;

    private DecodedFrames() {}

    static void validate(int w, int h, int count) throws IOException {
        if (w < 1
                || h < 1
                || w > MAX_SIDE
                || h > MAX_SIDE
                || count < 1
                || count > MAX_FRAMES
                || (long) w * h * count > MAX_PIXELS) throw new IOException("Invalid decoded preview dimensions");
    }

    /**
     * Reads {@code MAGIC, width, height, count} and then, per frame, a duration followed by big-endian ARGB
     * pixels, compressing each frame as it arrives and handing the animation to {@code firstFrame} after the
     * first. Fails on any truncation, trailing byte, bound or budget violation.
     */
    static MediaFrames read(InputStream stream, ByteBudget.Lease lease, Consumer<MediaFrames> firstFrame)
            throws IOException {
        var in = new DataInputStream(new BufferedInputStream(stream, 1 << 16));
        if (in.readInt() != MAGIC) throw new IOException("Invalid decoded preview header");
        int w = in.readInt(), h = in.readInt(), count = in.readInt();
        try (var writer = new MediaFrames.Writer(w, h, count, lease)) {
            int frameBytes = w * h * 4;
            lease.reserve(frameBytes);
            try {
                byte[] pixels = new byte[frameBytes];
                for (int i = 0; i < count; i++) {
                    int ms = in.readInt();
                    in.readFully(pixels);
                    writer.add(pixels, ms);
                    if (i == 0) firstFrame.accept(writer.frames());
                }
            } finally {
                lease.release(frameBytes);
            }
            if (in.read() != -1) throw new IOException("Unexpected decoded preview data");
            return writer.finish();
        }
    }
}
