package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.*;

import java.net.URI;

class DelayedMediaTest {
    private static final String PAGE = "https://klipy.com/gifs/wynncraft-hello-chat";
    private static final String MP4 =
            "https://static.klipy.com/ii/4e7bea9f7a3371424e6c16ebc93252fe/62/15/xiDo32fjTTjtLylez.mp4";
    private static final String GIF = MP4.replace(".mp4", ".gif");
    private static final String PROXY =
            "https://images-ext-1.discordapp.net/external/hash/https/" + MP4.substring(8);
    private static final String BARE =
            "https://media.discordapp.net/attachments/316978886087016449/1086927141700128839/ezgif-5-43ee149553.gif";
    private static final String SIGNED =
            BARE
                    + "?ex=6ac8a3c8&is=6ac75248&hm=640ab2fa14a241137c9c41ec99c8d2eb8fefe2316b34c35ba06c0dc8ca79cf76&";

    private String payload(String type, String user, String message, String... urls) {
        var json = new JsonObject();
        json.addProperty("type", type);
        json.addProperty("username", user);
        if (message != null) json.addProperty("message", message);
        var media = new JsonArray();
        for (String url : urls) media.add(url);
        json.add("media_urls", media);
        return json.toString();
    }

    @BeforeEach
    void reset() {
        BridgeMedia.clear();
    }

    @Test
    void exactLatestAttachmentRecoversWhenSignedMediaArrivesSeparately() {
        BridgeMedia.rewrite(payload("discord_chat", "Topdiamond2006", BARE));
        assertEquals(URI.create(BARE), BridgeMedia.resolve(URI.create(BARE)));
        BridgeMedia.rewrite(payload("discord_media", "Topdiamond2006", null, SIGNED));
        assertEquals(URI.create(SIGNED), BridgeMedia.resolve(URI.create(BARE)));
        assertEquals(
                URI.create(SIGNED),
                BridgeMedia.resolve(
                        URI.create(BARE.replace("media.discordapp.net", "cdn.discordapp.com"))));
    }

    @Test
    void signaturesNeverApplyToADifferentAttachment() {
        BridgeMedia.rewrite(payload("discord_media", "Alice", null, SIGNED));
        URI other = URI.create(BARE.replace("1086927141700128839", "999999"));
        assertEquals(other, BridgeMedia.resolve(other));
    }

    private static JsonObject json(String payload) {
        return JsonParser.parseString(payload).getAsJsonObject();
    }

    private String withDiscordId(String payload, String discordId) {
        var json = json(payload);
        json.addProperty("discord_id", discordId);
        return json.toString();
    }

    @Test
    void delayedKlipyMediaForASharePageIsShownAsItsOwnMessage() {
        assertEquals(URI.create(PAGE), MediaLinks.imageUri(PAGE));
        BridgeMedia.rewrite(payload("discord_chat", "Topdiamond2006", PAGE));
        assertThrows(
                MediaDownload.UnresolvedKlipyPage.class,
                () -> MediaDownload.fetch(URI.create(PAGE)));
        var result =
                json(
                        BridgeMedia.rewrite(
                                payload(
                                        "discord_media",
                                        "Topdiamond2006",
                                        null,
                                        PROXY,
                                        PROXY.replace(".mp4", ".webp"))));
        assertEquals("discord_chat", result.get("type").getAsString());
        assertEquals("Topdiamond2006", result.get("username").getAsString());
        assertEquals(MP4, result.get("message").getAsString());
        assertEquals(URI.create(PAGE), BridgeMedia.resolve(URI.create(PAGE)));
    }

    @Test
    void identicalDisplayNamesWithDifferentDiscordIdsNeverCrossAssociate() {
        BridgeMedia.rewrite(withDiscordId(payload("discord_chat", "Alice", PAGE), "1"));
        var result =
                json(
                        BridgeMedia.rewrite(
                                withDiscordId(payload("discord_media", "Alice", null, MP4), "2")));
        assertEquals(URI.create(PAGE), BridgeMedia.resolve(URI.create(PAGE)));
        assertEquals("discord_chat", result.get("type").getAsString());
        assertEquals("2", result.get("discord_id").getAsString());
        assertEquals(MP4, result.get("message").getAsString());
    }

    @Test
    void anotherUsersEarlierGifLinkDoesNotHideDelayedMedia() {
        BridgeMedia.rewrite(payload("discord_chat", "Alice", GIF));
        BridgeMedia.rewrite(payload("discord_chat", "Bob", PAGE));
        var result = json(BridgeMedia.rewrite(payload("discord_media", "Bob", null, PROXY)));
        assertEquals("discord_chat", result.get("type").getAsString());
        assertEquals("Bob", result.get("username").getAsString());
        assertEquals(MP4, result.get("message").getAsString());
    }

    @Test
    void oneSendersOutstandingMessagesNeverCrossAssociate() {
        String other = PAGE.replace("wynncraft-hello-chat", "another-gif");
        String otherMedia = MP4.replace("xiDo32fjTTjtLylez", "anotherAsset");
        BridgeMedia.rewrite(payload("discord_chat", "Alice", PAGE));
        BridgeMedia.rewrite(payload("discord_chat", "Alice", other));
        for (String media : new String[] {otherMedia, MP4}) {
            var result = json(BridgeMedia.rewrite(payload("discord_media", "Alice", null, media)));
            assertEquals("discord_chat", result.get("type").getAsString());
            assertEquals(media, result.get("message").getAsString());
        }
        assertEquals(URI.create(PAGE), BridgeMedia.resolve(URI.create(PAGE)));
        assertEquals(URI.create(other), BridgeMedia.resolve(URI.create(other)));
    }

    @Test
    void klipyMediaWithoutMatchingShareBecomesItsOwnPreview() {
        var result =
                JsonParser.parseString(
                                BridgeMedia.rewrite(payload("discord_media", "Alice", null, PROXY)))
                        .getAsJsonObject();
        assertEquals("discord_chat", result.get("type").getAsString());
        assertEquals("Alice", result.get("username").getAsString());
        assertEquals(MP4, result.get("message").getAsString());
    }

    @Test
    void differentSendersAreNeverPaired() {
        BridgeMedia.rewrite(payload("discord_chat", "Alice", PAGE));
        BridgeMedia.rewrite(payload("discord_media", "Bob", null, PROXY));
        assertEquals(URI.create(PAGE), BridgeMedia.resolve(URI.create(PAGE)));
    }

    @Test
    void ambiguousSharesProduceSeparatePreviewInsteadOfWrongMapping() {
        BridgeMedia.rewrite(payload("discord_chat", "Alice", PAGE));
        String other = PAGE.replace("wynncraft-hello-chat", "another-gif");
        BridgeMedia.rewrite(payload("discord_chat", "Alice", other));
        var result =
                JsonParser.parseString(
                                BridgeMedia.rewrite(payload("discord_media", "Alice", null, PROXY)))
                        .getAsJsonObject();
        assertEquals(MP4, result.get("message").getAsString());
        assertEquals(URI.create(PAGE), BridgeMedia.resolve(URI.create(PAGE)));
        assertEquals(URI.create(other), BridgeMedia.resolve(URI.create(other)));
    }

    @Test
    void clearForgetsResolvedMedia() {
        BridgeMedia.rewrite(payload("discord_media", "Alice", null, SIGNED));
        BridgeMedia.clear();
        assertEquals(URI.create(BARE), BridgeMedia.resolve(URI.create(BARE)));
    }

    @Test
    void clearingPreviewsRetainsValidSignedBridgeMedia() {
        BridgeMedia.rewrite(payload("discord_media", "Alice", null, SIGNED));
        ImagePreviews.clear();
        assertEquals(URI.create(SIGNED), BridgeMedia.resolve(URI.create(BARE)));
    }

    @Test
    void resolvedMediaCacheIsBounded() {
        for (int i = 0; i < 129; i++)
            BridgeMedia.rewrite(
                    payload(
                            "discord_media",
                            "Alice",
                            null,
                            SIGNED.replace("1086927141700128839", Integer.toString(i))));
        URI first = URI.create(BARE.replace("1086927141700128839", "0"));
        assertEquals(first, BridgeMedia.resolve(first));
        URI last = URI.create(BARE.replace("1086927141700128839", "128"));
        assertNotEquals(last, BridgeMedia.resolve(last));
    }

    @Test
    void rejectsUntrustedProxyTargetsAndInsecureUrls() {
        for (String url :
                new String[] {
                    PROXY.replace("images-ext-1.discordapp.net", "evil.test"),
                    PROXY.replace("static.klipy.com", "static.klipy.com.evil.test"),
                    PROXY.replace("/https/", "/http/"),
                    PROXY.replace("https://", "http://"),
                    MP4.replace("static.klipy.com", "user@static.klipy.com"),
                    MP4.replace("/ii/", "/private/"),
                    MP4.replace("static.klipy.com", "static.klipy.com:444")
                }) {
            String original = payload("discord_media", "Alice", null, url);
            assertSame(original, BridgeMedia.rewrite(original), url);
        }
    }

    @Test
    void inlineMediaAndAlreadyDirectKlipyGifWork() {
        assertEquals(URI.create(GIF), MediaLinks.imageUri(GIF));
        assertEquals(URI.create(MP4), MediaLinks.imageUri(MP4));
        var result =
                JsonParser.parseString(
                                BridgeMedia.rewrite(payload("discord_chat", "Alice", PAGE, MP4)))
                        .getAsJsonObject();
        assertEquals(MP4, result.get("message").getAsString());
    }

    @Test
    void directGifPreviewRecoversWhenActualMediaArrivesSeparately() {
        BridgeMedia.rewrite(payload("discord_chat", "Alice", GIF));
        assertEquals(URI.create(GIF), BridgeMedia.resolve(URI.create(GIF)));
        // The asset itself identifies the GIF link. The delayed media still gets its own line,
        // because nothing tells which message, if any, it belongs to.
        var delayed = json(BridgeMedia.rewrite(payload("discord_media", "Bob", null, PROXY)));
        assertEquals(URI.create(MP4), BridgeMedia.resolve(URI.create(GIF)));
        assertEquals("discord_chat", delayed.get("type").getAsString());
        assertEquals(MP4, delayed.get("message").getAsString());
    }

    @Test
    void directKlipyGifUsesExactCorrespondingMediaWithoutInventingAUrl() {
        String original = payload("discord_chat", "Alice", GIF, PROXY);
        BridgeMedia.rewrite(original);
        assertEquals(URI.create(MP4), BridgeMedia.resolve(URI.create(GIF)));
        assertEquals(
                URI.create(GIF + "?other=1"), BridgeMedia.resolve(URI.create(GIF + "?other=1")));
    }

    @Test
    void stillLeavesUnknownOrMalformedEventsAlone() {
        for (String original :
                new String[] {
                    payload("guild_chat", "Alice", PAGE, PROXY),
                    "{\"type\":\"discord_media\",\"media_urls\":[null,{}]}",
                    "{\"type\":\"discord_media\",\"username\":{},\"media_urls\":[null]}"
                }) assertSame(original, BridgeMedia.rewrite(original));
    }

    @Test
    void klipyPageBecomesHoverAndClickPreview() {
        assertEquals(
                "Alice: [View GIF]",
                ImagePreviews.decorate(
                                net.minecraft.network.chat.Component.literal("Alice: " + PAGE))
                        .getString());
    }
}
