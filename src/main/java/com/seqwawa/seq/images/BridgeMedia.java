package com.seqwawa.seq.images;

import com.google.gson.JsonParser;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class BridgeMedia {
    private static final LinkedHashMap<String, URI> RESOLVED = new LinkedHashMap<>();
    private static final LinkedHashMap<String, List<Pending>> PENDING = new LinkedHashMap<>();

    private record Pending(URI uri, long time) {}

    private BridgeMedia() {}

    public static synchronized URI resolve(URI uri) {
        return RESOLVED.getOrDefault(key(uri), uri);
    }

    public static synchronized void clear() {
        RESOLVED.clear();
        PENDING.clear();
    }

    private static String key(URI uri) {
        return discordAttachment(uri) ? "attachment:" + uri.getRawPath() : uri.toString();
    }

    private static void remember(URI original, URI media) {
        RESOLVED.put(key(original), media);
        while (RESOLVED.size() > 128) RESOLVED.remove(RESOLVED.keySet().iterator().next());
    }

    public static synchronized String rewrite(String payload) {
        if (!ImagePreviewSettings.enabled()) return payload;
        if (payload == null || payload.length() > 1_000_000 || !payload.contains("\"media_urls\""))
            return payload;
        try {
            var json = JsonParser.parseString(payload).getAsJsonObject();
            String type = json.get("type").getAsString();
            if (!Set.of("discord_chat", "discord_media").contains(type)
                    || !json.get("media_urls").isJsonArray()) return payload;
            String user = json.has("username") ? json.get("username").getAsString() : "";
            boolean chat = type.equals("discord_chat");
            if (chat
                    && (!json.has("message")
                            || !json.get("message").isJsonPrimitive()
                            || !json.get("message").getAsJsonPrimitive().isString()))
                return payload;
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
                    if (MediaLinks.isKlipyVideo(gif)) {
                        String path = gif.getRawPath();
                        remember(
                                URI.create(
                                        "https://static.klipy.com"
                                                + path.substring(0, path.length() - 4)
                                                + ".gif"),
                                gif);
                    }
                }
            }
            long now = System.nanoTime();
            PENDING.values()
                    .forEach(list -> list.removeIf(item -> now - item.time() > 30_000_000_000L));
            PENDING.values().removeIf(List::isEmpty);
            if (chat) {
                String message = json.get("message").getAsString();
                List<URI> pages =
                        MediaLinks.find(message).stream()
                                .filter(MediaLinks::isKlipyPage)
                                .distinct()
                                .toList();
                String rewritten = replace(message, signed);
                for (URI original : MediaLinks.find(message)) {
                    if (!"static.klipy.com".equalsIgnoreCase(original.getHost())
                            || !original.getRawPath().endsWith(".gif")) continue;
                    String stem =
                            original.getRawPath().substring(0, original.getRawPath().length() - 4);
                    for (URI actual : klipy) {
                        if (actual.getRawPath().equals(stem + ".mp4")) remember(original, actual);
                    }
                }
                if (pages.size() == 1 && klipy.size() == 1) {
                    remember(pages.getFirst(), klipy.getFirst());
                    rewritten =
                            rewritten.replace(
                                    pages.getFirst().toString(), klipy.getFirst().toString());
                } else if (!user.isEmpty() && !pages.isEmpty()) {
                    var pending = PENDING.computeIfAbsent(user, ignored -> new ArrayList<>());
                    for (URI page : pages) pending.add(new Pending(page, now));
                    while (pending.size() > 8) pending.removeFirst();
                    while (PENDING.size() > 32) PENDING.remove(PENDING.keySet().iterator().next());
                }
                if (rewritten.equals(message)) return payload;
                json.addProperty("message", rewritten);
                return json.toString();
            }
            if (klipy.isEmpty())
                return payload; // Discord attachments use their path as the key, so late signatures
                                // still resolve.
            List<Pending> pending = PENDING.remove(user);
            if (pending != null && pending.size() == 1 && klipy.size() == 1) {
                remember(pending.getFirst().uri(), klipy.getFirst());
                return payload;
            }
            // Without a message ID, guessing could attach this media to the wrong chat message.
            // Show it separately with the sender's name instead.
            json.addProperty("type", "discord_chat");
            json.addProperty(
                    "message", String.join(" ", klipy.stream().map(URI::toString).toList()));
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
            if (Set.of("images-ext-1.discordapp.net", "images-ext-2.discordapp.net")
                    .contains(uri.getHost())) {
                String path = uri.getRawPath();
                String marker = "/https/static.klipy.com/";
                int start = path.indexOf(marker);
                if (!path.startsWith("/external/") || start < 0) return null;
                uri =
                        URI.create(
                                "https://static.klipy.com/"
                                        + path.substring(start + marker.length()));
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
        return cursor == 0 ? message : result.append(message, cursor, message.length()).toString();
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
        if (query == null) return false;
        for (String key : List.of("ex", "is", "hm"))
            if (!Pattern.compile("(?:^|&)" + key + "=[0-9a-fA-F]+(?:&|$)").matcher(query).find())
                return false;
        return true;
    }
}
