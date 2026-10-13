package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
            workers.execute(() -> {
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
        try (var harness = new PreviewCacheHarness((link, cancellation, lease, first) -> {
            started.incrementAndGet();
            throw new IOException("Test task finished");
        })) {
            var release = occupy(harness.workers());
            try {
                for (int i = 0; i < 3; i++) harness.cache().get(link("queued-" + i));
                assertEquals(3, harness.workers().getQueue().size());
                harness.cache().clear();
                assertEquals(0, harness.workers().getQueue().size(), "Cancelled work must not occupy queue slots");
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
    void clearingPreviewsInterruptsAnActiveTask() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var aborted = new CountDownLatch(1);
        try (var harness = new PreviewCacheHarness((link, cancellation, lease, first) -> {
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

    @Test
    void clearingDuringLeaseRegistrationReturnsTheInFlightBudget() throws Exception {
        var registering = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var harness = new PreviewCacheHarness((link, cancellation, lease, first) -> {
            lease.reserve(1024);
            return null;
        })) {
            var field = PreviewCache.class.getDeclaredField("postedLeases");
            field.setAccessible(true);
            Set<ByteBudget.Lease> leases = ConcurrentHashMap.newKeySet();
            field.set(harness.cache(), new AbstractSet<ByteBudget.Lease>() {
                @Override
                public boolean add(ByteBudget.Lease lease) {
                    registering.countDown();
                    // Hold registration across clear(), including its worker interruption.
                    boolean interrupted = false;
                    try {
                        while (true) {
                            try {
                                assertTrue(resume.await(5, TimeUnit.SECONDS));
                                return leases.add(lease);
                            } catch (InterruptedException ignored) {
                                interrupted = true;
                            }
                        }
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                }

                @Override
                public Iterator<ByteBudget.Lease> iterator() {
                    return leases.iterator();
                }

                @Override
                public int size() {
                    return leases.size();
                }
            });
            harness.cache().get(link("registering"));
            assertTrue(registering.await(2, TimeUnit.SECONDS));
            assertEquals(1024, harness.cache().inFlightBytes());
            try {
                harness.cache().clear();
            } finally {
                resume.countDown();
            }
            harness.workers().shutdown();
            assertTrue(harness.workers().awaitTermination(5, TimeUnit.SECONDS));
            harness.dropMain();
            assertEquals(0, harness.cache().inFlightBytes(), "Disconnect must release late leases");
            assertTrue(leases.isEmpty(), "Cancelled leases must not remain registered");
        } finally {
            resume.countDown();
        }
    }
}
