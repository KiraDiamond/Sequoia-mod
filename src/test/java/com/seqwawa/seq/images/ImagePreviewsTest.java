package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.seqwawa.seq.managers.DiscordRankChatDecorator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImagePreviewsTest {
    private static final String URL = "https://i.imgur.com/preview.gif";

    @BeforeEach
    void enablePreviews() {
        ImagePreviewSettings.setting().setValue(true);
    }

    @AfterEach
    void restoreSettings() {
        ImagePreviewSettings.setting().reset();
    }

    /** Styles of the runs whose text equals {@code text}. */
    private static List<Style> styles(Component component, String text) {
        var found = new ArrayList<Style>();
        component.visit(
                (style, run) -> {
                    if (run.equals(text)) found.add(style);
                    return Optional.empty();
                },
                Style.EMPTY);
        return found;
    }

    @Test
    void replacesMediaLinksAndKeepsSurroundingChat() {
        String url = "https://cdn.discordapp.com/a.gif?backend=b2";
        Component original = Component.literal("Alice: " + url + " hello").withStyle(ChatFormatting.GREEN);
        Component result = ImagePreviews.decorate(original);
        assertEquals("Alice: [View GIF] hello", result.getString());
        assertEquals("Alice: " + url + " hello", original.getString());
        assertEquals(
                ChatFormatting.GREEN.getColor(),
                styles(result, "Alice: ").getFirst().getColor().getValue());
        assertEquals(
                "Alice: [View GIF]",
                ImagePreviews.decorate(Component.literal("Alice: https://klipy.com/gifs/wynncraft-hello-chat"))
                        .getString());
    }

    @Test
    void multipleLinksKeepPunctuationAndTheirOriginalUrl() {
        Component result =
                ImagePreviews.decorate(Component.literal("<https://i.imgur.com/a.png> https://media.tenor.com/b.gif."));
        assertEquals("<[View Image]> [View GIF].", result.getString());
        var gif = styles(result, "[View GIF]").getFirst();
        assertEquals("seqimg:https://media.tenor.com/b.gif", gif.getInsertion());
        assertNotNull(gif.getClickEvent());
    }

    @Test
    void linksMayCrossStyledTextRuns() {
        Component source = Component.literal("Alice: ")
                .append(Component.literal("https://cdn.discordapp.com/").withStyle(ChatFormatting.RED))
                .append(Component.literal("image.gif?backend=b2").withStyle(ChatFormatting.BLUE));
        assertEquals("Alice: [View GIF]", ImagePreviews.decorate(source).getString());
    }

    @Test
    void otherClickableTextAndTheBridgeRailSurvive() {
        var worldClick = new ClickEvent.RunCommand("/switch WC1");
        Component linked = Component.empty()
                .append(Component.literal("WC1 ").withStyle(Style.EMPTY.withClickEvent(worldClick)))
                .append(Component.literal(URL));
        Component decorated = ImagePreviews.decorate(linked);
        assertEquals("WC1 [View GIF]", decorated.getString());
        assertEquals(worldClick, styles(decorated, "WC1 ").getFirst().getClickEvent());

        Component bridged = Component.literal("BridgeTester: " + URL);
        DiscordRankChatDecorator.displayUndecorated(bridged, () -> {});
        assertEquals(
                DiscordRankChatDecorator.bridgeContinuationPrefixFor(bridged),
                DiscordRankChatDecorator.bridgeContinuationPrefixFor(ImagePreviews.decorate(bridged)));
    }

    @Test
    void ordinaryOrDisabledChatIsLeftAlone() {
        Component ordinary = Component.literal("hello https://github.com/KiraDiamond/AS7-Mod");
        assertSame(ordinary, ImagePreviews.decorate(ordinary));
        ImagePreviewSettings.setting().setValue(false);
        Component link = Component.literal(URL);
        assertSame(link, ImagePreviews.decorate(link));
    }

    @Test
    void shiftClickOnAnExistingLabelStillInsertsTheUrlWhenDisabled() {
        ImagePreviewSettings.setting().setValue(false);
        Style label = Style.EMPTY.withInsertion("seqimg:" + URL);
        assertTrue(ImagePreviews.click(label, true, null));
        assertFalse(ImagePreviews.click(label, false, null));
        assertFalse(ImagePreviews.click(Style.EMPTY.withInsertion("BridgeTester"), true, null));
    }
}
