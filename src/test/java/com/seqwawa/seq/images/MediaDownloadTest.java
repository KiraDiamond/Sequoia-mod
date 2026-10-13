package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MediaDownloadTest {
    private static final URI GIF = URI.create("https://cdn.discordapp.com/x.gif");

    private static long deadline() {
        return System.nanoTime() + 2_000_000_000L;
    }

    @Test
    void blockedInitialAndRedirectDestinationsAreNeverRequested() {
        var calls = new AtomicInteger();
        MediaDownload.Exchange exchange = (uri, limit, end) -> {
            calls.incrementAndGet();
            return new MediaDownload.Reply(302, "http://127.0.0.1/private", new byte[0]);
        };
        assertThrows(
                IOException.class,
                () -> MediaDownload.fetch(URI.create("https://localhost/x.gif"), exchange, deadline()));
        assertEquals(0, calls.get());
        assertThrows(IOException.class, () -> MediaDownload.fetch(GIF, exchange, deadline()));
        assertEquals(1, calls.get());
        for (String url : new String[] {"file:///tracking.gif", "https://cdn.discordapp.com%2elogger.invalid/a.gif"})
            assertThrows(IOException.class, () -> MediaDownload.fetch(URI.create(url)), url);
    }

    @Test
    void redirectLoopsHaveAFixedRequestBudget() {
        var calls = new AtomicInteger();
        MediaDownload.Exchange loop = (uri, limit, end) -> {
            calls.incrementAndGet();
            return new MediaDownload.Reply(302, "/again.gif", new byte[0]);
        };
        assertThrows(IOException.class, () -> MediaDownload.fetch(GIF, loop, deadline()));
        assertEquals(4, calls.get());
    }

    @Test
    void expiredDownloadsDoNotStartAndBodiesAreBounded() throws Exception {
        MediaDownload.Exchange late = (uri, limit, end) -> fail("Request started after deadline");
        assertThrows(IOException.class, () -> MediaDownload.fetch(GIF, late, System.nanoTime() - 1));
        assertArrayEquals(
                new byte[32], MediaDownload.readLimited(new ByteArrayInputStream(new byte[32]), 32, -1, deadline()));
        assertThrows(
                IOException.class,
                () -> MediaDownload.readLimited(new ByteArrayInputStream(new byte[33]), 32, -1, deadline()));
        assertThrows(
                IOException.class,
                () -> MediaDownload.readLimited(new ByteArrayInputStream(new byte[33]), 32, 33, deadline()));
        assertThrows(
                IOException.class,
                () -> MediaDownload.readLimited(new ByteArrayInputStream(new byte[8]), 32, 16, deadline()));
        assertThrows(
                IOException.class,
                () -> MediaDownload.readLimited(new ByteArrayInputStream(new byte[1]), 32, -1, System.nanoTime() - 1));
    }

    @Test
    void tenorPagesResolveToTheirMediaHost() throws Exception {
        byte[] gif = "GIF89a....".getBytes(StandardCharsets.US_ASCII);
        String page = "<meta property=\"og:image\" content=\"https://media1.tenor.com/m/a/cat.gif\">";
        var requested = new ArrayList<URI>();
        MediaDownload.Exchange exchange = (uri, limit, end) -> {
            requested.add(uri);
            byte[] body = uri.getHost().equals("tenor.com") ? page.getBytes(StandardCharsets.UTF_8) : gif;
            return new MediaDownload.Reply(200, null, body);
        };
        assertArrayEquals(
                gif, MediaDownload.fetch(URI.create("https://tenor.com/view/cat-gif-1"), exchange, deadline()));
        assertEquals(URI.create("https://media1.tenor.com/m/a/cat.gif"), requested.get(1));
    }

    @Test
    void mp4FromAnImageLinkIsRejected() {
        byte[] mp4 = {0, 0, 0, 12, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        assertThrows(
                IOException.class,
                () -> MediaDownload.fetch(
                        GIF, (uri, limit, end) -> new MediaDownload.Reply(200, null, mp4), deadline()));
    }
}
