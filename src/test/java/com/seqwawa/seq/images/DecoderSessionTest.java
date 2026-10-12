package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Real helper processes: every way a session ends must leave no live process behind. */
class DecoderSessionTest {
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
    private static void assertStopped(Process process, List<ProcessHandle> started)
            throws Exception {
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "cancelling did not stop the helper");
        for (var child : started) child.onExit().get(10, TimeUnit.SECONDS);
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
        try (var stream = getClass().getResourceAsStream("/noise-183.mp4")) {
            media = stream.readAllBytes();
        }
        var cancellation = new MediaCancellation();
        var timer = Executors.newSingleThreadScheduledExecutor();
        try (var lease = new ByteBudget(256L * 1024 * 1024).open()) {
            var session = IsolatedDecoder.start(cancellation);
            var process = process(session);
            var started = started(process);
            // The near-limit clip takes well over 50 ms to decode.
            timer.schedule(cancellation::cancel, 50, TimeUnit.MILLISECONDS);
            assertThrows(InterruptedIOException.class, () -> session.decode(media, lease));
            assertStopped(process, started);
            session.close();
        } finally {
            timer.shutdownNow();
        }
    }
}
