package com.seqwawa.seq.images;

import java.io.IOException;
import java.util.Arrays;

/** One validated animation loop: per-frame durations and their cumulative end times. */
final class FrameTimeline {
    static final int MIN_DURATION_MS = 1;
    static final int MAX_DURATION_MS = 655_350;

    private final int[] durations;
    private final long[] ends;

    private FrameTimeline(int[] durations, long[] ends) {
        this.durations = durations;
        this.ends = ends;
    }

    static FrameTimeline of(int[] durations) throws IOException {
        if (durations.length == 0) throw new IOException("Invalid preview timing");
        var ends = new long[durations.length];
        long total = 0;
        for (int i = 0; i < durations.length; i++) {
            if (!validDuration(durations[i])) throw new IOException("Invalid preview timing");
            total += durations[i];
            ends[i] = total;
        }
        return new FrameTimeline(durations.clone(), ends);
    }

    static boolean validDuration(int durationMs) {
        return durationMs >= MIN_DURATION_MS && durationMs <= MAX_DURATION_MS;
    }

    int frameCount() {
        return durations.length;
    }

    int duration(int frame) {
        return durations[frame];
    }

    long cycleMs() {
        return ends[ends.length - 1];
    }

    /** Start of {@code frame} within the loop, in milliseconds. */
    long startMs(int frame) {
        return frame == 0 ? 0 : ends[frame - 1];
    }

    /** Frame shown {@code elapsedMs} after playback started, looping forever. */
    int frameAt(long elapsedMs) {
        long time = Math.floorMod(elapsedMs, cycleMs());
        // First frame whose end lies after the loop position.
        int found = Arrays.binarySearch(ends, time);
        return found >= 0 ? found + 1 : -found - 1;
    }
}
