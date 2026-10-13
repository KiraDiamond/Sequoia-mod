package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Real helper processes: every way a session ends must leave no live process behind. */
class IsolatedDecoderTest {
    @Test
    void selectsTheHelperForEachSupportedPlatform() throws Exception {
        for (String[] pair : new String[][] {
            {"Windows 11", "amd64", "windows-x86_64"},
            {"Windows 11", "aarch64", "windows-aarch64"},
            {"Linux", "x86_64", "linux-x86_64"},
            {"Linux", "arm64", "linux-aarch64"},
            {"Mac OS X", "x86_64", "macos-x86_64"},
            {"Mac OS X", "aarch64", "macos-aarch64"}
        }) assertEquals(pair[2], IsolatedDecoder.platform(pair[0], pair[1]));
        assertThrows(IOException.class, () -> IsolatedDecoder.platform("Linux", "arm"));
        assertThrows(IOException.class, () -> IsolatedDecoder.platform("Other", "amd64"));
    }

    @Test
    void aStalledHelperTimesOut() throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classes = Path.of(Stalled.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        var stalled = new ProcessBuilder(java, "-cp", classes, Stalled.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            var error = assertThrows(IOException.class, () -> IsolatedDecoder.await(stalled, 1));
            assertEquals("Image decoding exceeded the safety time limit", error.getMessage());
        } finally {
            stalled.destroyForcibly();
        }
    }

    public static final class Stalled {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(30_000);
        }
    }

    private static Process process(IsolatedDecoder.Session session) throws Exception {
        var field = IsolatedDecoder.Session.class.getDeclaredField("process");
        field.setAccessible(true);
        return (Process) field.get(session);
    }

    /** Processes the helper started: on Windows, the broker's AppContainer decoder. */
    private static List<ProcessHandle> started(Process process) throws InterruptedException {
        if (!System.getProperty("os.name").startsWith("Windows")) return List.of();
        for (int attempt = 0; attempt < 200; attempt++) {
            var found = process.descendants().toList();
            if (!found.isEmpty()) return found;
            Thread.sleep(50);
        }
        return fail("the broker never started its decoder");
    }

    /** Cancellation alone, before close(), must end the helper and what it started. */
    private static void assertStopped(Process process, List<ProcessHandle> started) throws Exception {
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "cancelling did not stop the helper");
        for (var child : started)
            assertDoesNotThrow(() -> child.onExit().get(10, TimeUnit.SECONDS), "cancelling left the decoder running");
    }

    @Test
    void cancellingAWaitingHelperKillsAndReapsIt() throws Exception {
        var cancellation = new MediaCancellation();
        var session = IsolatedDecoder.start(cancellation);
        var process = process(session);
        var started = started(process);
        assertTrue(process.isAlive(), "the helper waits for input");
        cancellation.cancel();
        assertStopped(process, started);
        session.close();
    }

    @Test
    void closingAnUnusedSessionReapsTheHelper() throws Exception {
        var session = IsolatedDecoder.start(new MediaCancellation());
        var process = process(session);
        var started = started(process);
        session.close();
        assertFalse(process.isAlive());
        for (var child : started) assertFalse(child.isAlive(), "the decoder outlived close()");
    }

    @Test
    void cancellationDuringDecodeReportsInterruptionAndReapsTheHelper() throws Exception {
        byte[] media;
        try (var stream = getClass().getResourceAsStream("/klipy-report.mp4")) {
            media = stream.readAllBytes();
        }
        var cancellation = new MediaCancellation();
        try (var lease = new ByteBudget(256L * 1024 * 1024).open()) {
            var session = IsolatedDecoder.start(cancellation);
            var process = process(session);
            var started = started(process);
            assertThrows(
                    InterruptedIOException.class, () -> session.decode(media, lease, first -> cancellation.cancel()));
            assertStopped(process, started);
            session.close();
        }
    }
}
