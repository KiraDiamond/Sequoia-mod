package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.Test;

class MediaCancellationTest {
    @Test
    void cancellationAbortsEachAttachedResourceOnce() throws Exception {
        var cancellation = new MediaCancellation();
        var helper = new AtomicInteger();
        var request = new AtomicInteger();
        cancellation.attach(helper::incrementAndGet);
        // A download's request is attached and finished while the helper stays attached.
        cancellation.attach(request::incrementAndGet).close();
        cancellation.cancel();
        cancellation.cancel();
        assertEquals(1, helper.get(), "the helper abort must survive the request finishing");
        assertEquals(0, request.get(), "a finished resource is not aborted later");
        assertThrows(InterruptedIOException.class, cancellation::check);
    }

    @Test
    void resourceOpenedDuringCancellationIsAborted() {
        var cancellation = new MediaCancellation();
        cancellation.cancel();
        var aborts = new AtomicInteger();
        assertThrows(InterruptedIOException.class, () -> cancellation.attach(aborts::incrementAndGet));
        assertEquals(1, aborts.get());
    }

    @Test
    void cancelledWorkCannotDownloadOrStartADecoder() {
        var cancellation = new MediaCancellation();
        cancellation.cancel();
        assertThrows(
                InterruptedIOException.class,
                () -> MediaDownload.fetch(
                        URI.create("https://i.imgur.com/test.png"),
                        cancellation,
                        new ByteBudget(MediaFormat.MAX_BYTES).open()));
        assertThrows(InterruptedIOException.class, () -> IsolatedDecoder.start(cancellation));
    }

    @Test
    void cancellationAbortsAnActiveHttpBodyRead() throws Exception {
        var cancellation = new MediaCancellation();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = Executors.newSingleThreadExecutor();
        server.setExecutor(handlers);
        server.createContext("/media", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 1024);
                exchange.getResponseBody().write(1);
                exchange.getResponseBody().flush();
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        try (var executor = Executors.newSingleThreadExecutor();
                var client = HttpClients.createDefault()) {
            // Exercise the request boundary on loopback without changing the provider allowlist.
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/media");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            var result = executor.submit(() -> assertThrows(
                    IOException.class,
                    () -> MediaDownload.request(
                            client, uri, 2048, deadline, cancellation, new ByteBudget(1 << 20).open())));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            cancellation.cancel();
            result.get(2, TimeUnit.SECONDS);
        } finally {
            cancellation.cancel();
            release.countDown();
            server.stop(0);
            handlers.shutdownNow();
        }
    }
}
