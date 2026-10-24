package com.seqwawa.seq.integrations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

class WynntilsChatAccessTest {
    @Test
    void missingModSkipsResolutionAndLeavesBothOperationsHarmless() {
        var access = new WynntilsChatAccess(() -> false,
                () -> { throw new AssertionError("Must not load the optional bridge"); },
                error -> fail("Missing mod must not log an error", error));
        assertEquals("", access.lookup("Baptiste").join());
        assertDoesNotThrow(() -> access.post(null, null));
        Component name = Component.literal("cat");
        assertSame(name, access.reveal(name, "catsfield"));
    }

    @Test
    void installedBridgePreservesAsyncLookupsAndOriginalMessageAndLine() {
        CompletableFuture<String> lookup = new CompletableFuture<>();
        List<String> usernames = new ArrayList<>();
        List<GuiMessage> messages = new ArrayList<>();
        List<GuiMessage.Line> lines = new ArrayList<>();
        AtomicInteger resolutions = new AtomicInteger();
        var access = new WynntilsChatAccess(() -> true, () -> {
            resolutions.incrementAndGet();
            return new WynntilsChatAccess.Bridge() {
                public CompletableFuture<String> lookupGuildTag(String username) {
                    usernames.add(username);
                    return lookup;
                }

                public void postAddedLine(GuiMessage message, GuiMessage.Line line) {
                    messages.add(message);
                    lines.add(line);
                }
            };
        }, error -> fail("Available integration must not log an error", error));
        assertSame(lookup, access.lookup("Baptiste"));
        assertFalse(lookup.isDone());
        lookup.complete("SEQ");
        assertEquals("SEQ", lookup.join());
        GuiMessage message = new GuiMessage(42, Component.literal("PM"), null, null);
        GuiMessage.Line line = new GuiMessage.Line(42,
                FormattedCharSequence.forward("PM", Style.EMPTY), null, true);
        access.post(message, line);
        assertEquals(List.of("Baptiste"), usernames);
        assertEquals(List.of(message), messages);
        assertEquals(List.of(line), lines);
        assertEquals(1, resolutions.get());
    }

    @Test
    void optionalRevealPreservesOriginalOnFailureAndDoesNotPostGeneralChatEvents() {
        List<Throwable> failures = new ArrayList<>();
        var access = new WynntilsChatAccess(() -> true, () -> new WynntilsChatAccess.Bridge() {
            public CompletableFuture<String> lookupGuildTag(String username) { throw new AssertionError("No lookup"); }
            public void postAddedLine(GuiMessage message, GuiMessage.Line line) { throw new AssertionError("No general event"); }
            public Component revealNickname(Component name, String username) {
                if (username.equals("broken")) throw new NoClassDefFoundError("RevealNicknamesFeature");
                assertEquals("cat", name.getString());
                return Component.empty().append(username + "/").append(name);
            }
        }, failures::add);
        Component name = Component.literal("cat");
        assertEquals("catsfield/cat", access.reveal(name, "catsfield").getString());
        assertSame(name, access.reveal(name, "broken"));
        assertSame(name, access.reveal(name, "broken"));
        assertEquals(1, failures.size());
    }

    @Test
    void unavailableBridgeFallsBackOnceWithoutRetryingClassResolution() {
        for (boolean linkageFailure : List.of(false, true)) {
            List<Throwable> failures = new ArrayList<>();
            AtomicInteger resolutions = new AtomicInteger();
            var access = new WynntilsChatAccess(() -> true, () -> {
                resolutions.incrementAndGet();
                if (linkageFailure) throw new NoClassDefFoundError("Wynntils");
                throw new ClassNotFoundException("WynntilsChatBridge");
            }, failures::add);
            assertEquals("", access.lookup("Baptiste").join());
            assertDoesNotThrow(() -> access.post(null, null));
            assertEquals("", access.lookup("OtherPlayer").join());
            assertEquals(1, resolutions.get());
            assertEquals(1, failures.size());
        }
    }

    @Test
    void lateLinkageFailuresDoNotEscapeIntoChatAndRemainLookupFailures() {
        List<Throwable> failures = new ArrayList<>();
        var access = new WynntilsChatAccess(() -> true, () -> new WynntilsChatAccess.Bridge() {
            public CompletableFuture<String> lookupGuildTag(String username) {
                throw new NoClassDefFoundError("Models");
            }

            public void postAddedLine(GuiMessage message, GuiMessage.Line line) {
                throw new NoClassDefFoundError("AddGuiMessageLineEvent");
            }
        }, failures::add);
        CompletableFuture<String> lookup = assertDoesNotThrow(() -> access.lookup("Baptiste"));
        assertInstanceOf(NoClassDefFoundError.class, assertThrows(CompletionException.class, lookup::join).getCause());
        assertDoesNotThrow(() -> access.post(null, null));
        assertEquals(1, failures.size());
    }
}
