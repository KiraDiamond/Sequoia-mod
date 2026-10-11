package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import com.seqwawa.seq.managers.DiscordRankChatDecorator;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

class ImagePreviewIntegrationTest {
    private static final String URL = "https://i.imgur.com/preview.gif";

    @AfterEach
    void restoreSettings() {
        ImagePreviewSettings.setting().setValue(true);
        BridgeMedia.clear();
    }

    @Test
    void replacingAnImageLinkKeepsTheBridgeRail() {
        Component original = Component.literal("BridgeTester: " + URL);
        DiscordRankChatDecorator.displayUndecorated(original, () -> {});
        Component decorated = ImagePreviews.decorate(original);

        assertEquals("BridgeTester: [View GIF]", decorated.getString());
        assertEquals(
                DiscordRankChatDecorator.bridgeContinuationPrefixFor(original),
                DiscordRankChatDecorator.bridgeContinuationPrefixFor(decorated));
    }

    @Test
    void imageReplacementKeepsOtherClickableChatText() {
        var worldClick = new ClickEvent.RunCommand("/switch WC1");
        Component original =
                Component.empty()
                        .append(
                                Component.literal("WC1 ")
                                        .withStyle(Style.EMPTY.withClickEvent(worldClick)))
                        .append(Component.literal(URL));
        Component decorated = ImagePreviews.decorate(original);
        var found = new AtomicBoolean();
        decorated.visit(
                (style, text) -> {
                    if (text.contains("WC1")) {
                        assertEquals(worldClick, style.getClickEvent());
                        found.set(true);
                    }
                    return java.util.Optional.empty();
                },
                Style.EMPTY);
        assertTrue(found.get());
        assertEquals("WC1 [View GIF]", decorated.getString());
    }

    @Test
    void disabledPreviewsDoNotRewriteChat() {
        ImagePreviewSettings.setting().setValue(false);
        Component original = Component.literal(URL);
        assertSame(original, ImagePreviews.decorate(original));
    }

    @Test
    void disablingPreviewsStillInterceptsShiftClickOnAnExistingLabel() {
        ImagePreviewSettings.setting().setValue(false);
        Style label = Style.EMPTY.withInsertion("seqimg:" + URL);
        assertTrue(ImagePreviews.click(label, true, null));
        assertFalse(ImagePreviews.click(label, false, null));
        assertFalse(ImagePreviews.click(Style.EMPTY.withInsertion("BridgeTester"), true, null));
    }
}
