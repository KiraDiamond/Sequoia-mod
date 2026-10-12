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
 * <p>Each frame is stored as its byte-wise difference from the previous frame (the first from
 * transparent black), split into R, G, B and A planes and deflated separately. Static regions
 * and opaque alpha become zero runs that DEFLATE removes, so even a playing animation is never
 * fully expanded in memory: {@link Reader} rebuilds one frame at a time, in order.
 *
 * <p>Frames are published as they are encoded, so playback can start with the first frame while
 * the {@link Writer} is still compressing the rest. Published frames never change.
 */
final class MediaFrames {
    private enum Stream {
        FILLING,
        COMPLETE,
        FAILED
    }

    private final int width;
    private final int height;
    private final byte[][] chunks;
    private final int[] durations;
    private final long[] ends;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition arrived = lock.newCondition();
    // Frames below this index are fully written; the volatile write publishes them.
    private volatile int available;
    private volatile Stream stream = Stream.FILLING;
    private volatile FrameTimeline timeline;
    private volatile long bytes;

    private MediaFrames(int width, int height, int frames) {
        this.width = width;
        this.height = height;
        chunks = new byte[frames][];
        durations = new int[frames];
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

    /** Timing of the finished animation. */
    FrameTimeline timeline() {
        var finished = timeline;
        if (finished == null) throw new IllegalStateException("Animation still decoding");
        return finished;
    }

    /**
     * Frame due {@code elapsedMs} after playback started. While frames are still arriving this
     * does not loop and may return {@link #available()}, meaning the frame is not ready yet.
     */
    int frameAt(long elapsedMs) {
        var finished = timeline;
        if (finished != null) return finished.frameAt(elapsedMs);
        return FrameTimeline.frameEndingAfter(ends, available, Math.max(0, elapsedMs));
    }

    /** Start of a published frame within the loop, in milliseconds. */
    long startMs(int frame) {
        return FrameTimeline.startMs(ends, frame);
    }

    /** RGBA bytes in one expanded frame. */
    int frameBytes() {
        return width * height * 4;
    }

    /** Retained heap bytes once complete: compressed frames plus per-frame bookkeeping. */
    long bytes() {
        return bytes;
    }

    /**
     * Waits until {@code frame} is published.
     *
     * @return false if the stream failed before reaching it
     */
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

        /**
         * Adds and publishes the next frame.
         *
         * @param argb big-endian ARGB pixels exactly as the helper emits them; only read
         * @param durationMs frame duration
         * @throws IOException if the frame is malformed, extra, or exceeds the memory budget
         */
        void add(byte[] argb, int durationMs) throws IOException {
            if (closed
                    || count == frames.chunks.length
                    || argb.length != previous.length
                    || !FrameTimeline.validDuration(durationMs))
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
            frames.durations[count] = durationMs;
            frames.ends[count] = cycle;
            frames.publish(++count, Stream.FILLING);
        }

        MediaFrames finish() throws IOException {
            if (closed || count != frames.chunks.length)
                throw new IOException("Truncated decoded preview");
            frames.timeline = FrameTimeline.of(frames.durations);
            frames.bytes = bytes;
            frames.publish(count, Stream.COMPLETE);
            close();
            return frames;
        }

        /**
         * Frees the compressor and scratch reservation; finished chunks stay reserved. Closing
         * before {@link #finish()} fails the stream, releasing anyone waiting for frames.
         */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            deflater.end();
            lease.release(scratchBytes);
            if (!frames.complete()) frames.publish(count, Stream.FAILED);
        }
    }

    /** Rebuilds published frames in display order, looping once complete. Not thread-safe. */
    final class Reader implements AutoCloseable {
        private final Inflater inflater = new Inflater();
        private final byte[] planes = new byte[frameBytes()];
        private final byte[] frame = new byte[frameBytes()];
        private final byte[] overflow = new byte[1];
        private int next;

        /**
         * Returns the next frame as RGBA bytes. The array is reused by the following call.
         *
         * @throws IOException if the frame is not published yet or fails its integrity check
         */
        byte[] next() throws IOException {
            if (next == chunks.length) {
                Arrays.fill(frame, (byte) 0);
                next = 0;
            }
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
                int read = 0;
                while (read < planes.length) {
                    int count = inflater.inflate(planes, read, planes.length - read);
                    if (count == 0 && (inflater.finished() || inflater.needsInput())) break;
                    read += count;
                }
                // The zlib trailer may remain once the output is full; it must end the stream.
                if (read != planes.length
                        || (!inflater.finished() && inflater.inflate(overflow) != 0)
                        || !inflater.finished()
                        || inflater.getRemaining() != 0)
                    throw new IOException("Corrupt preview cache");
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
