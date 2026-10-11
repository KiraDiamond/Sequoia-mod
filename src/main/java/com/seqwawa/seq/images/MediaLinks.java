package com.seqwawa.seq.images;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class MediaLinks {
    public static final Pattern URL = Pattern.compile("https?://[^\\s<>\"\\[\\]]+");
    private static final Set<String> HOSTS =
            Set.of(
                    "cdn.discordapp.com",
                    "media.discordapp.net",
                    "media.tenor.com",
                    "c.tenor.com",
                    "tenor.com",
                    "www.tenor.com",
                    "media.giphy.com",
                    "i.giphy.com",
                    "i.imgur.com",
                    "klipy.com",
                    "www.klipy.com",
                    "static.klipy.com");

    private MediaLinks() {}

    public static List<URI> find(String text) {
        return matches(text).stream().map(Link::uri).toList();
    }

    public record Link(int start, int end, URI uri) {}

    public static List<Link> matches(String text) {
        var results = new ArrayList<Link>();
        var matcher = URL.matcher(text);
        while (results.size() < 32 && matcher.find()) {
            URI uri = imageUri(matcher.group());
            if (uri != null)
                results.add(
                        new Link(matcher.start(), matcher.start() + uri.toString().length(), uri));
        }
        return results;
    }

    public static URI imageUri(String token) {
        if (token == null || token.length() > 4096) return null;
        while (!token.isEmpty() && ".,;!?)".indexOf(token.charAt(token.length() - 1)) >= 0)
            token = token.substring(0, token.length() - 1);
        try {
            URI uri = URI.create(token);
            if (!allowed(uri)) return null;
            String p = uri.getPath().toLowerCase(Locale.ROOT);
            if (p.matches(".*\\.(gif|png|jpe?g)$")
                    || isTenorPage(uri)
                    || isKlipyPage(uri)
                    || isKlipyVideo(uri)) return uri;
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    public static boolean allowed(URI uri) {
        return uri != null
                && uri.toString().length() <= 4096
                && uri.getRawFragment() == null
                && uri.getPath() != null
                && uri.getPath().chars().noneMatch(c -> c < 32 || c == 127)
                && (uri.getQuery() == null
                        || uri.getQuery().chars().noneMatch(c -> c < 32 || c == 127))
                && "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    public static boolean isKlipyVideo(URI uri) {
        return "static.klipy.com".equalsIgnoreCase(uri.getHost())
                && uri.getPath().startsWith("/ii/")
                && uri.getPath().endsWith(".mp4");
    }

    public static boolean isKlipyPage(URI uri) {
        return Set.of("klipy.com", "www.klipy.com").contains(uri.getHost())
                && uri.getPath().startsWith("/gifs/");
    }

    public static boolean isTenorPage(URI uri) {
        return Set.of("tenor.com", "www.tenor.com").contains(uri.getHost())
                && uri.getPath().startsWith("/view/");
    }
}
