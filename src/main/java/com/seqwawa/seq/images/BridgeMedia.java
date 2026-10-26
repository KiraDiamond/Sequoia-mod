package com.seqwawa.seq.images;

import com.google.gson.JsonParser;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Maps chat links to the media the Discord bridge delivered for them.
 *
 * <p>The bridge protocol carries no message identity, and {@code discord_media} updates carry
 * only a display name. Delayed media is therefore never attached to an earlier message: a signed
 * attachment only refreshes links to that exact attachment path, an MP4 rendition only resolves
 * the exact Klipy GIF it renders, and delayed Klipy media is always shown as its own message.
 */
public final class BridgeMedia {
    private static final int MAX_REMEMBERED = 128;
    private static final LinkedHashMap<String, URI> RESOLVED = new LinkedHashMap<>();
    private static final Set<String> TYPES = Set.of("discord_chat", "discord_media");
    private static final Set<String> DISCORD_PROXIES =
            Set.of("images-ext-1.discordapp.net", "images-ext-2.discordapp.net");
    private static final List<Pattern> SIGNATURE = Stream.of("ex", "is", "hm")
            .map(key -> Pattern.compile("(?:^|&)" + key + "=[0-9a-fA-F]+(?:&|$)"))
            .toList();

    private BridgeMedia() {}

    public static synchronized URI resolve(URI uri) {
        return RESOLVED.getOrDefault(key(uri), uri);
    }

    public static synchronized void clear() {
        RESOLVED.clear();
    }

    private static String key(URI uri) {
        return discordAttachment(uri) ? "attachment:" + uri.getRawPath() : uri.toString();
    }

    private static void remember(URI original, URI media) {
        RESOLVED.put(key(original), media);
        while (RESOLVED.size() > MAX_REMEMBERED)
            RESOLVED.remove(RESOLVED.keySet().iterator().next());
    }

    /** Path of a Klipy asset without its extension, shared by its GIF and MP4 renditions. */
    private static String klipyAsset(URI uri) {
        String path = uri.getRawPath();
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    /** Returns null when hidden media leaves no chat message to deliver. */
    public static synchronized String rewrite(String payload) {
        boolean hidden = ImagePreviewSettings.hidden();
        if (!hidden && !ImagePreviewSettings.enabled()) return payload;
        if (payload == null || payload.length() > 1_000_000
                || (!hidden && !payload.contains("\"media_urls\""))) return payload;
        try {
            var json = JsonParser.parseString(payload).getAsJsonObject();
            String type = json.get("type").getAsString();
            if (!TYPES.contains(type)) return payload;
            if (hidden && type.equals("discord_media")) return null;
            boolean chat = type.equals("discord_chat");
            if (chat
                    && (!json.has("message")
                            || !json.get("message").isJsonPrimitive()
                            || !json.get("message").getAsJsonPrimitive().isString())) return payload;
            if (hidden) {
                var delivered = new ArrayList<String>();
                if (json.has("media_urls") && json.get("media_urls").isJsonArray())
                    for (var value : json.getAsJsonArray("media_urls"))
                        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
                            delivered.add(value.getAsString());
                String message = json.get("message").getAsString();
                String filtered = HiddenMedia.filter(message, delivered);
                if (filtered.isBlank()) return null;
                if (filtered.equals(message)) return payload;
                json.addProperty("message", filtered);
                return json.toString();
            }
            if (!json.has("media_urls") || !json.get("media_urls").isJsonArray()) return payload;
            var signed = new ArrayList<URI>();
            var klipy = new ArrayList<URI>();
            for (var value : json.getAsJsonArray("media_urls")) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
                String url = value.getAsString();
                URI uri = MediaLinks.imageUri(url);
                if (discordAttachment(uri) && hasSignature(uri)) {
                    signed.add(uri);
                    remember(uri, uri);
                }
                URI gif = klipyMedia(url);
                if (gif != null && !klipy.contains(gif)) {
                    klipy.add(gif);
                    if (MediaLinks.isKlipyVideo(gif))
                        remember(URI.create("https://static.klipy.com" + klipyAsset(gif) + ".gif"), gif);
                }
            }
            if (chat) {
                String message = json.get("message").getAsString();
                List<URI> links = MediaLinks.find(message);
                List<URI> pages = links.stream()
                        .filter(MediaLinks::isKlipyPage)
                        .distinct()
                        .toList();
                String rewritten = replace(message, signed);
                for (URI original : links) {
                    if (!"static.klipy.com".equalsIgnoreCase(original.getHost())
                            || !original.getRawPath().endsWith(".gif")) continue;
                    for (URI actual : klipy)
                        if (klipyAsset(actual).equals(klipyAsset(original))) remember(original, actual);
                }
                // Only media inside the same message can belong to its share page.
                if (pages.size() == 1 && klipy.size() == 1) {
                    remember(pages.getFirst(), klipy.getFirst());
                    rewritten = rewritten.replace(
                            pages.getFirst().toString(), klipy.getFirst().toString());
                }
                if (rewritten.equals(message)) return payload;
                json.addProperty("message", rewritten);
                return json.toString();
            }
            // Signed attachments above already refresh their exact attachment paths.
            if (klipy.isEmpty()) return payload;
            json.addProperty("type", "discord_chat");
            json.addProperty(
                    "message",
                    String.join(" ", klipy.stream().map(URI::toString).toList()));
            return json.toString();
        } catch (RuntimeException invalidPayload) {
            return payload;
        }
    }

    private static URI klipyMedia(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            if (DISCORD_PROXIES.contains(uri.getHost())) {
                String path = uri.getRawPath();
                String marker = "/https/static.klipy.com/";
                int start = path.indexOf(marker);
                if (!path.startsWith("/external/") || start < 0) return null;
                uri = URI.create("https://static.klipy.com/" + path.substring(start + marker.length()));
            }
            if (!MediaLinks.allowed(uri)
                    || !"static.klipy.com".equalsIgnoreCase(uri.getHost())
                    || !uri.getRawPath().startsWith("/ii/")) return null;
            String path = uri.getRawPath();
            if (!path.endsWith(".mp4") && !path.endsWith(".gif")) return null;
            return MediaLinks.imageUri("https://static.klipy.com" + path);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String replace(String message, List<URI> signed) {
        var result = new StringBuilder();
        int cursor = 0;
        for (var link : MediaLinks.matches(message)) {
            if (!discordAttachment(link.uri())) continue;
            URI replacement = null;
            for (URI candidate : signed)
                if (candidate.getRawPath().equals(link.uri().getRawPath())) {
                    replacement = candidate;
                    break;
                }
            if (replacement == null) continue;
            result.append(message, cursor, link.start()).append(replacement);
            cursor = link.end();
        }
        return cursor == 0
                ? message
                : result.append(message, cursor, message.length()).toString();
    }

    static boolean discordAttachment(URI uri) {
        return uri != null
                && MediaLinks.allowed(uri)
                && ("cdn.discordapp.com".equalsIgnoreCase(uri.getHost())
                        || "media.discordapp.net".equalsIgnoreCase(uri.getHost()))
                && uri.getRawPath().startsWith("/attachments/");
    }

    private static boolean hasSignature(URI uri) {
        String query = uri.getRawQuery();
        return query != null
                && SIGNATURE.stream().allMatch(key -> key.matcher(query).find());
    }
}
