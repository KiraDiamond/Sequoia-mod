package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class PreviewCancellationTest {
    @AfterEach
    void reset() {
        ImagePreviews.clear();
        ImagePreviewSettings.setting().setValue(true);
    }

    private static URI link(String name) {
        return URI.create("https://i.imgur.com/" + name + ".gif");
    }

    /** Occupies both workers until the returned latch is released. */
    private static CountDownLatch occupy(ThreadPoolExecutor workers) throws InterruptedException {
        var occupied = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++)
            workers.execute(
                    () -> {
                        occupied.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
        assertTrue(occupied.await(2, TimeUnit.SECONDS));
        return release;
    }

    @Test
    void clearingPreviewsRemovesQueuedTasks() throws Exception {
        var started = new AtomicInteger();
        try (var harness =
                new PreviewCacheHarness(
                        (link, cancellation, lease, first) -> {
                            started.incrementAndGet();
                            throw new IOException("Test task finished");
                        })) {
            var release = occupy(harness.workers());
            try {
                for (int i = 0; i < 3; i++) harness.cache().get(link("queued-" + i));
                assertEquals(3, harness.workers().getQueue().size());
                harness.cache().clear();
                assertEquals(
                        0,
                        harness.workers().getQueue().size(),
                        "Cancelled work must not occupy queue slots");
            } finally {
                release.countDown();
            }
            harness.workers().submit(() -> {}).get(2, TimeUnit.SECONDS);
            harness.workers().submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals(0, started.get(), "Obsolete tasks must never start");
            assertEquals(0, harness.pendingMain());
            assertEquals(0, harness.cache().inFlightBytes());
        }
    }

    @Test
    void disablingThenReenablingDoesNotReviveQueuedTasks() throws Exception {
        var field = ImagePreviews.class.getDeclaredField("DOWNLOADS");
        field.setAccessible(true);
        var downloads = (ThreadPoolExecutor) field.get(null);
        var cacheField = ImagePreviews.class.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        var cache = (PreviewCache) cacheField.get(null);
        var release = occupy(downloads);
        try {
            for (int i = 0; i < 3; i++) ImagePreviews.get(link("disabled-" + i));
            assertEquals(3, downloads.getQueue().size());
            ImagePreviewSettings.setting().setValue(false);
            var maintain = ImagePreviews.class.getDeclaredMethod("maintain");
            maintain.setAccessible(true);
            maintain.invoke(null);
            ImagePreviewSettings.setting().setValue(true);
            assertEquals(
                    0, downloads.getQueue().size(), "Cancelled work must not occupy queue slots");
        } finally {
            release.countDown();
        }
        downloads.submit(() -> {}).get(2, TimeUnit.SECONDS);
        downloads.submit(() -> {}).get(2, TimeUnit.SECONDS);
        assertEquals(0, cache.size(), "Re-enabling must not revive cleared previews");
        assertEquals(0, cache.inFlightBytes(), "No cancelled load may have started");
    }

    @Test
    void clearingPreviewsInterruptsAnActiveTask() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var aborted = new CountDownLatch(1);
        try (var harness =
                new PreviewCacheHarness(
                        (link, cancellation, lease, first) -> {
                            cancellation.attach(aborted::countDown);
                            entered.countDown();
                            try {
                                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                interrupted.countDown();
                                throw e;
                            }
                            throw new IOException("Test task finished");
                        })) {
            harness.cache().get(link("active"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            harness.cache().clear();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "Active work was not cancelled");
            assertTrue(aborted.await(2, TimeUnit.SECONDS), "Held resources were not aborted");
            harness.workers().submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals(0, harness.pendingMain(), "A cancelled load must not post a result");
        }
    }
}
