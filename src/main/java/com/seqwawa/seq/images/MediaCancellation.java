package com.seqwawa.seq.images;

import java.io.InterruptedIOException;

final class MediaCancellation {
    private volatile boolean cancelled;
    private Runnable abort;

    boolean isCancelled() {
        return cancelled;
    }

    void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Image preview cancelled");
    }

    void attach(Runnable action) throws InterruptedIOException {
        synchronized (this) {
            if (!cancelled && !Thread.currentThread().isInterrupted()) {
                abort = action;
                return;
            }
        }
        // Cancellation may race with opening a request or starting a helper.
        action.run();
        throw new InterruptedIOException("Image preview cancelled");
    }

    synchronized void detach() {
        abort = null;
    }

    void cancel() {
        Runnable action;
        synchronized (this) {
            cancelled = true;
            action = abort;
            abort = null;
        }
        if (action != null) action.run();
    }
}
