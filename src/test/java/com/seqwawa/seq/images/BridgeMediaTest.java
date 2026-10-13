package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BridgeMediaTest {
    private static final String BARE =
            "https://cdn.discordapp.com/attachments/361194799744679947/1552730344417923202/IMG_0045.gif";
    private static final String SIGNED =
            BARE.replace("cdn.discordapp.com", "media.discordapp.net") + "?ex=6ac92152&is=6ac7cfd2&hm=abcdef&";
    private static final String PAGE = "https://klipy.com/gifs/wynncraft-hello-chat";
    private static final String MP4 =
            "https://static.klipy.com/ii/4e7bea9f7a3371424e6c16ebc93252fe/62/15/xiDo32fjTTjtLylez.mp4";
    private static final String GIF = MP4.replace(".mp4", ".gif");
    private static final String PROXY = "https://images-ext-1.discordapp.net/external/hash/https/" + MP4.substring(8);

    @BeforeEach
    void reset() {
        BridgeMedia.clear();
    }

    private static String payload(String type, String message, String... urls) {
        var json = new JsonObject();
        json.addProperty("type", type);
        json.addProperty("username", "Alice");
        json.addProperty("discord_id", "12345");
        if (message != null) json.addProperty("message", message);
        var media = new JsonArray();
        for (String url : urls) media.add(url);
        json.add("media_urls", media);
        return json.toString();
    }

    private static JsonObject json(String payload) {
        return JsonParser.parseString(payload).getAsJsonObject();
    }

    private static URI resolve(String link) {
        return BridgeMedia.resolve(URI.create(link));
    }

    @Test
    void signedAttachmentReplacesTheBareLinkAndKeepsTheRestOfThePayload() {
        var result = json(BridgeMedia.rewrite(payload("discord_chat", "look " + BARE, SIGNED)));
        assertEquals("look " + SIGNED, result.get("message").getAsString());
        assertEquals("Alice", result.get("username").getAsString());
        assertEquals("12345", result.get("discord_id").getAsString());
        assertEquals(SIGNED, result.getAsJsonArray("media_urls").get(0).getAsString());
        assertEquals(
                "<" + SIGNED + ">.",
                json(BridgeMedia.rewrite(payload("discord_chat", "<" + BARE + "?backend=b2>.", SIGNED)))
                        .get("message")
                        .getAsString());
    }

    @Test
    void replacesMultipleAttachmentsByIdNotArrayPosition() {
        String second =
                BARE.replace("1552730344417923202", "1552730344417923203").replace("IMG_0045", "other");
        String signedSecond =
                SIGNED.replace("1552730344417923202", "1552730344417923203").replace("IMG_0045", "other");
        String rewritten = BridgeMedia.rewrite(payload("discord_chat", BARE + " and " + second, signedSecond, SIGNED));
        assertEquals(
                SIGNED + " and " + signedSecond, json(rewritten).get("message").getAsString());
    }

    @Test
    void signaturesApplyOnlyToTheSameAttachmentFromATrustedHost() {
        for (String media : new String[] {
            SIGNED.replace("1552730344417923202", "999999"),
            SIGNED.replace("media.discordapp.net", "media.discordapp.net.evil.test"),
            SIGNED.replace("https://", "http://"),
            SIGNED.replace("?ex=6ac92152&is=6ac7cfd2&hm=abcdef&", "")
        }) {
            String original = payload("discord_chat", BARE, media);
            assertSame(original, BridgeMedia.rewrite(original), media);
        }
        String unrelated = payload("discord_chat", "https://i.imgur.com/a.gif", SIGNED);
        assertSame(unrelated, BridgeMedia.rewrite(unrelated));
    }

    @Test
    void delayedSignedAttachmentRefreshesOnlyThatAttachment() {
        BridgeMedia.rewrite(payload("discord_chat", BARE));
        assertEquals(URI.create(BARE), resolve(BARE));
        BridgeMedia.rewrite(payload("discord_media", null, SIGNED));
        assertEquals(URI.create(SIGNED), resolve(BARE));
        assertEquals(URI.create(SIGNED), resolve(BARE.replace("cdn.discordapp.com", "media.discordapp.net")));
        String other = BARE.replace("1552730344417923202", "999999");
        assertEquals(URI.create(other), resolve(other));
        ImagePreviews.clear();
        assertEquals(URI.create(SIGNED), resolve(BARE), "clearing previews keeps valid signed media");
    }

    @Test
    void delayedKlipyMediaBecomesItsOwnMessageAndNeverResolvesAPage() {
        BridgeMedia.rewrite(payload("discord_chat", PAGE));
        var result = json(BridgeMedia.rewrite(payload("discord_media", null, PROXY, PROXY.replace(".mp4", ".webp"))));
        assertEquals("discord_chat", result.get("type").getAsString());
        assertEquals("Alice", result.get("username").getAsString());
        assertEquals(MP4, result.get("message").getAsString());
        assertEquals(URI.create(PAGE), resolve(PAGE));
    }

    @Test
    void mediaInTheSameMessageReplacesItsSharePage() {
        assertEquals(
                MP4,
                json(BridgeMedia.rewrite(payload("discord_chat", PAGE, MP4)))
                        .get("message")
                        .getAsString());
    }

    @Test
    void klipyGifLinkResolvesToItsOwnMp4Rendition() {
        BridgeMedia.rewrite(payload("discord_chat", GIF));
        assertEquals(URI.create(GIF), resolve(GIF));
        var delayed = json(BridgeMedia.rewrite(payload("discord_media", null, PROXY)));
        assertEquals(URI.create(MP4), resolve(GIF));
        assertEquals(MP4, delayed.get("message").getAsString());
        assertEquals(URI.create(GIF + "?other=1"), resolve(GIF + "?other=1"));
    }

    @Test
    void resolvedMediaCacheIsBounded() {
        for (int i = 0; i < 129; i++)
            BridgeMedia.rewrite(payload("discord_media", null, SIGNED.replace("1552730344417923202", "" + i)));
        String first = BARE.replace("1552730344417923202", "0");
        assertEquals(URI.create(first), resolve(first));
        String last = BARE.replace("1552730344417923202", "128");
        assertNotEquals(URI.create(last), resolve(last));
    }

    @Test
    void rejectsUntrustedProxyTargetsAndInsecureUrls() {
        for (String url : new String[] {
            PROXY.replace("images-ext-1.discordapp.net", "evil.test"),
            PROXY.replace("static.klipy.com", "static.klipy.com.evil.test"),
            PROXY.replace("/https/", "/http/"),
            PROXY.replace("https://", "http://"),
            MP4.replace("static.klipy.com", "user@static.klipy.com"),
            MP4.replace("/ii/", "/private/"),
            MP4.replace("static.klipy.com", "static.klipy.com:444")
        }) {
            String original = payload("discord_media", null, url);
            assertSame(original, BridgeMedia.rewrite(original), url);
        }
    }

    @Test
    void disabledPreviewsLeaveBridgePayloadsAlone() {
        ImagePreviewSettings.setting().setValue(false);
        try {
            String original = payload("discord_chat", BARE, SIGNED);
            assertSame(original, BridgeMedia.rewrite(original));
        } finally {
            ImagePreviewSettings.setting().setValue(true);
        }
    }

    @Test
    void leavesOtherPayloadsAndMalformedDataUntouched() {
        for (String original : new String[] {
            "not json",
            "{}",
            payload("guild_chat", BARE, SIGNED),
            payload("guild_chat", PAGE, PROXY),
            "{\"type\":\"discord_chat\",\"message\":null,\"media_urls\":[null,{}]}",
            "{\"type\":\"discord_chat\",\"message\":\"hello\",\"media_urls\":{}}",
            "{\"type\":\"discord_media\",\"media_urls\":[null,{}]}",
            "{\"type\":\"discord_media\",\"username\":{},\"media_urls\":[null]}"
        }) assertSame(original, BridgeMedia.rewrite(original), original);
    }
}
