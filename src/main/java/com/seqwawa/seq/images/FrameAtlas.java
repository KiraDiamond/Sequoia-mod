package com.seqwawa.seq.images;

import java.io.IOException;

/**
 * Grid placement of every frame of one animation inside a single texture.
 *
 * <p>The decoder's bounds (sides of at most 256, at most 400 frames and 12 million pixels)
 * keep every valid animation within a 4096 by 3332 grid, so one texture always suffices on
 * hardware that supports 4096-pixel textures.
 */
record FrameAtlas(int frameWidth, int frameHeight, int columns, int width, int height) {
    static final int MAX_SIDE = 4096;

    /** Placement for frames of a validated animation, within this GPU's texture limit. */
    static FrameAtlas of(int frameWidth, int frameHeight, int frames, int maxTextureSide) throws IOException {
        int limit = Math.min(MAX_SIDE, maxTextureSide);
        int columns = Math.min(frames, limit / frameWidth);
        if (columns < 1) throw new IOException("Preview exceeds graphics texture limits");
        int rows = (frames + columns - 1) / columns;
        if ((long) rows * frameHeight > limit) throw new IOException("Preview exceeds graphics texture limits");
        return new FrameAtlas(frameWidth, frameHeight, columns, columns * frameWidth, rows * frameHeight);
    }

    int x(int frame) {
        return frame % columns * frameWidth;
    }

    int y(int frame) {
        return frame / columns * frameHeight;
    }

    long bytes() {
        return 4L * width * height;
    }
}
