package com.seqwawa.seq.images;

import java.io.IOException;

/** Thread-safe cap on bytes held by concurrent preview loads before they are cached. */
final class ByteBudget {
    static final class Exhausted extends IOException {
        Exhausted() {
            super("Preview memory budget exceeded");
        }
    }

    private final long capacity;
    private long used;

    ByteBudget(long capacity) {
        this.capacity = capacity;
    }

    synchronized long used() {
        return used;
    }

    Lease open() {
        return new Lease();
    }

    /** Bytes reserved by one load; closing it returns them all. */
    final class Lease implements AutoCloseable {
        private long held;
        private boolean closed;

        void reserve(long bytes) throws Exhausted {
            synchronized (ByteBudget.this) {
                if (closed || bytes < 0 || bytes > capacity - used) throw new Exhausted();
                used += bytes;
                held += bytes;
            }
        }

        void release(long bytes) {
            synchronized (ByteBudget.this) {
                long returned = Math.min(Math.max(0, bytes), held);
                held -= returned;
                used -= returned;
            }
        }

        @Override
        public void close() {
            synchronized (ByteBudget.this) {
                if (closed) return;
                closed = true;
                used -= held;
                held = 0;
            }
        }
    }
}
