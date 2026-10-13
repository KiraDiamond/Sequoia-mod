package com.seqwawa.seq.images;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A decoded animation held compressed for its whole cached lifetime.
 *
 * <p>Each frame is stored as its byte-wise difference from the previous frame (the first from transparent
 * black), split into R, G, B and A planes and deflated, so static regions and opaque alpha cost almost
 * nothing and the animation is never fully expanded. Frames are published as the {@link Writer} encodes
 * them, so playback can start while the rest is still compressing.
 */
final class MediaFrames {
    static final int MIN_DURATION_MS = 1;
    static final int MAX_DURATION_MS = 655_350;

    private enum Stream {
        FILLING,
        COMPLETE,
        FAILED
    }

    private final int width;
    private final int height;
    private final byte[][] chunks;
    // Cumulative end time of each frame within the loop, in milliseconds.
    private final long[] ends;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition arrived = lock.newCondition();
    // Frames below this index are fully written; the volatile write publishes them.
    private volatile int available;
    private volatile Stream stream = Stream.FILLING;
    private volatile long bytes;

    private MediaFrames(int width, int height, int frames) {
        this.width = width;
        this.height = height;
        chunks = new byte[frames][];
        ends = new long[frames];
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** Frames in the finished animation, including any not yet encoded. */
    int frameCount() {
        return chunks.length;
    }

    int available() {
        return available;
    }

    boolean complete() {
        return stream == Stream.COMPLETE;
    }

    static boolean validDuration(int durationMs) {
        return durationMs >= MIN_DURATION_MS && durationMs <= MAX_DURATION_MS;
    }

    /**
     * Frame due {@code elapsedMs} after playback started, looping once complete. While frames are still
     * arriving this may return {@link #available()}, meaning the frame is not ready yet.
     */
    int frameAt(long elapsedMs) {
        boolean looping = complete(); // read before available, which publish() writes first
        int count = available;
        long time = looping ? Math.floorMod(elapsedMs, ends[count - 1]) : Math.max(0, elapsedMs);
        int found = Arrays.binarySearch(ends, 0, count, time);
        return found >= 0 ? found + 1 : -found - 1;
    }

    /** Start of a published frame within the loop, in milliseconds. */
    long startMs(int frame) {
        return frame == 0 ? 0 : ends[frame - 1];
    }

    int duration(int frame) {
        return (int) (ends[frame] - startMs(frame));
    }

    long cycleMs() {
        return ends[ends.length - 1];
    }

    /** RGBA bytes in one expanded frame. */
    int frameBytes() {
        return width * height * 4;
    }

    /** Retained heap bytes once complete: compressed frames plus per-frame bookkeeping. */
    long bytes() {
        return bytes;
    }

    /** Waits until {@code frame} is published; false if the stream failed before reaching it. */
    boolean awaitFrame(int frame) throws InterruptedException {
        if (frame < available) return true;
        lock.lock();
        try {
            while (frame >= available && stream == Stream.FILLING) arrived.await();
            return frame < available;
        } finally {
            lock.unlock();
        }
    }

    private void publish(int count, Stream state) {
        lock.lock();
        try {
            available = count;
            stream = state;
            arrived.signalAll();
        } finally {
            lock.unlock();
        }
    }

    Reader reader() {
        return new Reader();
    }

    /** Encodes helper frames as they stream in; the expanded animation never exists at once. */
    static final class Writer implements AutoCloseable {
        private static final int FRAME_OVERHEAD = 32;

        private final MediaFrames frames;
        private final int pixels;
        private final byte[] previous;
        private final byte[] planes;
        private final byte[] output;
        private final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        private final ByteBudget.Lease lease;
        private final long scratchBytes;
        private int count;
        private long bytes;
        private long cycle;
        private boolean closed;

        Writer(int width, int height, int frameCount, ByteBudget.Lease lease) throws IOException {
            DecodedFrames.validate(width, height, frameCount);
            this.lease = lease;
            pixels = width * height;
            int frameBytes = pixels * 4;
            // zlib's conservative deflateBound(), so one call always completes a frame.
            int outputBytes = frameBytes + (frameBytes >> 3) + (frameBytes >> 6) + 64;
            scratchBytes = 2L * frameBytes + outputBytes;
            lease.reserve(scratchBytes);
            frames = new MediaFrames(width, height, frameCount);
            previous = new byte[frameBytes];
            planes = new byte[frameBytes];
            output = new byte[outputBytes];
        }

        /** The animation being written; readers see each frame once it is added. */
        MediaFrames frames() {
            return frames;
        }

        /** Adds and publishes the next frame of big-endian ARGB pixels, as the helper emits them. */
        void add(byte[] argb, int durationMs) throws IOException {
            if (closed || count == frames.chunks.length || argb.length != previous.length || !validDuration(durationMs))
                throw new IOException("Invalid decoded frame");
            for (int p = 0, i = 0; p < pixels; p++, i += 4) {
                planes[p] = (byte) (argb[i + 1] - previous[i + 1]);
                planes[pixels + p] = (byte) (argb[i + 2] - previous[i + 2]);
                planes[2 * pixels + p] = (byte) (argb[i + 3] - previous[i + 3]);
                planes[3 * pixels + p] = (byte) (argb[i] - previous[i]);
            }
            System.arraycopy(argb, 0, previous, 0, argb.length);
            deflater.reset();
            deflater.setInput(planes);
            deflater.finish();
            int length = deflater.deflate(output);
            if (!deflater.finished()) throw new IOException("Preview frame did not compress");
            lease.reserve(length + FRAME_OVERHEAD);
            bytes += length + FRAME_OVERHEAD;
            cycle += durationMs;
            frames.chunks[count] = Arrays.copyOf(output, length);
            frames.ends[count] = cycle;
            frames.publish(++count, Stream.FILLING);
        }

        MediaFrames finish() throws IOException {
            if (closed || count != frames.chunks.length) throw new IOException("Truncated decoded preview");
            frames.bytes = bytes;
            frames.publish(count, Stream.COMPLETE);
            close();
            return frames;
        }

        /** Frees the compressor and scratch space; closing before {@link #finish()} fails the stream. */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            deflater.end();
            lease.release(scratchBytes);
            if (!frames.complete()) frames.publish(count, Stream.FAILED);
        }
    }

    /** Rebuilds published frames once each, in display order. Not thread-safe. */
    final class Reader implements AutoCloseable {
        private final Inflater inflater = new Inflater();
        private final byte[] planes = new byte[frameBytes()];
        private final byte[] frame = new byte[frameBytes()];
        private final byte[] overflow = new byte[1];
        private int next;

        /** Returns the next frame as RGBA bytes, in an array the following call reuses. */
        byte[] next() throws IOException {
            if (next >= available) throw new IOException("Preview frame not decoded yet");
            inflate(chunks[next]);
            int pixels = width * height;
            for (int p = 0, i = 0; p < pixels; p++, i += 4) {
                frame[i] += planes[p];
                frame[i + 1] += planes[pixels + p];
                frame[i + 2] += planes[2 * pixels + p];
                frame[i + 3] += planes[3 * pixels + p];
            }
            next++;
            return frame;
        }

        private void inflate(byte[] chunk) throws IOException {
            inflater.reset();
            inflater.setInput(chunk);
            try {
                // The zlib trailer may remain once the output is full; it must end the stream.
                if (inflater.inflate(planes) != planes.length
                        || inflater.inflate(overflow) != 0
                        || !inflater.finished()
                        || inflater.getRemaining() != 0) throw new IOException("Corrupt preview cache");
            } catch (DataFormatException corrupt) {
                throw new IOException("Corrupt preview cache", corrupt);
            }
        }

        @Override
        public void close() {
            inflater.end();
        }
    }
}
