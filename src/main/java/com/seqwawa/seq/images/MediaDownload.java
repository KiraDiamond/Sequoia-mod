package com.seqwawa.seq.images;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.util.Timeout;

final class MediaDownload {
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, r -> {
        var thread = new Thread(r, "Sequoia media deadline");
        thread.setDaemon(true);
        return thread;
    });

    static {
        TIMER.setRemoveOnCancelPolicy(true);
    }

    /** Whole-download deadline, also bounding DNS through {@link PublicDns}. */
    static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(20);

    private static final Pattern TAG = Pattern.compile("<meta\\b[^>]{0,8192}>", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG =
            Pattern.compile("(?:property|name)\\s*=\\s*[\"']og:image[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT =
            Pattern.compile("content\\s*=\\s*[\"']([^\"']{1,4096})[\"']", Pattern.CASE_INSENSITIVE);

    /** A Discord attachment whose signed URL has not arrived yet. */
    static final class PendingMedia extends IOException {
        PendingMedia() {
            super("Waiting for Discord media...");
        }
    }

    /**
     * A Klipy share page with no media from the same bridge message. Its media, if Discord sends
     * it later, is shown as its own message because the bridge carries no message identity.
     */
    static final class UnresolvedKlipyPage extends IOException {
        UnresolvedKlipyPage() {
            super("Klipy page link; its GIF appears as a separate bridge message");
        }
    }

    record Reply(int status, String location, byte[] bytes) {}

    @FunctionalInterface
    interface Exchange {
        Reply request(URI uri, int limit, long deadline) throws IOException;
    }

    static byte[] fetch(URI uri) throws IOException {
        try (var lease = new ByteBudget(MediaFormat.MAX_BYTES).open()) {
            return fetch(uri, new MediaCancellation(), lease);
        }
    }

    /**
     * Downloads one allowlisted media file, following at most three validated redirects. The lease is
     * charged for the largest body allowed; the unused part is returned once the body is read.
     */
    static byte[] fetch(URI uri, MediaCancellation cancellation, ByteBudget.Lease lease) throws IOException {
        cancellation.check();
        if (!MediaLinks.allowed(uri)) throw new IOException("Unsupported image destination");
        if (MediaLinks.isKlipyPage(uri)) throw new UnresolvedKlipyPage();
        long deadline = System.nanoTime() + DEADLINE_NANOS;
        lease.reserve(MediaFormat.MAX_BYTES);
        var connectionFactory = ManagedHttpClientConnectionFactory.builder()
                .http1Config(Http1Config.custom()
                        .setMaxLineLength(8192)
                        .setMaxHeaderCount(64)
                        .build())
                .build();
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PublicDns(deadline))
                .setConnectionFactory(connectionFactory)
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(8))
                        .build())
                .build();
        try (var client = HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setAuthenticationEnabled(false)
                        .setConnectionRequestTimeout(Timeout.ofSeconds(2))
                        .setResponseTimeout(Timeout.ofSeconds(8))
                        .build())
                .disableCookieManagement()
                .disableContentCompression()
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .setUserAgent("SequoiaImages/1.1.5")
                .build()) {
            byte[] body = fetch(
                    uri,
                    (destination, limit, until) -> request(client, destination, limit, until, cancellation, lease),
                    deadline);
            lease.release(MediaFormat.MAX_BYTES - body.length);
            return body;
        }
    }

    static byte[] fetch(URI start, Exchange exchange, long deadline) throws IOException {
        URI uri = start;
        for (int depth = 0; depth <= 3; depth++) {
            checkDeadline(deadline);
            if (!MediaLinks.allowed(uri)) throw new IOException("Unsupported image destination");
            boolean page = MediaLinks.isTenorPage(uri);
            int limit = page ? 256 * 1024 : MediaFormat.MAX_BYTES;
            Reply reply = exchange.request(uri, limit, deadline);
            if (reply.status() >= 300 && reply.status() < 400) {
                if (reply.location() == null) throw new IOException("Image redirect missing destination");
                try {
                    uri = uri.resolve(reply.location());
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid image redirect");
                }
                continue;
            }
            if (reply.status() != 200) {
                boolean gone = reply.status() == 403 || reply.status() == 404;
                if (gone && BridgeMedia.discordAttachment(uri)) throw new PendingMedia();
                throw new IOException(gone ? "Image link unavailable" : "Image server returned HTTP " + reply.status());
            }
            if (reply.bytes().length > limit) throw new IOException("Media exceeds download limit");
            if (page) {
                String html = new String(reply.bytes(), StandardCharsets.UTF_8);
                var tags = TAG.matcher(html);
                URI media = null;
                while (tags.find()) {
                    String tag = tags.group();
                    if (!OG.matcher(tag).find()) continue;
                    var content = CONTENT.matcher(tag);
                    if (content.find()) {
                        URI found = MediaLinks.imageUri(content.group(1).replace("&amp;", "&"));
                        if (found != null && !MediaLinks.isTenorPage(found) && !MediaLinks.isKlipyPage(found)) {
                            media = found;
                            break;
                        }
                    }
                }
                if (media == null) throw new IOException("No GIF on this Tenor page; share its direct GIF link");
                uri = media;
                continue;
            }
            String format = MediaFormat.identify(reply.bytes());
            if (format.equals("mp4") && !MediaLinks.isKlipyVideo(uri))
                throw new IOException("Unsupported video destination");
            return reply.bytes();
        }
        throw new IOException("Too many image redirects");
    }

    static Reply request(
            CloseableHttpClient client,
            URI uri,
            int limit,
            long deadline,
            MediaCancellation cancellation,
            ByteBudget.Lease lease)
            throws IOException {
        cancellation.check();
        checkDeadline(deadline);
        var request = new HttpGet(uri);
        request.setHeader("Accept", "image/gif,image/png,image/jpeg,video/mp4,text/html;q=0.5");
        request.setHeader("Accept-Encoding", "identity");
        request.setHeader("Connection", "close");
        var watchdog = TIMER.schedule(request::cancel, Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        try (var abort = cancellation.attach(request::cancel);
                var response = client.executeOpen(null, request, null)) {
            try {
                int status = response.getCode();
                var location = response.getFirstHeader("Location");
                if (status != 200) return new Reply(status, location == null ? null : location.getValue(), new byte[0]);
                var entity = response.getEntity();
                if (entity == null) throw new IOException("Empty image response");
                String encoding = entity.getContentEncoding();
                if (encoding != null && !encoding.equalsIgnoreCase("identity"))
                    throw new IOException("Unsupported HTTP content encoding");
                long declared = entity.getContentLength();
                // Without a length the body grows and is copied once, briefly holding up to twice the
                // limit; the fetch already reserved one limit.
                long growth = declared < 0 ? limit : 0;
                lease.reserve(growth);
                try {
                    return new Reply(status, null, readLimited(entity.getContent(), limit, declared, deadline));
                } finally {
                    lease.release(growth);
                }
            } finally {
                request.cancel(); // Abort here: closing the response first may drain an oversized body.
            }
        } finally {
            watchdog.cancel(false);
        }
    }

    /**
     * Reads a body of at most {@code limit} bytes. A declared length (negative when unknown, as HttpEntity
     * reports it) is read into an exact array and must match; otherwise the body grows in chunks.
     */
    static byte[] readLimited(InputStream stream, int limit, long declared, long deadline) throws IOException {
        if (declared > limit) throw new IOException("Media exceeds download limit");
        if (declared >= 0) {
            byte[] body = new byte[(int) declared];
            int filled = 0;
            while (filled < body.length) {
                checkDeadline(deadline);
                int count = stream.read(body, filled, Math.min(1 << 16, body.length - filled));
                if (count < 0) throw new IOException("Truncated media response");
                filled += count;
            }
            checkDeadline(deadline);
            if (stream.read() != -1) throw new IOException("Media exceeds declared length");
            return body;
        }
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while (true) {
            checkDeadline(deadline);
            count = stream.read(buffer, 0, Math.min(buffer.length, limit - output.size() + 1));
            if (count < 0) break;
            if (output.size() + count > limit) throw new IOException("Media exceeds download limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
            throw new IOException("Image download timed out");
    }
}
