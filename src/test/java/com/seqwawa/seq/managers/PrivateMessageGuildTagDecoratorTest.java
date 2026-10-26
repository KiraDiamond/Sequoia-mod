package com.seqwawa.seq.managers;

import static org.junit.jupiter.api.Assertions.*;

import com.seqwawa.seq.utils.ComponentTextEditor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

class PrivateMessageGuildTagDecoratorTest {
    private static final String MARKER = "\uDAFF\uDFFC\uE007\uDAFF\uDFFF\uE002\uDAFF\uDFFE ";
    private static final String ARROW = " \uE003 ";

    @Test
    void inheritedRootIdentityCannotReplaceTheRemoteAccount() {
        Component message = Component.empty().withStyle(Style.EMPTY.withInsertion("RaidKeeper"))
                .append(MARKER).append(Component.literal("nunot").withStyle(Style.EMPTY.withInsertion("nunot")))
                .append(ARROW).append("GaztheCat: hello");
        var parsed = PrivateMessageGuildTagDecorator.parse(message, "nunot");
        assertEquals("GaztheCat", parsed.username);
    }

    @Test
    void reusesParsedDecorationUntilTheTagChangesAndCanRemoveIt() {
        Component message = Component.literal(MARKER + "Baptiste" + ARROW + "You: hello");
        var parsed = PrivateMessageGuildTagDecorator.parse(message, "LocalPlayer");
        assertSame(message, parsed.decorate(""));
        Component tagged = parsed.decorate("SEQ");
        assertSame(tagged, parsed.decorate("SEQ"));
        assertEquals(message.getString().replace("Baptiste", "[NEW] Baptiste"), parsed.decorate("NEW").getString());
        assertSame(message, parsed.decorate(""));
    }

    @Test
    void tagsOnlyTheOtherPlayerInBothDirections() {
        List<String> lookedUp = new ArrayList<>();
        for (String header : List.of("Baptiste" + ARROW + "You", "You" + ARROW + "Baptiste",
                "Baptiste" + ARROW + "LocalPlayer", "LocalPlayer" + ARROW + "Baptiste")) {
            Component message = Component.literal(MARKER + header + ": hello");
            Component result = PrivateMessageGuildTagDecorator.decorate(message, "LocalPlayer", name -> {
                lookedUp.add(name);
                return "SEQ";
            });
            assertEquals(MARKER + header.replace("Baptiste", "[SEQ] Baptiste") + ": hello", result.getString());
        }
        assertEquals(List.of("Baptiste", "Baptiste", "Baptiste", "Baptiste"), lookedUp);
    }

    @Test
    void preservesTimestampNicknameAndInteractiveStyles() {
        Style playerStyle = Style.EMPTY.withColor(0xDDCC99).withItalic(true).withInsertion("Baptiste")
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Real username: Baptiste")))
                .withClickEvent(new ClickEvent.SuggestCommand("/msg Baptiste "));
        Component message = Component.literal("[12:34:56] " + MARKER)
                .append(Component.literal("Nick Name").withStyle(playerStyle))
                .append(ARROW + "You: hi Baptiste");
        Component result = PrivateMessageGuildTagDecorator.decorate(message, "LocalPlayer", name -> {
            assertEquals("Baptiste", name);
            return "SEQ";
        });
        assertEquals("[12:34:56] " + MARKER + "[SEQ] Nick Name" + ARROW + "You: hi Baptiste", result.getString());
        assertEquals(playerStyle, ComponentTextEditor.flatten(result).stream()
                .filter(fragment -> fragment.text().equals("Nick Name")).findFirst().orElseThrow().style());
        assertEquals(playerStyle, ComponentTextEditor.flatten(result).stream()
                .filter(fragment -> fragment.text().equals("[SEQ] ")).findFirst().orElseThrow().style());
    }

    @Test
    void acceptsEmbeddedFormattingAndRevealedNames() {
        Component message = Component.literal("§#ddcc99ff" + MARKER + "§oNick(Baptiste)§r" + ARROW + "You: §fhello");
        Component result = PrivateMessageGuildTagDecorator.decorate(message, "LocalPlayer", name -> {
            assertEquals("Baptiste", name);
            return "SEQ";
        });
        assertEquals(message.getString().replace("Nick(Baptiste)", "[SEQ] Nick(Baptiste)"), result.getString());
    }

    @Test
    void leavesUnknownGuildsUnchangedAndDoesNotDuplicateTags() {
        Component message = Component.literal(MARKER + "Baptiste" + ARROW + "You: hello");
        for (String tag : new String[] {null, "", "bad\ntext"}) {
            assertSame(message, PrivateMessageGuildTagDecorator.decorate(message, "LocalPlayer", name -> tag));
        }
        Component tagged = PrivateMessageGuildTagDecorator.decorate(message, "LocalPlayer", name -> "SEQ");
        assertSame(tagged, PrivateMessageGuildTagDecorator.decorate(tagged, "LocalPlayer", name -> {
            fail("Already tagged lines must not trigger another lookup");
            return "SEQ";
        }));
    }

    @Test
    void ignoresOtherChannelsMessageBodiesAndSelfMessages() {
        for (String text : List.of("Baptiste" + ARROW + "You: hello",
                "GuildPlayer: " + MARKER + "Baptiste" + ARROW + "You: hello",
                MARKER + "You" + ARROW + "LocalPlayer: hello",
                MARKER + "Baptiste" + ARROW + "SomeoneElse: hello")) {
            Component message = Component.literal(text);
            assertSame(message, PrivateMessageGuildTagDecorator.decorate(message, "LocalPlayer", name -> {
                fail("Not a private message involving the local player");
                return "SEQ";
            }));
        }
    }

    @Test
    void recognizesTheSharedWrappedMarkerOnlyInPrivateChatColor() {
        String line = "\uE001 Baptiste" + ARROW + "You: hello";
        Component privateLine = Component.literal(line).withStyle(Style.EMPTY.withColor(0xDDCC99));
        assertEquals(line.replace("Baptiste", "[SEQ] Baptiste"),
                PrivateMessageGuildTagDecorator.decorate(privateLine, "LocalPlayer", name -> "SEQ").getString());
        Component guildLine = Component.literal(line).withStyle(Style.EMPTY.withColor(0x55FFFF));
        assertSame(guildLine, PrivateMessageGuildTagDecorator.decorate(guildLine, "LocalPlayer", name -> "SEQ"));
    }
}
