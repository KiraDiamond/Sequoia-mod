package com.seqwawa.seq.images;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

final class IsolatedDecoder {
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "Sequoia Images decoder watchdog");
                        t.setDaemon(true);
                        return t;
                    });
    private static Path extractionRoot;

    private record BundledResource(String hash, int size) {}

    private static final Map<String, BundledResource> BUNDLED = new HashMap<>();

    private IsolatedDecoder() {}

    // Prime helper extraction and decoder startup before the first preview.
    // Use a bundled 1px PNG so warm-up makes no network request.
    static void prepare() throws IOException {
        decode(
                Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z/AfAAQAAf8iCjrwAAAAAElFTkSuQmCC"));
    }

    static ImageDecoder.Animation decode(byte[] bytes) throws IOException {
        return decode(bytes, new MediaCancellation());
    }

    static ImageDecoder.Animation decode(byte[] bytes, MediaCancellation cancellation)
            throws IOException {
        cancellation.check();
        MediaFormat.identify(bytes);
        String platform = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
        boolean windows = platform.startsWith("windows-");
        Path helper = resource(platform, windows ? "seqimages-decoder.exe" : "seqimages-decoder");
        Path broker = windows ? resource(platform, "sandbox.exe") : null;
        Path scratch = Files.createTempDirectory("seqimages-pipes-").toRealPath();
        Process process = null;
        ScheduledFuture<?> timeout = null;
        try {
            var command =
                    windows
                            ? List.of(
                                    broker.toString(),
                                    "20",
                                    scratch.toString(),
                                    "1",
                                    helper.toString(),
                                    helper.toString())
                            : List.of(helper.toString());
            var builder =
                    new ProcessBuilder(command)
                            .directory(scratch.toFile())
                            .redirectError(ProcessBuilder.Redirect.DISCARD);
            // Don't pass launcher credentials or JVM injection options to the helper.
            var environment = builder.environment();
            String systemRoot = environment.get("SystemRoot"),
                    local = environment.get("LOCALAPPDATA");
            environment.clear();
            if (systemRoot != null) environment.put("SystemRoot", systemRoot);
            if (local != null) environment.put("LOCALAPPDATA", local);
            cancellation.check();
            process = builder.start();
            Process running = process;
            cancellation.attach(running::destroyForcibly);
            timeout = WATCHDOG.schedule(() -> running.destroyForcibly(), 22, TimeUnit.SECONDS);
            try (var out =
                    new DataOutputStream(new BufferedOutputStream(process.getOutputStream()))) {
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            ImageDecoder.Animation animation;
            try (var input = process.getInputStream()) {
                animation = DecodedFrames.read(input);
            }
            cancellation.check();
            await(process, 2);
            return animation;
        } catch (IOException failure) {
            throw new IOException("Image could not be decoded within safety limits");
        } finally {
            cancellation.detach();
            if (timeout != null) timeout.cancel(false);
            if (process != null && process.isAlive()) {
                // On Windows, killing the broker also kills the decoder through its Job Object.
                process.destroyForcibly();
                try {
                    process.waitFor(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Files.deleteIfExists(scratch);
        }
    }

    static String platform(String os, String arch) throws IOException {
        String system =
                os.startsWith("Windows")
                        ? "windows"
                        : os.equals("Linux") ? "linux" : os.equals("Mac OS X") ? "macos" : null;
        String cpu =
                switch (arch.toLowerCase(Locale.ROOT)) {
                    case "amd64", "x86_64" -> "x86_64";
                    case "aarch64", "arm64" -> "aarch64";
                    default -> null;
                };
        if (system == null || cpu == null)
            throw new IOException(
                    "Unsupported OS or CPU; use Windows, Linux or macOS on x86-64 or ARM64");
        return system + "-" + cpu;
    }

    static void await(Process process, int seconds) throws IOException {
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS))
                throw new IOException("Image decoding exceeded the safety time limit");
            if (process.exitValue() != 0)
                throw new IOException("Image could not be decoded within safety limits");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Image decoding cancelled");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static synchronized Path resource(String platform, String name) throws IOException {
        if (!Set.of("seqimages-decoder.exe", "seqimages-decoder", "sandbox.exe").contains(name))
            throw new IOException("Unknown helper");
        String key = platform + "/" + name;
        BundledResource bundled = BUNDLED.get(key);
        byte[] bytes = null;
        if (bundled == null) {
            bytes = bundledBytes(key);
            bundled = new BundledResource(hash(bytes), bytes.length);
            BUNDLED.put(key, bundled);
        }
        String hash = bundled.hash();
        if (extractionRoot == null) {
            extractionRoot =
                    (System.getProperty("os.name").startsWith("Windows")
                                    ? Files.createTempDirectory("seqimages-sandbox-")
                                    : Files.createTempDirectory(
                                            "seqimages-sandbox-",
                                            java.nio.file.attribute.PosixFilePermissions
                                                    .asFileAttribute(
                                                            java.nio.file.attribute
                                                                    .PosixFilePermissions
                                                                    .fromString("rwx------"))))
                            .toRealPath();
            extractionRoot.toFile().deleteOnExit();
        }
        Path root = extractionRoot;
        if (Files.isSymbolicLink(root)) throw new IOException("Unsafe sandbox path");
        Files.createDirectories(root);
        Path directory = root.resolve(hash);
        if (Files.isSymbolicLink(directory)) throw new IOException("Unsafe sandbox path");
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        if (Files.isSymbolicLink(target)) throw new IOException("Unsafe sandbox path");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (bytes == null) bytes = bundledBytes(key);
            if (bytes.length != bundled.size() || !hash(bytes).equals(hash))
                throw new IOException("Bundled sandbox changed");
            Path temporary = Files.createTempFile(directory, "helper-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (FileAlreadyExistsException raced) {
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        // The cached hash comes from the JAR; check the extracted file on every launch.
        if (Files.size(target) != bundled.size() || !hash(target).equals(hash))
            throw new IOException("Sandbox verification failed");
        if (!System.getProperty("os.name").startsWith("Windows"))
            Files.setPosixFilePermissions(
                    target,
                    Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        directory.toFile().deleteOnExit();
        target.toFile().deleteOnExit();
        return target;
    }

    private static byte[] bundledBytes(String key) throws IOException {
        try (var input = IsolatedDecoder.class.getResourceAsStream("/sandbox/" + key)) {
            if (input == null) throw new IOException("Missing bundled sandbox");
            byte[] bytes = input.readNBytes(32 * 1024 * 1024 + 1);
            if (bytes.length > 32 * 1024 * 1024) throw new IOException("Invalid sandbox resource");
            return bytes;
        }
    }

    private static String hash(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read, total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 32 * 1024 * 1024) throw new IOException("Invalid sandbox resource");
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String hash(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
