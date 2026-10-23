package com.seqwawa.seq.managers;

import java.util.ArrayList;
import com.seqwawa.seq.utils.PacketTextNormalizer;
import java.util.UUID;
import java.util.List;
import java.util.regex.Matcher;
import net.minecraft.network.chat.HoverEvent;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Resolves Minecraft usernames from packet-level chat components while
 * preserving hover/insertion metadata after Wynncraft formatting cleanup.
 */
public final class PacketNameResolver {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final long session;
    private final String text;
    private final List<MetaChar> characters;

    private PacketNameResolver(String text, List<MetaChar> characters, long session) {
        this.session = session;
        this.text = text;
        this.characters = characters;
    }

    public enum State { RESOLVED, UNRESOLVED, AMBIGUOUS }
    public enum Source { HOVER, INSERTION, INLINE, ACCOUNT, CACHE, PROFILE, NONE }
    public enum Policy { CHAT, ALIAS, TRUNCATED, METADATA }

    /** A failed or ambiguous resolution never carries an actionable account. */
    public record Resolution(State state, String display, String username, UUID uuid, Source source,
                             String role, int start, int end, List<String> evidence, long session) {
        public Resolution {
            evidence = List.copyOf(evidence);
            if (state != State.RESOLVED) { username = null; uuid = null; }
        }
    }

    /** Only callers with an actual player/profile context may supply a UUID. */
    static Resolution profile(String display, String account, UUID uuid, String role) {
        return new Resolution(validUsername(account) == null ? State.UNRESOLVED : State.RESOLVED,
                display, account, new UUID(0, 0).equals(uuid) ? null : uuid, Source.PROFILE, role, 0, display.length(),
                account == null ? List.of() : List.of(account), NicknameResolverCache.generation());
    }

    public static PacketNameResolver from(Component message) {
        long session = NicknameResolverCache.generation();
        List<MetaChar> characters = new ArrayList<>();
        if (message != null) appendTree(characters, message, Style.EMPTY, null, null);
        return new PacketNameResolver(toText(characters), List.copyOf(characters), session);
    }

    public String text() { return text; }

    public Resolution resolve(int start, int end, Policy policy, String role) {
        return resolve(start, end, policy, role, true);
    }

    Resolution resolve(int start, int end, Policy policy, String role, boolean useCache) {
        if (session != NicknameResolverCache.generation() || start < 0 || end > text.length() || start >= end)
            return decision(State.UNRESOLVED, "", null, Source.NONE, role, start, end, List.of());
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        String display = text.substring(start, end);
        Set<String> candidates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> insertions = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Source source = Source.NONE;
        for (int i = start; i < end; i++) {
            MetaChar ch = characters.get(i);
            Owner hover = ch.hoverOwner();
            if (hover != null && ch.hoverRealName() != null &&
                    (hoverIdentifiesDisplayName(ch.hoverText(), ch.hoverRealName(), display)
                    || (hover.inside(start, end, text) && subjectless(ch.hoverText())))) {
                candidates.add(ch.hoverRealName());
                source = Source.HOVER;
            }
            if (ch.insertionOwner() != null && ch.insertionOwner().inside(start, end, text)
                    && ch.insertionName() != null) {
                insertions.add(ch.insertionName());
            }
        }
        Matcher inline = INLINE.matcher(display);
        if (policy != Policy.METADATA && inline.matches()) {
            candidates.add(inline.group(1));
            if (source == Source.NONE) source = Source.INLINE;
        }
        // An insertion can be the nickname itself. Explicit reveals are stronger;
        // multiple reveals (or multiple eligible insertion targets) stay ambiguous.
        if (policy != Policy.METADATA && candidates.isEmpty() && display.contains("/")) {
            String[] halves = display.split("/", -1);
            if (halves.length == 2) for (String half : halves)
                if (validUsername(half.trim()) != null) candidates.add(half.trim());
            if (!candidates.isEmpty()) source = Source.ACCOUNT;
        }
        if (policy == Policy.METADATA) {
            candidates.addAll(insertions);
            if (source == Source.NONE && !insertions.isEmpty()) source = Source.INSERTION;
        }
        if (candidates.isEmpty()) {
            if (hasItalic(start, end)) insertions.remove(display);
            candidates.addAll(insertions);
            if (!candidates.isEmpty()) source = Source.INSERTION;
        }
        if (!candidates.isEmpty()) {
            return decision(candidates.size() == 1 ? State.RESOLVED : State.AMBIGUOUS,
                    display, candidates.iterator().next(), source, role, start, end, List.copyOf(candidates));
        }
        // Only a surface declaring account text may treat syntax as identity.
        if (policy == Policy.CHAT && !hasItalic(start, end) && validUsername(display) != null)
            return decision(State.RESOLVED, display, display, Source.ACCOUNT, role, start, end, List.of(display));
        if (useCache && policy != Policy.METADATA) {
            Resolution cached = NicknameResolverCache.lookup(display, policy == Policy.TRUNCATED, role, start, end, session);
            if (cached.state() != State.UNRESOLVED) return cached;
        }
        return decision(State.UNRESOLVED, display, null, Source.NONE, role, start, end, List.of());
    }

    Resolution observe(int start, int end, Policy policy, String role) {
        Resolution identity = resolve(start, end, policy, role);
        NicknameResolverCache.observe(identity);
        return identity;
    }

    /** Rendering parsers retain raw UTF-16 offsets; normalize them with this same indexer. */
    Resolution resolveRaw(String raw, int start, int end, Policy policy, String role) {
        int first = from(Component.literal(raw.substring(0, start))).text().length();
        int last = from(Component.literal(raw.substring(0, end))).text().length();
        return resolve(first, last, policy, role);
    }

    private Resolution decision(State state, String display, String username, Source source, String role,
                             int start, int end, List<String> evidence) {
        return new Resolution(state, display, username, null, source, role, start, end, evidence, session);
    }

    /** Preserve the subtree owning metadata, before inherited styles are flattened. */
    private static void appendTree(List<MetaChar> target, Component node, Style parent, Owner hover, Owner insertion) {
        Style own = node.getStyle();
        Owner currentHover = own.getHoverEvent() == null ? hover : new Owner(target.size());
        Owner currentInsertion = own.getInsertion() == null ? insertion : new Owner(target.size());
        Style effective = own.applyTo(parent);
        node.getContents().visit((style, value) -> {
            appendNormalizedFragment(target, value, style, currentHover, currentInsertion);
            return java.util.Optional.empty();
        }, effective);
        for (Component child : node.getSiblings()) appendTree(target, child, effective, currentHover, currentInsertion);
        if (currentHover != null && currentHover != hover) currentHover.end = target.size();
        if (currentInsertion != null && currentInsertion != insertion) currentInsertion.end = target.size();
    }

    private static final Pattern INLINE = Pattern.compile("^.+?\\s*\\(([A-Za-z0-9_]{3,16})\\)$");
    private static final Pattern HOVER = Pattern.compile(
            "(?i)(?:real (?:user)?name is|real username:)\\s*([A-Za-z0-9_]{3,16})\\b|^([A-Za-z0-9_]{3,16})'(?:s)? nickname is ");

    static String normalizedHoverText(Style style) {
        return style.getHoverEvent() instanceof HoverEvent.ShowText hover
                ? PacketTextNormalizer.normalizeForParsing(hover.value().getString()).replace('’', '\'').replace('‘', '\'') : null;
    }

    static String extractHoverRealUsername(Style style) {
        String text = normalizedHoverText(style);
        Matcher matcher = HOVER.matcher(text == null ? "" : text);
        return matcher.find() ? (matcher.group(1) == null ? matcher.group(2) : matcher.group(1)) : null;
    }

    static boolean hoverIdentifiesDisplayName(String text, String username, String display) {
        return text != null && (text.matches("(?i)" + Pattern.quote(display) + "'(?:s)? real (?:user)?name is\\s+" + Pattern.quote(username))
                || text.matches("(?i)" + Pattern.quote(username) + "'(?:s)? nickname is\\s+" + Pattern.quote(display)));
    }

    private static boolean subjectless(String text) {
        return text != null && text.matches("(?i)^Real Username:\\s*[a-zA-Z0-9_]{3,16}$");
    }

    static String extractInsertionUsername(Style style) { return validUsername(style.getInsertion()); }

    private static final class Owner {
        final int start;
        int end;
        Owner(int start) { this.start = start; }
        boolean inside(int first, int last, String text) {
            int a = start, b = end;
            while (a < b && Character.isWhitespace(text.charAt(a))) a++;
            while (b > a && Character.isWhitespace(text.charAt(b - 1))) b--;
            return b <= last && (a >= first || (a < first
                    && text.substring(a, first).matches("<\\d+>\\s*")));
        }
    }

    boolean metadataCovers(String username, int start, int end) {
        for (int i = start; i < end; i++) {
            MetaChar ch = characters.get(i);
            if (!Character.isWhitespace(ch.value()) && !username.equalsIgnoreCase(ch.hoverRealName())
                    && !username.equalsIgnoreCase(ch.insertionName())) return false;
        }
        return start < end;
    }

    private static void appendNormalizedFragment(List<MetaChar> target, String text, Style style, Owner hoverOwner, Owner insertionOwner) {
        if (text.isEmpty()) {
            return;
        }

        String hoverRealName = validUsername(extractHoverRealUsername(style));
        String hoverText = normalizedHoverText(style);
        String insertionName = validUsername(extractInsertionUsername(style));
        Integer baseColor = style.getColor() == null ? null : style.getColor().getValue();
        Integer currentColor = baseColor;
        boolean legacyStrikethrough = false;
        boolean legacyItalic = false;

        boolean previousWasSpace = !target.isEmpty() && target.getLast().value() == ' ';
        boolean skipLegacyFormattingCode = false;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);

            if (skipLegacyFormattingCode) {
                ChatFormatting formatting = ChatFormatting.getByCode((char) Character.toLowerCase(codePoint));
                if (formatting == ChatFormatting.RESET) {
                    currentColor = baseColor;
                    legacyStrikethrough = false;
                    legacyItalic = false;
                } else if (formatting != null) {
                    if (formatting.getColor() != null) {
                        currentColor = formatting.getColor();
                        legacyStrikethrough = false;
                        legacyItalic = false;
                    } else if (formatting == ChatFormatting.STRIKETHROUGH) {
                        legacyStrikethrough = true;
                    } else if (formatting == ChatFormatting.ITALIC) {
                        legacyItalic = true;
                    }
                }
                skipLegacyFormattingCode = false;
                continue;
            }
            if (codePoint == '§') {
                skipLegacyFormattingCode = true;
                continue;
            }

            if (Character.isWhitespace(codePoint) || isIgnorableForParsing(codePoint)) {
                if (!previousWasSpace) {
                    target.add(new MetaChar(
                            ' ',
                            hoverRealName,
                            hoverText,
                            insertionName,
                            style.isStrikethrough() || legacyStrikethrough,
                            style.isItalic() || legacyItalic,
                            currentColor, hoverOwner, insertionOwner));
                    previousWasSpace = true;
                }
                continue;
            }

            String value = new String(Character.toChars(codePoint));
            for (int charIndex = 0; charIndex < value.length(); charIndex++) {
                target.add(new MetaChar(
                        value.charAt(charIndex),
                        hoverRealName,
                        hoverText,
                        insertionName,
                        style.isStrikethrough() || legacyStrikethrough,
                        style.isItalic() || legacyItalic,
                        currentColor, hoverOwner, insertionOwner));
            }
            previousWasSpace = false;
        }
    }

    private static boolean isIgnorableForParsing(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL,
                    Character.FORMAT,
                    Character.PRIVATE_USE,
                    Character.SURROGATE,
                    Character.UNASSIGNED -> true;
            default -> false;
        };
    }

    public static String validUsername(String candidate) {
        return candidate != null && USERNAME_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    private static String toText(List<MetaChar> characters) {
        StringBuilder builder = new StringBuilder(characters.size());
        for (MetaChar character : characters) {
            builder.append(character.value());
        }
        return builder.toString();
    }

    boolean hasStrikethrough(int startInclusive, int endExclusive) {
        int cappedStart = Math.max(0, startInclusive);
        int cappedEnd = Math.min(endExclusive, characters.size());
        for (int index = cappedStart; index < cappedEnd; index++) {
            if (characters.get(index).strikethrough()) {
                return true;
            }
        }
        return false;
    }

    boolean hasItalic(int startInclusive, int endExclusive) {
        int cappedStart = Math.max(0, startInclusive);
        int cappedEnd = Math.min(endExclusive, characters.size());
        for (int index = cappedStart; index < cappedEnd; index++) {
            if (characters.get(index).italic()) {
                return true;
            }
        }
        return false;
    }

    boolean hasOnlyRedHealthCharacters(int startInclusive, int endExclusive) {
        int cappedStart = Math.max(0, startInclusive);
        int cappedEnd = Math.min(endExclusive, characters.size());
        boolean foundHealthCharacter = false;
        for (int index = cappedStart; index < cappedEnd; index++) {
            MetaChar character = characters.get(index);
            if (Character.isWhitespace(character.value())) {
                continue;
            }
            foundHealthCharacter = true;
            if (!isRedHealthColor(character.color())) {
                return false;
            }
        }
        return foundHealthCharacter;
    }

    private static boolean isRedHealthColor(Integer color) {
        if (color == null) {
            return false;
        }

        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        return red >= 120 && green <= 120 && blue <= 120 && red > green && red > blue;
    }

    private record MetaChar(
            char value,
            String hoverRealName,
            String hoverText,
            String insertionName,
            boolean strikethrough,
            boolean italic,
            Integer color, Owner hoverOwner, Owner insertionOwner) {}

}
