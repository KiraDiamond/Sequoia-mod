package com.seqwawa.seq.managers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;

/**
 * Resolves Minecraft usernames from packet-level chat components while
 * preserving hover/insertion metadata after Wynncraft formatting cleanup.
 */
final class PacketNameResolver {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final String text;
    private final List<MetaChar> characters;

    private PacketNameResolver(String text, List<MetaChar> characters) {
        this.text = text;
        this.characters = characters;
    }

    static PacketNameResolver from(Component message) {
        List<MetaChar> characters = new ArrayList<>();
        if (message != null) {
            for (Component fragment : message.toFlatList()) {
                appendNormalizedFragment(characters, fragment);
            }
        }
        return new PacketNameResolver(toText(characters), List.copyOf(characters));
    }

    String text() {
        return text;
    }

    String resolveUsername(String displayedName, int startInclusive, int endExclusive) {
        String trimmed = displayedName == null ? "" : displayedName.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        NameMetadata metadata = metadataForRange(startInclusive, endExclusive);
        if (!metadata.hoverRealNames().isEmpty()) {
            return metadata.uniqueHoverRealName();
        }

        if (!metadata.insertionNames().isEmpty()) {
            return metadata.uniqueInsertionName();
        }

        String displayedUsername = validUsername(trimmed);
        if (displayedUsername != null) {
            return displayedUsername;
        }

        return null;
    }

    String resolveMetadataUsername(int startInclusive, int endExclusive) {
        return metadataForRange(startInclusive, endExclusive).username();
    }

    String resolveSpeakerMetadataUsername(String displayedName, int startInclusive, int endExclusive) {
        return metadataForRange(startInclusive, endExclusive, displayedName).username();
    }

    boolean hasConflictingMetadata(String displayedName, int startInclusive, int endExclusive) {
        NameMetadata metadata = metadataForRange(startInclusive, endExclusive, displayedName);
        return metadata.hoverRealNames().size() > 1
                || (metadata.hoverRealNames().isEmpty() && metadata.insertionNames().size() > 1);
    }

    /** Current speaker metadata wins; an old alias must not rename a plain account. */
    String resolveChatUsername(String displayedName, int startInclusive, int endExclusive, boolean useCache) {
        NameMetadata metadata = metadataForRange(startInclusive, endExclusive, displayedName);
        if (!metadata.hoverRealNames().isEmpty() || !metadata.insertionNames().isEmpty()) {
            return metadata.username();
        }
        String displayedUsername = validUsername(displayedName.trim());
        if (displayedUsername != null && !hasItalic(startInclusive, endExclusive)) {
            return displayedUsername;
        }
        String cached = useCache ? NicknameResolverCache.resolveUsername(displayedName) : null;
        return cached != null ? cached : displayedUsername;
    }

    private NameMetadata metadataForRange(int startInclusive, int endExclusive, String displayedName) {
        Set<String> hoverRealNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> insertionNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Boolean> hoverSubjects = new HashMap<>();

        int cappedStart = Math.max(0, startInclusive);
        int cappedEnd = Math.min(endExclusive, characters.size());
        for (int index = cappedStart; index < cappedEnd; index++) {
            MetaChar character = characters.get(index);
            if (character.hoverRealName() != null && (displayedName == null
                    || hoverSubjects.computeIfAbsent(character.hoverText(), text -> ChatManager.hoverBelongsToSpeaker(
                            text, character.hoverRealName(), displayedName)))) {
                hoverRealNames.add(character.hoverRealName());
            }
            if (character.insertionName() != null) {
                insertionNames.add(character.insertionName());
            }
        }

        return new NameMetadata(List.copyOf(hoverRealNames), List.copyOf(insertionNames));
    }

    private NameMetadata metadataForRange(int startInclusive, int endExclusive) {
        return metadataForRange(startInclusive, endExclusive, null);
    }

    boolean hasUsernameMetadataThroughout(String username, int startInclusive, int endExclusive) {
        if (username == null || username.isBlank()) {
            return false;
        }

        boolean foundVisibleCharacter = false;
        int cappedStart = Math.max(0, startInclusive);
        int cappedEnd = Math.min(endExclusive, characters.size());
        for (int index = cappedStart; index < cappedEnd; index++) {
            MetaChar character = characters.get(index);
            if (Character.isWhitespace(character.value())) {
                continue;
            }
            foundVisibleCharacter = true;
            String metadataUsername = character.hoverRealName() != null
                    ? character.hoverRealName()
                    : character.insertionName();
            if (!username.equalsIgnoreCase(metadataUsername)) {
                return false;
            }
        }
        return foundVisibleCharacter;
    }

    private static void appendNormalizedFragment(List<MetaChar> target, Component fragment) {
        String text = extractFragmentText(fragment);
        if (text.isEmpty()) {
            return;
        }

        Style style = fragment.getStyle();
        String hoverRealName = validUsername(ChatManager.extractHoverRealUsername(style));
        String hoverText = ChatManager.normalizedHoverText(style);
        String insertionName = validUsername(ChatManager.extractInsertionUsername(style));
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
                            currentColor));
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
                        currentColor));
            }
            previousWasSpace = false;
        }
    }

    private static String extractFragmentText(Component fragment) {
        if (fragment.getContents() instanceof PlainTextContents plainTextContents) {
            return plainTextContents.text();
        }
        return fragment.getString();
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

    private static String validUsername(String candidate) {
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
            Integer color) {}

    private record NameMetadata(List<String> hoverRealNames, List<String> insertionNames) {
        private String username() {
            return hoverRealNames.isEmpty() ? uniqueInsertionName() : uniqueHoverRealName();
        }
        private String uniqueHoverRealName() {
            return hoverRealNames.size() == 1 ? hoverRealNames.get(0) : null;
        }

        private String uniqueInsertionName() {
            return insertionNames.size() == 1 ? insertionNames.get(0) : null;
        }
    }
}
