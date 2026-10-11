package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

class BridgeMediaTest {
    private static final String BARE =
            "https://cdn.discordapp.com/attachments/361194799744679947/1552730344417923202/IMG_0045.gif";
    private static final String SIGNED =
            "https://media.discordapp.net/attachments/361194799744679947/1552730344417923202/IMG_0045.gif?ex=6ac92152&is=6ac7cfd2&hm=abcdef&";

    private String payload(String message, String media) {
        var json = new com.google.gson.JsonObject();
        json.addProperty("type", "discord_chat");
        json.addProperty("username", "Topdiamond2006");
        json.addProperty("message", message);
        json.addProperty("discord_id", "12345");
        var urls = new com.google.gson.JsonArray();
        urls.add(media);
        json.add("media_urls", urls);
        return json.toString();
    }

    private String message(String payload) {
        return JsonParser.parseString(payload).getAsJsonObject().get("message").getAsString();
    }

    @Test
    void usesSignedMediaFromActualBridgePayload() {
        String original = payload("look " + BARE, SIGNED);
        var result = JsonParser.parseString(BridgeMedia.rewrite(original)).getAsJsonObject();
        assertEquals("look " + SIGNED, result.get("message").getAsString());
        assertEquals("Topdiamond2006", result.get("username").getAsString());
        assertEquals("12345", result.get("discord_id").getAsString());
        assertEquals(SIGNED, result.getAsJsonArray("media_urls").get(0).getAsString());
        assertEquals("look " + BARE, message(original));
    }

    @Test
    void preservesPunctuationAndExistingQuery() {
        assertEquals(
                "<" + SIGNED + ">.",
                message(BridgeMedia.rewrite(payload("<" + BARE + "?backend=b2>.", SIGNED))));
    }

    @Test
    void refusesUnrelatedAttachmentOrUntrustedHost() {
        for (String media :
                new String[] {
                    SIGNED.replace("1552730344417923202", "999999"),
                    SIGNED.replace("media.discordapp.net", "media.discordapp.net.evil.test"),
                    SIGNED.replace("https://", "http://"),
                    SIGNED.replace("?ex=6ac92152&is=6ac7cfd2&hm=abcdef&", "")
                }) {
            String original = payload(BARE, media);
            assertSame(original, BridgeMedia.rewrite(original));
        }
    }

    @Test
    void replacesMultipleAttachmentsByIdNotArrayPosition() {
        String second =
                BARE.replace("1552730344417923202", "1552730344417923203")
                        .replace("IMG_0045", "other");
        String signedSecond =
                SIGNED.replace("1552730344417923202", "1552730344417923203")
                        .replace("IMG_0045", "other");
        var json =
                JsonParser.parseString(payload(BARE + " and " + second, signedSecond))
                        .getAsJsonObject();
        json.getAsJsonArray("media_urls").add(SIGNED);
        assertEquals(
                SIGNED + " and " + signedSecond, message(BridgeMedia.rewrite(json.toString())));
    }

    @Test
    void leavesOtherPayloadsAndMalformedDataUntouched() {
        for (String original :
                new String[] {
                    "not json",
                    "{}",
                    payload(BARE, SIGNED).replace("discord_chat", "guild_chat"),
                    "{\"type\":\"discord_chat\",\"message\":null,\"media_urls\":[null,{}]}",
                    "{\"type\":\"discord_chat\",\"message\":\"hello\",\"media_urls\":{}}"
                }) assertSame(original, BridgeMedia.rewrite(original));
    }

    @Test
    void unrelatedImageTextNeverUsesAnotherAttachmentsSignature() {
        String original = payload("https://i.imgur.com/a.gif", SIGNED);
        assertSame(original, BridgeMedia.rewrite(original));
    }
}
