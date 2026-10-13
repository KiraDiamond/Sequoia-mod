package com.seqwawa.seq.images;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Runs the bundled decoder helper, sandboxed by the OS, one process per decode. */
final class IsolatedDecoder {
    /** The helper's own Wasm deadline is 15 s; this also covers pixel transfer. */
    private static final long DECODE_LIMIT_SECONDS = 22;
    /** Upper bound on one session, including the download the helper waits through. */
    private static final int BROKER_LIMIT_SECONDS = 60;

    private static final int EXIT_GRACE_SECONDS = 2;
    /** Time the Windows broker gets to stop the decoder and remove its AppContainer state. */
    private static final long BROKER_CLEANUP_MILLIS = 5_000;

    private static final long REAP_MILLIS = 3_000;
    private static final int DIAGNOSTIC_BYTES = 2_048;
    private static final int SCRATCH_ATTEMPTS = 20;
    private static final long SCRATCH_RETRY_MILLIS = 50;
    /** File the Windows broker watches for in its scratch directory to cancel cleanly. */
    static final String CANCEL_FILE = "cancel";

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Sequoia Images decoder watchdog");
        t.setDaemon(true);
        return t;
    });
    // Warm-up and tests only; preview loads charge the shared in-flight budget instead.
    private static final ByteBudget STANDALONE = new ByteBudget(128L * 1024 * 1024);
    private static final byte[] WARM_UP_PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z/AfAAQAAf8iCjrwAAAAAElFTkSuQmCC");
    private static Path extractionRoot;
    // File key and owner of extractionRoot when it was created; another directory at that path is not it.
    // Windows reports no file key, but there the root is in the user's own temp directory.
    private static List<String> extractionRootIdentity;

    private record BundledResource(String hash, int size) {}

    private static final Map<String, BundledResource> BUNDLED = new HashMap<>();

    private IsolatedDecoder() {}

    /** Extracts and verifies the helper and pages it in, without any network request. */
    static void prepare() throws IOException {
        decode(WARM_UP_PNG);
    }

    static MediaFrames decode(byte[] bytes) throws IOException {
        var cancellation = new MediaCancellation();
        try (var lease = STANDALONE.open();
                var session = start(cancellation)) {
            return session.decode(bytes, lease, frames -> {});
        }
    }

    /** Starts a helper so its runtime setup overlaps with the caller's download. */
    static Session start(MediaCancellation cancellation) throws IOException {
        cancellation.check();
        String platform = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
        boolean windows = platform.startsWith("windows-");
        Path helper = resource(platform, windows ? "seqimages-decoder.exe" : "seqimages-decoder");
        Path broker = windows ? resource(platform, "sandbox.exe") : null;
        Path scratch = Files.createTempDirectory("seqimages-pipes-").toRealPath();
        try {
            var command = windows
                    ? List.of(
                            broker.toString(),
                            Integer.toString(BROKER_LIMIT_SECONDS),
                            scratch.toString(),
                            "1",
                            helper.toString(),
                            helper.toString())
                    : List.of(helper.toString());
            var builder = new ProcessBuilder(command).directory(scratch.toFile());
            // Don't pass launcher credentials or JVM injection options to the helper.
            var environment = builder.environment();
            String systemRoot = environment.get("SystemRoot"), local = environment.get("LOCALAPPDATA");
            environment.clear();
            if (systemRoot != null) environment.put("SystemRoot", systemRoot);
            if (local != null) environment.put("LOCALAPPDATA", local);
            cancellation.check();
            return new Session(builder.start(), scratch, windows, cancellation);
        } catch (IOException | RuntimeException failure) {
            deleteScratch(scratch);
            throw failure;
        }
    }

    /** One helper process. Closing it always terminates and reaps it and what it started. */
    static final class Session implements AutoCloseable {
        private final Process process;
        private final Path scratch;
        private final boolean windows;
        private final MediaCancellation cancellation;
        private final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        private final Thread diagnosticsReader;
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final AtomicBoolean aborting = new AtomicBoolean();
        // What a Windows broker had started when an abort killed it; null otherwise (a Unix
        // helper starts nothing). close() reaps these along with the helper.
        private volatile List<ProcessHandle> abortKilledDescendants;
        private MediaCancellation.Registration abortRegistration;
        private boolean closed;

        private Session(Process process, Path scratch, boolean windows, MediaCancellation cancellation)
                throws IOException {
            this.process = process;
            this.scratch = scratch;
            this.windows = windows;
            this.cancellation = cancellation;
            diagnosticsReader = Thread.ofVirtual()
                    .name("Sequoia Images decoder diagnostics")
                    .start(() -> retainPrefix(process.getErrorStream(), diagnostics));
            try {
                abortRegistration = cancellation.attach(this::abort);
            } catch (IOException cancelled) {
                close();
                throw cancelled;
            }
        }

        /** Sends media to the helper and streams its validated frames, publishing the first one early. */
        MediaFrames decode(byte[] media, ByteBudget.Lease lease, Consumer<MediaFrames> firstFrame) throws IOException {
            ScheduledFuture<?> timeout = WATCHDOG.schedule(
                    () -> {
                        timedOut.set(true);
                        abort();
                    },
                    DECODE_LIMIT_SECONDS,
                    TimeUnit.SECONDS);
            try {
                cancellation.check();
                MediaFormat.identify(media);
                try (var out = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()))) {
                    out.writeInt(media.length);
                    out.write(media);
                }
                MediaFrames frames;
                try (InputStream input = process.getInputStream()) {
                    frames = DecodedFrames.read(input, lease, firstFrame);
                }
                await(process, EXIT_GRACE_SECONDS);
                cancellation.check();
                return frames;
            } catch (IOException failure) {
                throw explain(failure);
            } finally {
                timeout.cancel(false);
            }
        }

        private IOException explain(IOException failure) {
            if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
                if (failure instanceof InterruptedIOException interrupted) return interrupted;
                var cancelled = new InterruptedIOException("Image preview cancelled");
                cancelled.initCause(failure);
                return cancelled;
            }
            if (failure instanceof ByteBudget.Exhausted) return failure;
            var explained = new IOException(
                    timedOut.get()
                            ? "Image decoding exceeded the safety time limit"
                            : "Image could not be decoded within safety limits",
                    failure);
            // Stop the helper before reading its diagnostics so the text is complete.
            try {
                terminate();
            } catch (IOException cleanup) {
                explained.addSuppressed(cleanup);
            }
            // Helper stderr is kept for logs only; it never contains URLs or credentials.
            String detail = diagnosticText();
            if (!detail.isEmpty()) explained.addSuppressed(new IOException("Helper reported: " + detail));
            return explained;
        }

        /** Requests cancellation without blocking the caller, escalating to a hard kill. */
        private void abort() {
            if (!process.isAlive() || !aborting.compareAndSet(false, true)) return;
            if (!windows) {
                // A Unix helper starts no processes; skip the process-tree scan on this thread.
                process.destroyForcibly();
                return;
            }
            // The kill scans the process tree; keep that off the cancelling thread.
            long grace = requestBrokerCancel() ? BROKER_CLEANUP_MILLIS : 0;
            WATCHDOG.schedule(
                    () -> {
                        if (process.isAlive()) killForAbort();
                    },
                    grace,
                    TimeUnit.MILLISECONDS);
        }

        /** Kills the helper and what it started without waiting; close() reaps them. */
        private void killForAbort() {
            List<ProcessHandle> started = descendants(process);
            // Published before the kill, so terminate() never sees a dead helper without it.
            abortKilledDescendants = started;
            destroy(process, started);
        }

        /** @return false if the request could not be made and the helper must be killed */
        private boolean requestBrokerCancel() {
            try {
                Files.createFile(scratch.resolve(CANCEL_FILE));
            } catch (FileAlreadyExistsException alreadyRequested) {
                // A timeout and a cancellation can both ask; one request is enough.
            } catch (IOException unavailable) {
                return false;
            }
            return true;
        }

        /**
         * Stops the helper and waits for it to exit. On Windows the broker first gets time to
         * revoke its file grants and delete its AppContainer profile; if it is killed instead,
         * the next broker removes the leftover state from its lease record.
         */
        private void terminate() throws IOException {
            if (process.isAlive()) {
                boolean exitedAfterCancel = windows
                        && requestBrokerCancel()
                        && waitUninterruptibly(
                                process.onExit(),
                                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BROKER_CLEANUP_MILLIS));
                if (!exitedAfterCancel) {
                    killAndReap(process);
                    return;
                }
            }
            // An abort may have killed it without waiting for what it started.
            var started = abortKilledDescendants;
            if (started != null) reap(process, started);
        }

        private String diagnosticText() {
            try {
                diagnosticsReader.join(REAP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            synchronized (diagnostics) {
                return diagnostics.toString(StandardCharsets.UTF_8).strip();
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            if (abortRegistration != null) abortRegistration.close();
            try {
                terminate();
            } finally {
                // A session closed before decode() never closed the pipes; Windows keeps them open until GC.
                closeQuietly(process.getOutputStream());
                closeQuietly(process.getInputStream());
                deleteScratch(scratch);
            }
        }
    }

    private static void closeQuietly(Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The helper is already gone.
        }
    }

    private static void retainPrefix(InputStream stream, ByteArrayOutputStream target) {
        byte[] buffer = new byte[512];
        try (stream) {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                synchronized (target) {
                    target.write(buffer, 0, Math.min(read, DIAGNOSTIC_BYTES - target.size()));
                }
            }
        } catch (IOException closed) {
            // The process exited or was killed; whatever arrived is enough for diagnostics.
        }
    }

    /** Waits for a worker to exit successfully; the caller stops it if this throws. */
    static void await(Process process, int seconds) throws IOException {
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS))
                throw new IOException("Image decoding exceeded the safety time limit");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Image decoding cancelled");
        }
        if (process.exitValue() != 0) throw new IOException("Image could not be decoded within safety limits");
    }

    /**
     * Kills a worker and waits for it and every process it started. On Windows the broker's
     * AppContainer decoder ends through its job object only after the broker itself.
     */
    private static void killAndReap(Process process) throws IOException {
        List<ProcessHandle> started = descendants(process);
        destroy(process, started);
        reap(process, started);
    }

    /** Processes the worker started; empty if the OS cannot list them, so a kill still runs. */
    private static List<ProcessHandle> descendants(Process process) {
        try {
            return process.descendants().toList();
        } catch (RuntimeException unavailable) {
            // A Windows decoder still ends with its broker through the job object.
            return List.of();
        }
    }

    private static void destroy(Process process, List<ProcessHandle> started) {
        process.destroyForcibly();
        started.forEach(ProcessHandle::destroyForcibly);
    }

    /** Waits, within the reap bound, for a killed worker and the processes it started. */
    private static void reap(Process process, List<ProcessHandle> started) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REAP_MILLIS);
        boolean exited = waitUninterruptibly(process.onExit(), deadline);
        for (var child : started) exited &= waitUninterruptibly(child.onExit(), deadline);
        if (!exited) throw new IOException("Image decoder did not exit after termination");
    }

    private static boolean waitUninterruptibly(CompletableFuture<?> exit, long deadline) {
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                try {
                    exit.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    return true;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (TimeoutException late) {
                    return false;
                } catch (ExecutionException impossible) {
                    return true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Removes the per-session scratch directory. Windows can hold it for a moment after a process
     * exits; an empty directory left behind is removed when the client exits instead.
     */
    private static void deleteScratch(Path scratch) {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.deleteIfExists(scratch.resolve(CANCEL_FILE));
                Files.deleteIfExists(scratch);
                return;
            } catch (IOException busy) {
                if (attempt == SCRATCH_ATTEMPTS || !pause(SCRATCH_RETRY_MILLIS)) {
                    scratch.toFile().deleteOnExit();
                    return;
                }
            }
        }
    }

    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static String platform(String os, String arch) throws IOException {
        String system = os.startsWith("Windows")
                ? "windows"
                : os.equals("Linux") ? "linux" : os.equals("Mac OS X") ? "macos" : null;
        String cpu = switch (arch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> null;
        };
        if (system == null || cpu == null)
            throw new IOException("Unsupported OS or CPU; use Windows, Linux or macOS on x86-64 or ARM64");
        return system + "-" + cpu;
    }

    private static synchronized Path resource(String platform, String name) throws IOException {
        String key = platform + "/" + name;
        BundledResource bundled = BUNDLED.get(key);
        byte[] bytes = null;
        if (bundled == null) {
            bytes = bundledBytes(key);
            bundled = new BundledResource(hash(bytes), bytes.length);
            BUNDLED.put(key, bundled);
        }
        String hash = bundled.hash();
        boolean windows = platform.startsWith("windows-");
        // A private directory made here. If a temp cleaner removed it, another local user may have
        // claimed the path, so anything that is not the same directory is replaced by a new one.
        List<String> current = extractionRoot == null ? List.of() : identity(extractionRoot);
        if (current.isEmpty() || !current.equals(extractionRootIdentity)) {
            extractionRoot = (windows
                            ? Files.createTempDirectory("seqimages-sandbox-")
                            : Files.createTempDirectory(
                                    "seqimages-sandbox-",
                                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))))
                    .toRealPath();
            extractionRootIdentity = identity(extractionRoot);
            extractionRoot.toFile().deleteOnExit();
        }
        Path directory = extractionRoot.resolve(hash);
        if (Files.isSymbolicLink(directory)) throw new IOException("Unsafe sandbox path");
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        if (Files.isSymbolicLink(target)) throw new IOException("Unsafe sandbox path");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (bytes == null) bytes = bundledBytes(key);
            Path temporary = Files.createTempFile(directory, "helper-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (FileAlreadyExistsException raced) {
                    // Another load extracted the same verified bytes first.
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        // The cached hash comes from the JAR; check the extracted file on every launch.
        if (Files.size(target) != bundled.size()
                || !hash(Files.readAllBytes(target)).equals(hash)) throw new IOException("Sandbox verification failed");
        if (!windows)
            Files.setPosixFilePermissions(
                    target,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        directory.toFile().deleteOnExit();
        target.toFile().deleteOnExit();
        return target;
    }

    /** File key and owner of a directory; empty if the path is not a directory. */
    private static List<String> identity(Path directory) {
        try {
            var attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) return List.of();
            String owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).getName();
            return List.of(String.valueOf(attributes.fileKey()), owner);
        } catch (IOException missing) {
            return List.of();
        }
    }

    private static byte[] bundledBytes(String key) throws IOException {
        try (var input = IsolatedDecoder.class.getResourceAsStream("/sandbox/" + key)) {
            if (input == null) throw new IOException("Missing bundled sandbox");
            byte[] bytes = input.readNBytes(32 * 1024 * 1024 + 1);
            if (bytes.length > 32 * 1024 * 1024) throw new IOException("Invalid sandbox resource");
            return bytes;
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
