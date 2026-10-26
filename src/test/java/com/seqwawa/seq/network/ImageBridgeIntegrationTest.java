package com.seqwawa.seq.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.config.ConfigManager;
import com.seqwawa.seq.images.BridgeMedia;
import com.seqwawa.seq.images.ImagePreviewSettings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageBridgeIntegrationTest {
    private static final String BARE = "https://cdn.discordapp.com/attachments/1/2/test.gif";
    private static final String SIGNED = BARE + "?ex=abc&is=def&hm=123";
    private static final String VIDEO = "https://static.klipy.com/ii/test/preview.mp4";

    @TempDir
    Path directory;

    private ConfigManager oldConfig;
    private Object oldHandler;
    private Field handlerField;
    private Method handleMessage;
    private ConnectionManager connection;
    private final AtomicReference<ConnectionManager.DiscordChatMessage> received = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        oldConfig = SeqClient.configManager;
        var configConstructor = ConfigManager.class.getDeclaredConstructor(Path.class, Path.class, boolean.class);
        configConstructor.setAccessible(true);
        SeqClient.configManager =
                configConstructor.newInstance(directory.resolve("config.json"), directory.resolve("token"), false);
        handlerField = ConnectionManager.class.getDeclaredField("discordChatHandler");
        handlerField.setAccessible(true);
        oldHandler = handlerField.get(null);
        ConnectionManager.onDiscordChat(received::set);
        var constructor = ConnectionManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        connection = constructor.newInstance();
        handleMessage = ConnectionManager.class.getDeclaredMethod("handleMessage", String.class);
        handleMessage.setAccessible(true);
        ImagePreviewSettings.setting().setValue(true);
        ImagePreviewSettings.mediaSetting().reset();
        BridgeMedia.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        handlerField.set(null, oldHandler);
        SeqClient.configManager = oldConfig;
        ImagePreviewSettings.setting().reset();
        ImagePreviewSettings.mediaSetting().reset();
        BridgeMedia.clear();
    }

    private String payload(String type, String text, String media) {
        var json = new JsonObject();
        json.addProperty("type", type);
        json.addProperty("username", "BridgeTester");
        json.addProperty("discord_id", "123");
        if (text != null) json.addProperty("message", text);
        var urls = new JsonArray();
        urls.add(media);
        json.add("media_urls", urls);
        return json.toString();
    }

    @Test
    void signedAttachmentReachesTheChatHandlerWithItsSender() throws Exception {
        handleMessage.invoke(connection, payload("discord_chat", BARE, SIGNED));

        assertNotNull(received.get());
        assertEquals(SIGNED, received.get().message());
        assertEquals("BridgeTester", received.get().username());
        assertEquals("123", received.get().discordId());
        assertEquals(URI.create(SIGNED), BridgeMedia.resolve(URI.create(BARE)));
    }

    @Test
    void hiddenMediaDropsMediaOnlyMessagesBeforeDispatchAndPreservesTextAndIdentity() throws Exception {
        ImagePreviewSettings.mediaSetting().setValue(false);
        assertFalse(ImagePreviewSettings.enabled());
        handleMessage.invoke(connection, payload("discord_chat", BARE, SIGNED));
        assertNull(received.get());
        handleMessage.invoke(connection, payload("discord_media", null, VIDEO));
        assertNull(received.get());
        handleMessage.invoke(connection, payload("discord_chat", "hello " + BARE + " https://example.com/help", SIGNED));
        assertEquals("hello https://example.com/help", received.get().message());
        assertEquals("BridgeTester", received.get().username());
        assertEquals("123", received.get().discordId());
    }

    @Test
    void mediaVisibilityPersistsWithoutLosingTheSavedPreviewOptIn() throws Exception {
        Files.writeString(directory.resolve("config.json"), "{\"chat.image_previews\":true}");
        ConfigManager config = SeqClient.configManager;
        config.register(ImagePreviewSettings.mediaSetting());
        config.register(ImagePreviewSettings.setting());
        config.load();
        assertTrue(ImagePreviewSettings.enabled(), "legacy preview opt-in must still work");
        ImagePreviewSettings.mediaSetting().setValue(false);
        config.save();
        ImagePreviewSettings.mediaSetting().reset();
        ImagePreviewSettings.setting().reset();
        config.load();
        assertTrue(ImagePreviewSettings.hidden());
        assertFalse(ImagePreviewSettings.enabled());
        assertTrue(ImagePreviewSettings.setting().getValue());
        ImagePreviewSettings.mediaSetting().setValue(true);
        assertTrue(ImagePreviewSettings.enabled());
        ImagePreviewSettings.setting().setValue(false);
        String original = payload("discord_chat", BARE, SIGNED);
        handleMessage.invoke(connection, original);
        assertEquals(BARE, received.get().message(), "links mode must preserve the original URL");
    }

    @Test
    void mediaUpdatesRespectIgnoredBridgeUsers() throws Exception {
        SeqClient.configManager.addIgnoredBridgeUser("BridgeTester");
        handleMessage.invoke(connection, payload("discord_media", null, VIDEO));
        assertNull(received.get());

        SeqClient.configManager.removeIgnoredBridgeUser("BridgeTester");
        handleMessage.invoke(connection, payload("discord_media", null, VIDEO));
        assertNotNull(received.get());
        assertEquals(VIDEO, received.get().message());
        assertEquals("BridgeTester", received.get().username());
    }
}
