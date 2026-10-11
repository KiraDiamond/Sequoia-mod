package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import org.junit.jupiter.api.Test;

import java.util.Optional;

class ChatTest {
    @Test
    void replacesGifLinkAndKeepsSurroundingChat() {
        String url = "https://cdn.discordapp.com/a.gif?backend=b2";
        Component original =
                Component.literal("Alice: " + url + " hello").withStyle(ChatFormatting.GREEN);
        Component result = ImagePreviews.decorate(original);
        assertEquals("Alice: [View GIF] hello", result.getString());
        assertEquals("Alice: " + url + " hello", original.getString());
        var sender = new java.util.ArrayList<Style>();
        result.visit(
                (style, text) -> {
                    if (text.equals("Alice: ")) sender.add(style);
                    return Optional.empty();
                },
                Style.EMPTY);
        assertEquals(ChatFormatting.GREEN.getColor(), sender.getFirst().getColor().getValue());
    }

    @Test
    void imageUrlCanCrossStyledTextRuns() {
        Component source =
                Component.literal("Alice: ")
                        .append(
                                Component.literal("https://cdn.discordapp.com/")
                                        .withStyle(ChatFormatting.RED))
                        .append(
                                Component.literal("image.gif?backend=b2")
                                        .withStyle(ChatFormatting.BLUE));
        assertEquals("Alice: [View GIF]", ImagePreviews.decorate(source).getString());
    }

    @Test
    void leavesOrdinaryMessagesIdentical() {
        Component original = Component.literal("hello https://github.com/KiraDiamond/AS7-Mod");
        assertSame(original, ImagePreviews.decorate(original));
    }

    @Test
    void multipleEmbedsKeepPunctuationAndOriginalLink() {
        Component result =
                ImagePreviews.decorate(
                        Component.literal(
                                "<https://i.imgur.com/a.png> https://media.tenor.com/b.gif."));
        assertEquals("<[View Image]> [View GIF].", result.getString());
        var styles = new java.util.ArrayList<Style>();
        result.visit(
                (style, text) -> {
                    if (text.equals("[View GIF]")) styles.add(style);
                    return Optional.empty();
                },
                Style.EMPTY);
        assertEquals("seqimg:https://media.tenor.com/b.gif", styles.getFirst().getInsertion());
        assertNotNull(styles.getFirst().getClickEvent());
    }
}
