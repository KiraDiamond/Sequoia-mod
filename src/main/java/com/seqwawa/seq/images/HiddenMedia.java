package com.seqwawa.seq.images;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Removes Discord media from display only; these rules never authorize a download. */
final class HiddenMedia {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"\\[\\]]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE = Pattern.compile(".*\\.(gif|png|jpe?g|webp|avif|bmp|svg|mp4|webm|mov|m4v|mp3|ogg|wav)$");

    private HiddenMedia() {}

    static String filter(String message, List<String> delivered) {
        Set<String> attachments = new HashSet<>();
        for (String url : delivered) {
            URI uri = uri(url);
            if (uri != null) attachments.add(key(uri));
        }
        var lines = new ArrayList<String>();
        boolean changed = false;
        for (String line : message.split("\\R", -1)) {
            String filtered = line(line, attachments);
            boolean removed = !line.equals(filtered);
            changed |= removed;
            if (!removed || !filtered.isBlank()) lines.add(filtered);
        }
        return changed ? String.join("\n", lines).strip() : message;
    }

    private static String line(String line, Set<String> attachments) {
        var matcher = URL.matcher(line);
        var result = new StringBuilder();
        int cursor = 0;
        int textCursor = 0;
        boolean removed = false;
        boolean accompanyingText = false;
        while (matcher.find()) {
            String token = matcher.group();
            while (!token.isEmpty() && ".,;!?)".indexOf(token.charAt(token.length() - 1)) >= 0)
                token = token.substring(0, token.length() - 1);
            URI uri = uri(token);
            if (uri == null || !media(uri, attachments)) continue;
            int start = matcher.start(), end = start + token.length();
            if (start > cursor && end < line.length()) {
                char open = line.charAt(start - 1), close = line.charAt(end);
                if (open == '<' && close == '>' || open == '[' && close == ']' || open == '(' && close == ')') {
                    start--;
                    end++;
                    // Discord markdown media links: remove their label with the hidden URL.
                    if (open == '(' && start > cursor && line.charAt(start - 1) == ']') {
                        int label = line.lastIndexOf('[', start - 1);
                        if (label >= cursor) start = label;
                    }
                }
            }
            if (start > textCursor && !line.substring(textCursor, start).isBlank()) accompanyingText = true;
            textCursor = end;
            // Ignore punctuation attached to a media-only link, but keep separate text such as "!!!".
            while (textCursor < line.length() && ".,;!?".indexOf(line.charAt(textCursor)) >= 0) textCursor++;
            result.append(line, cursor, start);
            if (!result.isEmpty() && horizontal(result.charAt(result.length() - 1)))
                while (end < line.length() && horizontal(line.charAt(end))) end++;
            cursor = end;
            removed = true;
        }
        if (!removed) return line;
        String filtered = result.append(line, cursor, line.length()).toString().strip();
        return accompanyingText || !line.substring(textCursor).isBlank() ? filtered : "";
    }

    private static boolean horizontal(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean media(URI uri, Set<String> attachments) {
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return attachments.contains(key(uri))
                || MediaLinks.imageUri(uri.toString()) != null
                || FILE.matcher(path).matches()
                || MediaLinks.isTenorPage(uri)
                || MediaLinks.isKlipyPage(uri)
                || ((host.equals("giphy.com") || host.equals("www.giphy.com")) && path.startsWith("/gifs/"));
    }

    private static String key(URI uri) {
        return BridgeMedia.discordAttachment(uri) ? "attachment:" + uri.getRawPath() : uri.toString();
    }

    private static URI uri(String token) {
        try {
            URI uri = URI.create(token);
            return uri.getHost() != null && uri.getPath() != null
                            && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    ? uri : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
