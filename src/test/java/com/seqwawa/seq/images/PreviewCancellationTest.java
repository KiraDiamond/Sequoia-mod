package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class PreviewCancellationTest {
    @AfterEach
    void reset() {
        ImagePreviews.clear();
        ImagePreviewSettings.setting().setValue(true);
    }

    @Test
    void clearingPreviewsRemovesQueuedTasks() throws Exception {
        queuedWorkDoesNotStart(false);
    }

    @Test
    void disablingThenReenablingDoesNotReviveQueuedTasks() throws Exception {
        queuedWorkDoesNotStart(true);
    }

    private void queuedWorkDoesNotStart(boolean disable) throws Exception {
        ImagePreviews.clear();
        var executor = downloads();
        var occupied = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var started = new AtomicInteger();
        for (int i = 0; i < 2; i++)
            executor.execute(
                    () -> {
                        occupied.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
        try {
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 3; i++) {
                enqueue(
                        "queued-" + i,
                        () -> {
                            started.incrementAndGet();
                            throw new IOException("Test task finished");
                        });
            }
            assertEquals(3, executor.getQueue().size());
            if (disable) {
                ImagePreviewSettings.setting().setValue(false);
                var maintain = ImagePreviews.class.getDeclaredMethod("maintain");
                maintain.setAccessible(true);
                maintain.invoke(null);
                ImagePreviewSettings.setting().setValue(true);
            } else ImagePreviews.clear();
            int queuedAfterClear = executor.getQueue().size();
            release.countDown();
            executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
            executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals(0, queuedAfterClear, "Cancelled work must not occupy queue slots");
            assertEquals(0, started.get(), "Obsolete tasks must never start");
        } finally {
            release.countDown();
            executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void clearingPreviewsInterruptsAnActiveTask() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            enqueue(
                    "active",
                    () -> {
                        entered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            interrupted.countDown();
                            throw e;
                        }
                        throw new IOException("Test task finished");
                    });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            ImagePreviews.clear();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "Active work was not cancelled");
        } finally {
            release.countDown();
        }
    }

    private static ThreadPoolExecutor downloads() throws Exception {
        var field = ImagePreviews.class.getDeclaredField("DOWNLOADS");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(null);
    }

    private interface Task {
        void run() throws Exception;
    }

    @SuppressWarnings("unchecked")
    private static void enqueue(String key, Task body) throws Exception {
        var preview = new ImagePreviews.Preview();
        var cache = ImagePreviews.class.getDeclaredField("CACHE");
        cache.setAccessible(true);
        ((Map<String, ImagePreviews.Preview>) cache.get(null)).put(key, preview);
        Class<?> taskType = Class.forName(ImagePreviews.class.getName() + "$DecodeTask");
        Object task =
                Proxy.newProxyInstance(
                        taskType.getClassLoader(),
                        new Class<?>[] {taskType},
                        (proxy, method, args) -> {
                            body.run();
                            return null;
                        });
        var load =
                ImagePreviews.class.getDeclaredMethod(
                        "load", String.class, ImagePreviews.Preview.class, taskType);
        load.setAccessible(true);
        load.invoke(null, key, preview, task);
    }
}
