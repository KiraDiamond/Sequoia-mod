package com.seqwawa.seq.managers;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.integrations.WynntilsChatAccess;
import com.seqwawa.seq.network.WynncraftServerPolicy;
import com.seqwawa.seq.utils.ComponentTextEditor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Adds the remote participant's guild prefix to private messages at display time. */
public final class PrivateMessageGuildTagDecorator {
    private static final Pattern FORMATTING = Pattern.compile("§(?:#[0-9a-fA-F]{8}|[0-9a-fA-Fk-oK-OrR])");
    private static final Pattern TIMESTAMP = Pattern.compile("\\s*(?:\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\]\\s*)?");
    private static final Pattern HEADER = Pattern.compile("\\s*(.+?)\\s+\uE003\\s+(.+?):\\s");
    private static final Pattern GUILD_TAG = Pattern.compile("[A-Za-z0-9]{1,5}");
    private static final PrivateMessageGuildTagCache TAGS = new PrivateMessageGuildTagCache(
            WynntilsChatAccess::lookupGuildTag, System::currentTimeMillis);
    // Identity keys: distinct styled messages must never share cached parsing by visible text alone.
    private static final Cache<Component, ParsedMessage> MESSAGES =
            CacheBuilder.newBuilder().weakKeys().maximumSize(2048).build();
    private static final Set<Runnable> DIRTY_VIEWS = new LinkedHashSet<>();
    private static Object connection;
    private static String localUsername = "";

    private PrivateMessageGuildTagDecorator() {}

    public static boolean enabled() {
        if (!WynntilsChatAccess.isAvailable()) return false;
        Minecraft client = Minecraft.getInstance();
        return (SeqClient.getShowPrivateMessageGuildTagsSetting() == null
                || SeqClient.getShowPrivateMessageGuildTagsSetting().getValue())
                && client.player != null && WynncraftServerPolicy.isCurrentServerAllowed();
    }

    public static boolean isPrivateMessage(Component message) {
        return message != null && enabled() && parsed(message).username != null;
    }

    public static Component decorate(Component message, Runnable changed) {
        if (message == null || !enabled()) return message;
        ParsedMessage parsed = parsed(message);
        return parsed.username == null ? message : parsed.decorate(TAGS.read(parsed.username, changed));
    }

    public static void queueRefresh(Runnable refresh) {
        DIRTY_VIEWS.add(refresh);
    }

    /** Called once per client tick, including for inactive Wynntils chat tabs. */
    public static void tick() {
        if (!enabled()) {
            TAGS.clear();
            DIRTY_VIEWS.clear();
            return;
        }
        checkSession();
        TAGS.tick();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2);
        int views = DIRTY_VIEWS.size();
        while (views-- > 0 && !DIRTY_VIEWS.isEmpty()) {
            Runnable refresh = DIRTY_VIEWS.iterator().next();
            DIRTY_VIEWS.remove(refresh);
            refresh.run();
            if (System.nanoTime() >= deadline) break;
        }
    }

    private static void checkSession() {
        Minecraft client = Minecraft.getInstance();
        String username = client.getUser().getName();
        if (connection != client.getConnection() || !localUsername.equals(username)) {
            connection = client.getConnection();
            localUsername = username;
            TAGS.clear();
            MESSAGES.invalidateAll();
            DIRTY_VIEWS.clear();
        }
    }

    private static ParsedMessage parsed(Component message) {
        checkSession();
        ParsedMessage parsed = MESSAGES.getIfPresent(message);
        if (parsed == null) {
            parsed = parse(message, localUsername);
            MESSAGES.put(message, parsed);
        }
        return parsed;
    }

    static Component decorate(Component message, String localUsername, Function<String, String> guildLookup) {
        ParsedMessage parsed = parse(message, localUsername);
        return parsed.username == null ? message : parsed.decorate(guildLookup.apply(parsed.username));
    }

    static ParsedMessage parse(Component message, String localUsername) {
        ParsedMessage unchanged = new ParsedMessage(message, null, List.of(), 0);
        String text = message.getString();
        int marker = text.indexOf('\uE007');
        if (marker < 0 && text.indexOf('\uE001') < 0) return unchanged;
        List<ComponentTextEditor.Fragment> fragments = ComponentTextEditor.flatten(message);
        if (marker < 0) {
            marker = text.indexOf('\uE001');
            if (!hasPrivateColor(fragments, marker)) return unchanged;
        }
        String masked = maskDecorations(text);
        if (!TIMESTAMP.matcher(masked.substring(0, marker)).matches()) return unchanged;
        Matcher header = HEADER.matcher(masked);
        header.region(marker + 1, masked.length());
        if (!header.lookingAt()) return unchanged;

        String from = username(message, text, header.start(1), header.end(1), "private.from");
        String to = username(message, text, header.start(2), header.end(2), "private.to");
        boolean fromLocal = isLocal(from, localUsername);
        boolean toLocal = isLocal(to, localUsername);
        if (fromLocal == toLocal) return unchanged;
        int group = fromLocal ? 2 : 1;
        String other = fromLocal ? to : from;
        if (!(PacketNameResolver.validUsername(other) != null)) return unchanged;
        return new ParsedMessage(message, other, fragments, header.start(group));
    }

    static final class ParsedMessage {
        final Component original;
        final String username;
        final List<ComponentTextEditor.Fragment> fragments;
        final int insertAt;
        private String lastTag = "";
        private Component decorated;

        ParsedMessage(Component original, String username, List<ComponentTextEditor.Fragment> fragments, int insertAt) {
            this.original = original;
            this.username = username;
            this.fragments = fragments;
            this.insertAt = insertAt;
            this.decorated = original;
        }

        Component decorate(String tag) {
            if (tag == null || !GUILD_TAG.matcher(tag).matches()) tag = "";
            if (!tag.equals(lastTag)) {
                lastTag = tag;
                Component prefix = Component.literal("[" + tag + "] ").withStyle(styleAt(fragments, insertAt));
                decorated = tag.isEmpty() ? original
                        : ComponentTextEditor.toComponent(ComponentTextEditor.insertAt(fragments, insertAt, prefix));
            }
            return decorated;
        }
    }

    private static boolean isLocal(String username, String localUsername) {
        return username != null && (username.equalsIgnoreCase(localUsername) || username.equalsIgnoreCase("You"));
    }

    private static String username(Component message, String text, int start, int end, String role) {
        if (maskDecorations(text.substring(start, end)).trim().startsWith("[")) return null;
        return PacketNameResolver.from(message).resolveRaw(text, start, end, PacketNameResolver.Policy.CHAT, role).username();
    }

    private static Style styleAt(List<ComponentTextEditor.Fragment> fragments, int index) {
        int offset = 0;
        for (var fragment : fragments) {
            offset += fragment.text().length();
            if (index < offset) return fragment.style();
        }
        return Style.EMPTY;
    }

    private static boolean hasPrivateColor(List<ComponentTextEditor.Fragment> fragments, int marker) {
        var color = styleAt(fragments, marker).getColor();
        return color != null && color.getValue() == 0xDDCC99;
    }

    /** Keep UTF-16 offsets while ignoring rank glyphs, padding and embedded colour codes. */
    private static String maskDecorations(String text) {
        char[] masked = text.toCharArray();
        Matcher formatting = FORMATTING.matcher(text);
        while (formatting.find()) {
            java.util.Arrays.fill(masked, formatting.start(), formatting.end(), ' ');
        }
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int width = Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (codePoint != '\uE003' && (type == Character.PRIVATE_USE
                    || type == Character.UNASSIGNED || type == Character.FORMAT)) {
                java.util.Arrays.fill(masked, i, i + width, ' ');
            }
            i += width;
        }
        return new String(masked);
    }
}
