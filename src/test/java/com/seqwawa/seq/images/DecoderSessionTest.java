package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Real helper processes: every way a session ends must leave no live process behind. */
class DecoderSessionTest {
    private static Process process(IsolatedDecoder.Session session) throws Exception {
        var field = IsolatedDecoder.Session.class.getDeclaredField("process");
        field.setAccessible(true);
        return (Process) field.get(session);
    }

    @Test
    void cancellingAWaitingHelperKillsAndReapsIt() throws Exception {
        var cancellation = new MediaCancellation();
        var session = IsolatedDecoder.start(cancellation);
        var process = process(session);
        assertTrue(process.isAlive(), "the helper waits for input");
        cancellation.cancel();
        session.close();
        assertFalse(process.isAlive());
    }

    @Test
    void closingAnUnusedSessionReapsTheHelper() throws Exception {
        var session = IsolatedDecoder.start(new MediaCancellation());
        var process = process(session);
        session.close();
        assertFalse(process.isAlive());
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
            // The near-limit clip takes well over 50 ms to decode.
            timer.schedule(cancellation::cancel, 50, TimeUnit.MILLISECONDS);
            assertThrows(InterruptedIOException.class, () -> session.decode(media, lease));
            session.close();
            assertFalse(process.isAlive());
        } finally {
            timer.shutdownNow();
        }
    }
}
