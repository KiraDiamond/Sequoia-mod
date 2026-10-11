package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class PendingMediaTest {
    private static final URI BARE =
            URI.create("https://cdn.discordapp.com/attachments/1/2/test.gif");
    private static final URI SIGNED =
            URI.create(
                    "https://media.discordapp.net/attachments/1/2/test.gif?ex=abc&is=def&hm=123");

    @AfterEach
    void clearBridge() {
        BridgeMedia.clear();
    }

    @Test
    void unresolvedKlipyPageHasExplicitPendingState() {
        assertThrows(
                MediaDownload.PendingMedia.class,
                () ->
                        MediaDownload.fetch(
                                URI.create("https://klipy.com/gifs/cat-please-please-cat")));
    }

    @Test
    void unavailableDiscordAttachmentWaitsWithoutRetryingTheSameRequest() {
        for (String host : new String[] {"cdn.discordapp.com", "media.discordapp.net"})
            for (int code : new int[] {403, 404}) {
                var requests = new AtomicInteger();
                assertThrows(
                        MediaDownload.PendingMedia.class,
                        () ->
                                MediaDownload.fetch(
                                        URI.create(
                                                BARE.toString()
                                                        .replace("cdn.discordapp.com", host)),
                                        (uri, limit, deadline) -> {
                                            requests.incrementAndGet();
                                            return new MediaDownload.Reply(code, null, new byte[0]);
                                        },
                                        System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
                assertEquals(1, requests.get());
            }
    }

    @Test
    void pendingMessageExpiresWithoutCallingItASafetyBlock() {
        var preview = new ImagePreviews.Preview();
        preview.failed(new CompletionException(new MediaDownload.PendingMedia()), 100);
        assertEquals(
                "Waiting for Discord media...",
                preview.statusMessage(100 + TimeUnit.SECONDS.toNanos(6)));
        assertEquals(
                "Image link unavailable; resend it in Discord",
                preview.statusMessage(100 + TimeUnit.SECONDS.toNanos(30)));
    }

    @Test
    void realFormatFailureIsNeverHiddenAsWaiting() {
        IOException failure =
                assertThrows(
                        IOException.class,
                        () ->
                                MediaDownload.fetch(
                                        BARE,
                                        (uri, limit, deadline) ->
                                                new MediaDownload.Reply(
                                                        200, null, new byte[] {1, 2, 3}),
                                        System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
        assertFalse(failure instanceof MediaDownload.PendingMedia);
        var preview = new ImagePreviews.Preview();
        preview.failed(failure, 100);
        assertEquals(
                "Image blocked, unavailable or outside safety limits", preview.statusMessage(100));
    }

    @Test
    void otherProviderFailureDoesNotWaitForDiscord() {
        IOException failure =
                assertThrows(
                        IOException.class,
                        () ->
                                MediaDownload.fetch(
                                        URI.create("https://i.imgur.com/missing.gif"),
                                        (uri, limit, deadline) ->
                                                new MediaDownload.Reply(404, null, new byte[0]),
                                        System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
        assertFalse(failure instanceof MediaDownload.PendingMedia);
    }

    @Test
    void delayedSignatureReplacesTheFailedSourceAndPendingStatusClears() {
        BridgeMedia.clear();
        var preview = new ImagePreviews.Preview();
        preview.source = BARE;
        preview.failed(new IOException("previous failure"), 100);
        preview.failed(new MediaDownload.PendingMedia(), 101);
        assertNull(preview.error);
        assertEquals("Waiting for Discord media...", preview.statusMessage(102));
        BridgeMedia.rewrite(
                "{\"type\":\"discord_media\",\"username\":\"Alice\",\"media_urls\":[\""
                        + SIGNED
                        + "\"]}");
        assertNotEquals(preview.source, BridgeMedia.resolve(BARE));
        assertEquals(SIGNED, BridgeMedia.resolve(BARE));
        var next = new ImagePreviews.Preview();
        assertEquals("Loading image...", next.statusMessage(103));
    }
}
