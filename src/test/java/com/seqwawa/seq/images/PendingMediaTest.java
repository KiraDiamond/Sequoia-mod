package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PendingMediaTest {
    private static final URI BARE = URI.create("https://cdn.discordapp.com/attachments/1/2/test.gif");
    private static final URI SIGNED =
            URI.create("https://media.discordapp.net/attachments/1/2/test.gif?ex=abc&is=def&hm=123");

    @AfterEach
    void clearBridge() {
        BridgeMedia.clear();
    }

    @Test
    void unresolvedKlipyPageSaysWhereItsMediaAppearsInsteadOfWaiting() {
        var failure = assertThrows(
                MediaDownload.UnresolvedKlipyPage.class,
                () -> MediaDownload.fetch(URI.create("https://klipy.com/gifs/cat-please-please-cat")));
        var state = PreviewState.failedWith(failure, 100);
        assertInstanceOf(PreviewState.Failed.class, state);
        assertEquals(
                "Klipy page link; its GIF appears as a separate bridge message",
                PreviewState.status(state, 100 + TimeUnit.SECONDS.toNanos(60)));
    }

    @Test
    void unavailableDiscordAttachmentWaitsWithoutRetryingTheSameRequest() {
        for (String host : new String[] {"cdn.discordapp.com", "media.discordapp.net"})
            for (int code : new int[] {403, 404}) {
                var requests = new AtomicInteger();
                assertThrows(
                        MediaDownload.PendingMedia.class,
                        () -> MediaDownload.fetch(
                                URI.create(BARE.toString().replace("cdn.discordapp.com", host)),
                                (uri, limit, deadline) -> {
                                    requests.incrementAndGet();
                                    return new MediaDownload.Reply(code, null, new byte[0]);
                                },
                                System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
                assertEquals(1, requests.get());
            }
    }

    @Test
    void pendingMediaExpiresWithoutCallingItASafetyBlock() {
        var state = PreviewState.failedWith(new MediaDownload.PendingMedia(), 100);
        assertEquals("Waiting for Discord media...", PreviewState.status(state, 100 + TimeUnit.SECONDS.toNanos(6)));
        assertEquals(
                "Image link unavailable; resend it in Discord",
                PreviewState.status(state, 100 + TimeUnit.SECONDS.toNanos(30)));
    }

    @Test
    void otherFailuresNeverWaitForDiscord() {
        for (var reply : new MediaDownload.Reply[] {
            new MediaDownload.Reply(200, null, new byte[] {1, 2, 3}), new MediaDownload.Reply(404, null, new byte[0])
        }) {
            URI link = reply.status() == 404 ? URI.create("https://i.imgur.com/missing.gif") : BARE;
            IOException failure = assertThrows(
                    IOException.class,
                    () -> MediaDownload.fetch(
                            link, (uri, limit, deadline) -> reply, System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
            assertFalse(failure instanceof MediaDownload.PendingMedia);
            assertEquals(
                    "Image blocked, unavailable or outside safety limits",
                    PreviewState.status(PreviewState.failedWith(failure, 100), 100));
        }
    }

    @Test
    void delayedSignatureReplacesTheWaitingPreviewWithAFreshLoad() throws Exception {
        var loads = new CopyOnWriteArrayList<URI>();
        try (var harness = new PreviewCacheHarness((media, cancellation, lease, first) -> {
            loads.add(media);
            throw new MediaDownload.PendingMedia();
        })) {
            var waiting = harness.cache().get(BARE);
            harness.runMain();
            assertEquals(
                    "Waiting for Discord media...",
                    waiting.status(harness.cache().now()));
            BridgeMedia.rewrite(
                    "{\"type\":\"discord_media\",\"username\":\"Alice\",\"media_urls\":[\"" + SIGNED + "\"]}");
            var refreshed = harness.cache().get(BARE);
            assertNotSame(waiting, refreshed);
            assertEquals(SIGNED, refreshed.source);
            assertEquals("Loading image...", refreshed.status(harness.cache().now()));
            harness.runMain();
            assertEquals(List.of(BARE, SIGNED), loads);
        }
    }
}
