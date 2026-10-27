package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaLinksTest {
    @Test
    void fxTwitterAllowsOnlyDirectGifConversionEndpoints() {
        for (String url : List.of(
                "https://gif.fxtwitter.com/tweet_video/HNfKdlnWkAAiO5j.webp",
                "https://GIF.FXTWITTER.COM:443/tweet_video/abc_123-xyz.gif")) {
            assertEquals(URI.create(url), MediaLinks.imageUri(url));
            assertTrue(MediaLinks.isAnimated(URI.create(url)), "converted WebP is labelled as a GIF");
        }
        for (String url : List.of(
                "http://gif.fxtwitter.com/tweet_video/a.webp",
                "https://fxtwitter.com/tweet_video/a.webp",
                "https://video.fxtwitter.com/tweet_video/a.webp",
                "https://gif.fxtwitter.com.evil.test/tweet_video/a.webp",
                "https://evil.gif.fxtwitter.com/tweet_video/a.webp",
                "https://user@gif.fxtwitter.com/tweet_video/a.webp",
                "https://gif.fxtwitter.com:444/tweet_video/a.webp",
                "https://gif.fxtwitter.com/tweet_video/a.webp#fragment",
                "https://gif.fxtwitter.com/tweet_video/a.webp?url=https://evil.test/a.webp",
                "https://gif.fxtwitter.com/other/a.webp",
                "https://gif.fxtwitter.com/tweet_video/nested/a.webp",
                "https://gif.fxtwitter.com/tweet_video/../a.webp",
                "https://gif.fxtwitter.com/tweet_video/%2e%2e/a.webp",
                "https://gif.fxtwitter.com/tweet_video/%61.webp",
                "https://gif.fxtwitter.com/tweet_video/a%2Fb.webp",
                "https://gif.fxtwitter.com/tweet_video/a%5Cb.webp",
                "https://gif.fxtwitter.com/tweet_video/a.png",
                "https://gif.fxtwitter.com/tweet_video/a.mp4",
                "https://gif.fxtwitter.com/tweet_video/.webp")) {
            assertNull(MediaLinks.imageUri(url), url);
            assertFalse(MediaLinks.allowed(URI.create(url)), "download gate: " + url);
        }
    }

    @Test
    void findsMediaLinksAndKeepsTheirQuery() {
        String url =
                "https://cdn.discordapp.com/attachments/725134391021666366/1556530112252346429/togif.gif?backend=b2";
        assertEquals(List.of(URI.create(url)), MediaLinks.find("Topdiamond2006: " + url));
        assertEquals(
                List.of(URI.create("https://media.tenor.com/a.gif"), URI.create("https://i.imgur.com/b.png")),
                MediaLinks.find("see <https://media.tenor.com/a.gif> (https://i.imgur.com/b.png)."));
        assertEquals(List.of(URI.create("https://Tenor.com/view/x")), MediaLinks.find("https://Tenor.com/view/x"));
        assertTrue(
                MediaLinks.find("hello https://github.com/KiraDiamond/AS7-Mod").isEmpty());
        assertTrue(MediaLinks.find("https://cdn.discordapp.com/a.mp4").isEmpty());
    }

    @Test
    void webpUsesTheSameHttpsHostRestrictions() {
        assertEquals(URI.create("https://i.imgur.com/a.webp?x=1"), MediaLinks.imageUri("https://i.imgur.com/a.webp?x=1"));
        for (String url : List.of("https://other.example/a.webp", "http://i.imgur.com/a.webp",
                "https://i.imgur.com.evil.test/a.webp", "https://i.imgur.com:444/a.webp"))
            assertNull(MediaLinks.imageUri(url), url);
    }

    @Test
    void acceptsExactlyTheNumberedGiphyHosts() {
        for (int i = 0; i <= 4; i++) {
            String url = "https://media" + i + ".giphy.com/media/preview/giphy.gif";
            assertEquals(List.of(URI.create(url)), MediaLinks.find(url), url);
        }
        assertTrue(MediaLinks.allowed(URI.create("https://MEDIA2.giphy.com/media/a/giphy.gif")));
        for (String host :
                List.of("media5.giphy.com", "media01.giphy.com", "media0.giphy.com.evil.test", "evil.media0.giphy.com"))
            assertTrue(MediaLinks.find("https://" + host + "/preview.gif").isEmpty(), host);
    }

    @Test
    void hostileUrlFormsAreRejected() {
        // Downloads, redirects and the DNS gate all rely on these rules.
        for (String url : List.of(
                "file:///secret.png",
                "data:image/gif;base64,R0lGODlh",
                "http://cdn.discordapp.com/x.gif",
                "https://127.0.0.1/x.gif",
                "https://localhost/a.png",
                "https://cdn.discordapp.com.attacker.test/x.gif",
                "https://cdn.discordapp.com%2elogger.invalid/tracking.gif",
                "https://user@cdn.discordapp.com/x.gif",
                "https://cdn.discordapp.com@evil.test/a.gif",
                "https://cdn.discordapp.com:8080/x.gif",
                "https://media2.giphy.com:8443/media/a/giphy.gif",
                "https://cdn.discordapp.com/x%0d%0a.gif",
                "https://cdn.discordapp.com/x.gif?x=%00",
                "https://cdn.discordapp.com/x.gif#fragment",
                "https://cdn.discordapp.com/" + "x".repeat(4100) + ".gif")) assertNull(MediaLinks.imageUri(url), url);
        assertFalse(MediaLinks.allowed(null));
        assertNull(MediaLinks.imageUri(null));
    }
}
