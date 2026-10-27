package com.seqwawa.seq.images;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Recognizes chat links to media on allowlisted hosts. */
final class MediaLinks {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"\\[\\]]+");
    private static final String FX_GIF_HOST = "gif.fxtwitter.com";
    private static final Pattern FX_GIF_PATH = Pattern.compile("/tweet_video/[A-Za-z0-9_-]{1,128}\\.(?:gif|webp)");
    private static final Set<String> HOSTS = Set.of(
            "cdn.discordapp.com",
            "media.discordapp.net",
            "media.tenor.com",
            "media1.tenor.com",
            "c.tenor.com",
            "tenor.com",
            "www.tenor.com",
            "media.giphy.com",
            "media0.giphy.com",
            "media1.giphy.com",
            "media2.giphy.com",
            "media3.giphy.com",
            "media4.giphy.com",
            "i.giphy.com",
            "i.imgur.com",
            FX_GIF_HOST,
            "klipy.com",
            "www.klipy.com",
            "static.klipy.com");
    private static final Set<String> KLIPY_PAGES = Set.of("klipy.com", "www.klipy.com");
    private static final Set<String> TENOR_PAGES = Set.of("tenor.com", "www.tenor.com");

    private MediaLinks() {}

    record Link(int start, int end, URI uri) {}

    static List<URI> find(String text) {
        return matches(text).stream().map(Link::uri).toList();
    }

    static List<Link> matches(String text) {
        var results = new ArrayList<Link>();
        var matcher = URL.matcher(text);
        while (results.size() < 32 && matcher.find()) {
            URI uri = imageUri(matcher.group());
            if (uri != null)
                results.add(new Link(
                        matcher.start(), matcher.start() + uri.toString().length(), uri));
        }
        return results;
    }

    static URI imageUri(String token) {
        if (token == null || token.length() > 4096) return null;
        while (!token.isEmpty() && ".,;!?)".indexOf(token.charAt(token.length() - 1)) >= 0)
            token = token.substring(0, token.length() - 1);
        try {
            URI uri = URI.create(token);
            if (!allowed(uri)) return null;
            String p = uri.getPath().toLowerCase(Locale.ROOT);
            if (p.matches(".*\\.(gif|png|jpe?g|webp)$") || isTenorPage(uri) || isKlipyPage(uri) || isKlipyVideo(uri))
                return uri;
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    static boolean allowed(URI uri) {
        return uri != null
                && uri.toString().length() <= 4096
                && uri.getRawFragment() == null
                && uri.getPath() != null
                && uri.getPath().chars().noneMatch(c -> c < 32 || c == 127)
                && (uri.getQuery() == null || uri.getQuery().chars().noneMatch(c -> c < 32 || c == 127))
                && "https".equalsIgnoreCase(uri.getScheme())
                && allowedHost(uri.getHost())
                && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && (!host(uri).equals(FX_GIF_HOST) || isFxGif(uri));
    }

    /** DNS checks the exact host; requests and redirects additionally check endpoint restrictions. */
    static boolean allowedHost(String host) {
        return host != null && HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private static boolean isFxGif(URI uri) {
        return host(uri).equals(FX_GIF_HOST)
                && uri.getRawQuery() == null
                && uri.getRawPath() != null
                && FX_GIF_PATH.matcher(uri.getRawPath()).matches();
    }

    /** Whether the link is shown as a GIF rather than a still image. */
    static boolean isAnimated(URI uri) {
        return uri.getPath().toLowerCase(Locale.ROOT).endsWith(".gif")
                || isTenorPage(uri)
                || isKlipyPage(uri)
                || isKlipyVideo(uri)
                || isFxGif(uri);
    }

    static boolean isKlipyVideo(URI uri) {
        return host(uri).equals("static.klipy.com")
                && uri.getPath().startsWith("/ii/")
                && uri.getPath().endsWith(".mp4");
    }

    static boolean isKlipyPage(URI uri) {
        return KLIPY_PAGES.contains(host(uri)) && uri.getPath().startsWith("/gifs/");
    }

    static boolean isTenorPage(URI uri) {
        return TENOR_PAGES.contains(host(uri)) && uri.getPath().startsWith("/view/");
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }
}
