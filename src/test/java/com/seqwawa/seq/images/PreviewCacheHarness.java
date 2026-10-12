package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** A preview cache with real workers and an explicit main-thread queue, as in the client. */
final class PreviewCacheHarness implements AutoCloseable {
    private final LinkedBlockingQueue<Runnable> mainThread = new LinkedBlockingQueue<>();
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    private final AtomicLong clock = new AtomicLong();
    private final PreviewCache cache;

    PreviewCacheHarness(PreviewCache.Loader loader) {
        cache = new PreviewCache(workers, mainThread::add, loader, clock::get);
    }

    /** Moves the cache's clock forward. */
    long advance(long nanos) {
        return clock.addAndGet(nanos);
    }

    PreviewCache cache() {
        return cache;
    }

    ThreadPoolExecutor workers() {
        return workers;
    }

    /** Runs the next completion the workers posted to the main thread. */
    void runMain() throws InterruptedException {
        Runnable task = mainThread.poll(30, TimeUnit.SECONDS);
        assertNotNull(task, "No preview load completed");
        task.run();
    }

    int pendingMain() {
        return mainThread.size();
    }

    /** Discards posted completions, as the client does while it disconnects. */
    void dropMain() {
        mainThread.clear();
    }

    /** Waits until every worker has returned its in-flight reservation. */
    void awaitInFlightReleased() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (cache.inFlightBytes() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
        org.junit.jupiter.api.Assertions.assertEquals(0, cache.inFlightBytes());
    }

    @Override
    public void close() throws InterruptedException {
        cache.clear();
        workers.shutdownNow();
        workers.awaitTermination(10, TimeUnit.SECONDS);
        BridgeMedia.clear();
    }
}
