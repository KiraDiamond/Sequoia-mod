package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

class SecurityTest {
    @TempDir Path temporary;

    private static long deadline() {
        return System.nanoTime() + 2_000_000_000L;
    }

    @Test
    void privateReservedAndTransitionNetworksAreBlocked() throws Exception {
        for (String ip :
                List.of(
                        "0.0.0.0",
                        "10.0.0.1",
                        "127.0.0.1",
                        "169.254.169.254",
                        "172.16.0.1",
                        "192.168.1.1",
                        "100.64.0.1",
                        "192.0.0.1",
                        "192.0.2.1",
                        "192.88.99.1",
                        "198.18.0.1",
                        "198.51.100.1",
                        "203.0.113.1",
                        "224.0.0.1",
                        "255.255.255.255",
                        "::",
                        "::1",
                        "fc00::1",
                        "fe80::1",
                        "ff02::1",
                        "::ffff:127.0.0.1",
                        "64:ff9b::7f00:1",
                        "2001::1",
                        "2001:db8::1",
                        "2002:7f00:1::",
                        "3fff::1")) assertFalse(PublicDns.isPublic(InetAddress.getByName(ip)), ip);
        for (String ip :
                List.of(
                        "1.1.1.1",
                        "8.8.8.8",
                        "104.16.1.1",
                        "2606:4700:4700::1111",
                        "2001:4860:4860::8888"))
            assertTrue(PublicDns.isPublic(InetAddress.getByName(ip)), ip);
    }

    @Test
    void onePrivateDnsAnswerRejectsTheEntireLookup() throws Exception {
        assertThrows(
                UnknownHostException.class,
                () ->
                        PublicDns.checked(
                                new InetAddress[] {
                                    InetAddress.getByName("1.1.1.1"),
                                    InetAddress.getByName("127.0.0.1")
                                }));
        assertThrows(UnknownHostException.class, () -> PublicDns.checked(new InetAddress[0]));
        assertThrows(UnknownHostException.class, () -> new PublicDns(deadline()).resolve("localhost"));
    }

    @Test
    void stalledResolverIsBoundedByTheDownloadDeadline() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        // Like the OS resolver, a stalled lookup ignores interruption.
        PublicDns.Lookup stalled =
                host -> {
                    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                    boolean interrupted = false;
                    while (release.getCount() > 0 && System.nanoTime() < end) {
                        try {
                            release.await(end - System.nanoTime(), java.util.concurrent.TimeUnit.NANOSECONDS);
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                    return new InetAddress[] {InetAddress.getByName("1.1.1.1")};
                };
        try {
            long start = System.nanoTime();
            var deadline = start + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200);
            var timeout =
                    assertThrows(
                            UnknownHostException.class,
                            () -> new PublicDns(deadline, stalled).resolve("i.imgur.com"));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals("Media host lookup timed out", timeout.getMessage());
            assertTrue(elapsedMs < 2_000, "lookup outlived its deadline: " + elapsedMs + " ms");
            // While stalled lookups hold the resolver threads, new lookups fail instead of queueing
            // without bound.
            UnknownHostException last = null;
            for (int i = 0; i < 8; i++) {
                long shortDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50);
                last =
                        assertThrows(
                                UnknownHostException.class,
                                () -> new PublicDns(shortDeadline, stalled).resolve("i.imgur.com"));
            }
            assertEquals("Media host lookup unavailable", last.getMessage());
        } finally {
            release.countDown();
        }
        assertArrayEquals(
                new InetAddress[] {InetAddress.getByName("1.1.1.1")},
                new PublicDns(deadline(), host -> new InetAddress[] {InetAddress.getByName("1.1.1.1")})
                        .resolve("i.imgur.com"));
    }

    @Test
    void hostileUrlFormsAreRejectedBeforeDownloading() {
        for (String url :
                List.of(
                        "file:///secret.png",
                        "http://cdn.discordapp.com/x.gif",
                        "https://127.0.0.1/x.gif",
                        "https://cdn.discordapp.com.attacker.test/x.gif",
                        "https://user@cdn.discordapp.com/x.gif",
                        "https://cdn.discordapp.com:8080/x.gif",
                        "https://cdn.discordapp.com/x%0d%0a.gif",
                        "https://cdn.discordapp.com/x.gif?x=%00",
                        "https://cdn.discordapp.com/x.gif#fragment",
                        "https://cdn.discordapp.com/" + "x".repeat(4100) + ".gif"))
            assertNull(MediaLinks.imageUri(url), url);
        assertFalse(MediaLinks.allowed(null));
        assertNull(MediaLinks.imageUri(null));
    }

    @Test
    void blockedInitialAndRedirectDestinationsAreNeverRequested() {
        var calls = new AtomicInteger();
        MediaDownload.Exchange exchange =
                (uri, limit, end) -> {
                    calls.incrementAndGet();
                    return new MediaDownload.Reply(302, "http://127.0.0.1/private", new byte[0]);
                };
        assertThrows(
                IOException.class,
                () ->
                        MediaDownload.fetch(
                                URI.create("https://localhost/x.gif"), exchange, deadline()));
        assertEquals(0, calls.get());
        assertThrows(
                IOException.class,
                () ->
                        MediaDownload.fetch(
                                URI.create("https://cdn.discordapp.com/x.gif"),
                                exchange,
                                deadline()));
        assertEquals(1, calls.get());
    }

    @Test
    void redirectLoopsHaveAFixedRequestBudget() {
        var calls = new AtomicInteger();
        assertThrows(
                IOException.class,
                () ->
                        MediaDownload.fetch(
                                URI.create("https://cdn.discordapp.com/x.gif"),
                                (uri, limit, end) -> {
                                    calls.incrementAndGet();
                                    return new MediaDownload.Reply(302, "/again.gif", new byte[0]);
                                },
                                deadline()));
        assertEquals(4, calls.get());
    }

    @Test
    void expiredDownloadsDoNotStartAndBodiesAreBounded() throws Exception {
        assertThrows(
                IOException.class,
                () ->
                        MediaDownload.fetch(
                                URI.create("https://cdn.discordapp.com/x.gif"),
                                (uri, limit, end) -> {
                                    fail("Request started after deadline");
                                    return null;
                                },
                                System.nanoTime() - 1));
        assertArrayEquals(
                new byte[32],
                MediaDownload.readLimited(new ByteArrayInputStream(new byte[32]), 32, deadline()));
        assertThrows(
                IOException.class,
                () ->
                        MediaDownload.readLimited(
                                new ByteArrayInputStream(new byte[33]), 32, deadline()));
        assertThrows(
                IOException.class,
                () ->
                        MediaDownload.readLimited(
                                new ByteArrayInputStream(new byte[1]), 32, System.nanoTime() - 1));
    }

    @Test
    void misleadingFilesAndUnsupportedFormatsNeverReachAReader() throws Exception {
        for (String text :
                List.of(
                        "<svg onload='attack()'/>",
                        "<html>not a GIF</html>",
                        "GIF90a fake",
                        "PK fake executable"))
            assertThrows(
                    IOException.class,
                    () ->
                            ImageDecoder.decode(
                                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", output);
        assertThrows(IOException.class, () -> ImageDecoder.decode(output.toByteArray()));
        assertThrows(
                IOException.class, () -> MediaFormat.identify(new byte[MediaFormat.MAX_BYTES + 1]));
    }

    @Test
    void unexpectedMp4FromAnImageHostIsRejected() throws Exception {
        byte[] bytes;
        try (var stream = getClass().getResourceAsStream("/klipy-report.mp4")) {
            bytes = stream.readAllBytes();
        }
        assertThrows(
                IOException.class,
                () ->
                        MediaDownload.fetch(
                                URI.create("https://cdn.discordapp.com/x.gif"),
                                (uri, limit, end) -> new MediaDownload.Reply(200, null, bytes),
                                deadline()));
    }

    @Test
    void workerRejectsMalformedMediaAndReturnsNoInternalDetails() throws Exception {
        var failure =
                assertThrows(
                        IOException.class,
                        () ->
                                IsolatedDecoder.decode(
                                        new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}));
        assertEquals("Image could not be decoded within safety limits", failure.getMessage());
    }

    @Test
    void invalidWorkerDimensionsAndExtraOutputAreRejectedBeforeAllocation() throws Exception {
        var budget = new ByteBudget(64L * 1024 * 1024);
        var header = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(header)) {
            out.writeInt(0x53494D31);
            out.writeInt(Integer.MAX_VALUE);
            out.writeInt(Integer.MAX_VALUE);
            out.writeInt(400);
        }
        try (var lease = budget.open()) {
            assertThrows(
                    IOException.class,
                    () -> DecodedFrames.read(new ByteArrayInputStream(header.toByteArray()), lease));
            assertEquals(0, budget.used(), "Nothing may be reserved for an invalid header");
        }
        var valid = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(valid)) {
            for (int value : new int[] {0x53494D31, 1, 1, 1, 20, 0xff123456}) out.writeInt(value);
            out.write(0);
        }
        try (var lease = budget.open()) {
            assertThrows(
                    IOException.class,
                    () -> DecodedFrames.read(new ByteArrayInputStream(valid.toByteArray()), lease));
        }
        assertEquals(0, budget.used());
    }

    public static final class WaitingWorker {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), "ready");
            Thread.sleep(30000);
        }
    }

    @Test
    void stalledWorkerIsKilledInsteadOfHoldingAMinecraftThreadForever() throws Exception {
        String java =
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name").startsWith("Windows")
                                        ? "java.exe"
                                        : "java")
                        .toString();
        Path marker = temporary.resolve("ready");
        String classes =
                Path.of(
                                SecurityTest.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .toString();
        Process process =
                new ProcessBuilder(
                                java,
                                "-cp",
                                classes,
                                WaitingWorker.class.getName(),
                                marker.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        try {
            long end = System.nanoTime() + 5_000_000_000L;
            while (!Files.exists(marker) && process.isAlive() && System.nanoTime() < end)
                Thread.sleep(10);
            assertTrue(Files.exists(marker), "Worker did not start");
            var error = assertThrows(IOException.class, () -> IsolatedDecoder.await(process, 1));
            assertEquals("Image decoding exceeded the safety time limit", error.getMessage());
            assertFalse(process.isAlive());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void frameProtocolRejectsTruncationTimingAndPixelBombs() throws Exception {
        for (int[] values :
                new int[][] {
                    {0x53494d31, 1, 1, 1, 0, 0},
                    {0x53494d31, 1, 1, 1, 20},
                    {0x53494d31, 256, 256, 400}
                }) {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            for (int v : values) out.writeInt(v);
            try (var lease = new ByteBudget(64L * 1024 * 1024).open()) {
                assertThrows(
                        IOException.class,
                        () ->
                                DecodedFrames.read(
                                        new ByteArrayInputStream(bytes.toByteArray()), lease));
            }
        }
    }

    @Test
    void pngPixelsAndAlphaSurviveSandboxExactly() throws Exception {
        var picture = new BufferedImage(17, 19, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 19; y++)
            for (int x = 0; x < 17; x++)
                picture.setRGB(
                        x,
                        y,
                        ((x % 3 == 0 ? 0 : x % 3 == 1 ? 127 : 255) << 24)
                                | (x * 13 << 16)
                                | (y * 11 << 8)
                                | 19);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(picture, "png", bytes));
        var actual = ImageDecoder.decode(bytes.toByteArray());
        assertEquals(17, actual.width());
        assertEquals(19, actual.height());
        assertEquals(1, actual.frames().size());
        assertArrayEquals(
                picture.getRGB(0, 0, 17, 19, null, 0, 17), actual.frames().getFirst().argb());
    }

    @Test
    void jpegDecodesInsideSandbox() throws Exception {
        var picture = new BufferedImage(17, 19, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 19; y++) for (int x = 0; x < 17; x++) picture.setRGB(x, y, 0xffe01020);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(picture, "jpeg", bytes));
        var actual = ImageDecoder.decode(bytes.toByteArray());
        assertEquals(17, actual.width());
        assertEquals(19, actual.height());
        assertEquals(1, actual.frames().size());
        int pixel = actual.frames().getFirst().argb()[0];
        assertEquals(255, pixel >>> 24);
        assertTrue(((pixel >>> 16) & 255) > 200);
        assertTrue(((pixel >>> 8) & 255) < 40);
    }
}
