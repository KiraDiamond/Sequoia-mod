package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

class HiddenMediaTest {
    private static final String GIF = "https://i.imgur.com/cat.gif";

    @Test
    void removesMediaAndItsWrappersWithoutLeavingEmptyChatLines() {
        for (String wrapped : List.of(GIF, "<" + GIF + ">", "[" + GIF + "]", "(" + GIF + ")", "[cat](" + GIF + ")")) {
            assertEquals("", HiddenMedia.filter(wrapped + ".", List.of()), wrapped);
            assertEquals("hello there", HiddenMedia.filter("hello " + wrapped + " there", List.of()), wrapped);
            assertEquals("hello\nthere", HiddenMedia.filter("hello\n" + wrapped + "\nthere", List.of()), wrapped);
        }
        assertEquals("hello", HiddenMedia.filter(GIF + "\n\nhello\n" + GIF, List.of()));
        assertEquals("hello there", HiddenMedia.filter("hello " + GIF + " " + GIF + " there", List.of()));
    }

    @Test
    void keepsOrdinaryLinksTextAndPunctuation() {
        String ordinary = "  hello, world!\nhttps://example.com/page?file=cat.gif\n";
        assertSame(ordinary, HiddenMedia.filter(ordinary, List.of()));
        assertEquals("look: and https://example.com/page!",
                HiddenMedia.filter("look: " + GIF + " and https://example.com/page!", List.of()));
        assertEquals("thanks!", HiddenMedia.filter("thanks! " + GIF, List.of()));
        assertEquals("!!!", HiddenMedia.filter("!!! " + GIF, List.of()));
        assertEquals("!!!", HiddenMedia.filter(GIF + " !!!", List.of()));
    }

    @Test
    void metadataHidesExtensionlessMediaAndMatchesSignedDiscordAttachmentIdentity() {
        String media = "https://example.com/assets/123";
        assertEquals("hello", HiddenMedia.filter("hello " + media, List.of(media)));
        assertEquals(media + "-unrelated", HiddenMedia.filter(media + "-unrelated", List.of(media)));
        String original = "https://cdn.discordapp.com/attachments/1/2/asset";
        String proxy = "https://media.discordapp.net/attachments/1/2/asset?ex=abc&is=def&hm=123";
        assertEquals("", HiddenMedia.filter(original, List.of(proxy)));
        String other = original.replace("/2/", "/3/");
        assertSame(other, HiddenMedia.filter(other, List.of(proxy)));
    }

    @Test
    void hidesEveryMediaLinkBeyondThePreviewLimitWithoutAuthorizingNewDownloads() {
        assertEquals("", HiddenMedia.filter(String.join(" ", java.util.Collections.nCopies(40, GIF)), List.of()));
        for (String url : List.of("https://other.example/a.webp", "https://other.example/a.mp4?x=1",
                "https://giphy.com/gifs/hello", "https://tenor.com/view/cat-gif-1"))
            assertEquals("", HiddenMedia.filter(url, List.of()));
        assertNull(MediaLinks.imageUri("https://other.example/a.webp"));
    }
}
