package com.seqwawa.seq.images;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

/** Cancels one preview load and aborts whatever blocking resources it currently holds. */
final class MediaCancellation {
    /** Detaches one abort action once its resource has finished. */
    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private volatile boolean cancelled;
    private final List<Runnable> aborts = new ArrayList<>();

    boolean isCancelled() {
        return cancelled;
    }

    void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Image preview cancelled");
    }

    /**
     * Runs {@code action} if the load is cancelled while the returned registration is open.
     * Several resources (a request and a helper process) can be attached at once.
     *
     * @throws InterruptedIOException after running {@code action} if already cancelled
     */
    Registration attach(Runnable action) throws InterruptedIOException {
        synchronized (this) {
            if (!cancelled && !Thread.currentThread().isInterrupted()) {
                aborts.add(action);
                return () -> {
                    synchronized (this) {
                        aborts.remove(action);
                    }
                };
            }
        }
        // Cancellation may race with opening a request or starting a helper.
        action.run();
        throw new InterruptedIOException("Image preview cancelled");
    }

    void cancel() {
        List<Runnable> actions;
        synchronized (this) {
            cancelled = true;
            actions = List.copyOf(aborts);
            aborts.clear();
        }
        actions.forEach(Runnable::run);
    }
}
