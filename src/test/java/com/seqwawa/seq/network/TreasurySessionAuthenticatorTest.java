package com.seqwawa.seq.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TreasurySessionAuthenticatorTest {
    private static final String NONCE = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX";

    @Test
    void neverSignsBackendChosenMinecraftServerHash() {
        String attackerTarget = "0123456789abcdef0123456789abcdef";
        AtomicReference<String> joined = new AtomicReference<>();
        TreasurySessionAuthenticator authenticator = new TreasurySessionAuthenticator(
                joined::set, Runnable::run, ignored -> {}, ignored -> {}, "wss://api.seqwawa.com/ws");

        assertTrue(authenticator.handleChallenge(attackerTarget, 2));

        assertNotNull(joined.get());
        assertTrue(joined.get().matches("[0-9a-f]{40}"));
        assertNotEquals(attackerTarget, joined.get());
    }

    @Test
    void joinsMinecraftSessionThenRequiresCorrelatedBackendConfirmation() {
        AtomicReference<String> joined = new AtomicReference<>();
        AtomicReference<TreasuryAuthResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        TreasurySessionAuthenticator authenticator = new TreasurySessionAuthenticator(
                joined::set, Runnable::run, response::set, failure::set, "wss://api.seqwawa.com/ws");

        assertTrue(authenticator.handleChallenge(NONCE, 2));

        assertNotEquals(NONCE, joined.get());
        assertEquals(NONCE, response.get().nonce());
        assertNull(failure.get());
        assertEquals(TreasurySessionAuthenticator.State.WAITING_BACKEND, authenticator.state());
        assertFalse(authenticator.confirm("different-nonce-0000000000000000", response.get().client_nonce()));
        assertFalse(authenticator.isAuthenticated());
        assertTrue(authenticator.confirm(NONCE, response.get().client_nonce()));
        assertTrue(authenticator.isAuthenticated());
    }

    @Test
    void rejectsInvalidOrRepeatedChallenges() {
        AtomicInteger joinCalls = new AtomicInteger();
        TreasurySessionAuthenticator authenticator = new TreasurySessionAuthenticator(
                ignored -> joinCalls.incrementAndGet(),
                Runnable::run,
                ignored -> {},
                ignored -> {}, "wss://api.seqwawa.com/ws");

        assertFalse(authenticator.handleChallenge("short", 2));
        assertEquals(0, joinCalls.get());

        authenticator.reset();
        assertTrue(authenticator.handleChallenge(NONCE, 2));
        assertFalse(authenticator.handleChallenge("another-valid-nonce-000000000000", 2));
        assertEquals(1, joinCalls.get());
    }

    @Test
    void ignoresMalformedLateChallengeAfterAuthentication() {
        AtomicInteger failureCalls = new AtomicInteger();
        AtomicReference<TreasuryAuthResponse> response = new AtomicReference<>();
        TreasurySessionAuthenticator authenticator = new TreasurySessionAuthenticator(
                ignored -> {}, Runnable::run, response::set, ignored -> failureCalls.incrementAndGet(), "wss://api.seqwawa.com/ws");

        assertTrue(authenticator.handleChallenge(NONCE, 2));
        assertTrue(authenticator.confirm(NONCE, response.get().client_nonce()));

        assertFalse(authenticator.handleChallenge("short", 2));
        assertTrue(authenticator.isAuthenticated());
        assertEquals(TreasurySessionAuthenticator.State.AUTHENTICATED, authenticator.state());
        assertEquals(0, failureCalls.get());
    }

    @Test
    void invalidInitialChallengeInvokesFailureHandlerOutsideMonitor() {
        AtomicReference<TreasurySessionAuthenticator> authenticatorReference = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> handlerHeldMonitor = new AtomicReference<>();
        TreasurySessionAuthenticator authenticator = new TreasurySessionAuthenticator(
                ignored -> {},
                Runnable::run,
                ignored -> {},
                error -> {
                    failure.set(error);
                    handlerHeldMonitor.set(Thread.holdsLock(authenticatorReference.get()));
                }, "wss://api.seqwawa.com/ws");
        authenticatorReference.set(authenticator);

        assertFalse(authenticator.handleChallenge("short", 2));

        assertEquals("Backend supplied an invalid Treasury authentication nonce.", failure.get().getMessage());
        assertFalse(handlerHeldMonitor.get());
        assertEquals(TreasurySessionAuthenticator.State.FAILED, authenticator.state());
    }

    @Test
    void joinFailureNeverSendsAuthenticationResponse() {
        AtomicReference<TreasuryAuthResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        TreasurySessionAuthenticator authenticator = new TreasurySessionAuthenticator(
                ignored -> {
                    throw new IllegalStateException("invalid access token");
                },
                Runnable::run,
                response::set,
                failure::set, "wss://api.seqwawa.com/ws");

        assertTrue(authenticator.handleChallenge(NONCE, 2));

        assertNull(response.get());
        assertEquals("invalid access token", failure.get().getMessage());
        assertEquals(TreasurySessionAuthenticator.State.FAILED, authenticator.state());
        assertFalse(authenticator.isAuthenticated());
    }

    @Test
    void resetDiscardsAStaleInFlightJoinResult() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicReference<TreasuryAuthResponse> response = new AtomicReference<>();
        Executor delayedExecutor = queued::set;
        TreasurySessionAuthenticator authenticator = new TreasurySessionAuthenticator(
                ignored -> {}, delayedExecutor, response::set, ignored -> {}, "wss://api.seqwawa.com/ws");

        assertTrue(authenticator.handleChallenge(NONCE, 2));
        authenticator.reset();
        queued.get().run();

        assertNull(response.get());
        assertEquals(TreasurySessionAuthenticator.State.WAITING_CHALLENGE, authenticator.state());
        assertFalse(authenticator.isAuthenticated());
    }
    @Test
    void refusesLegacyBackendBeforeJoining() {
        AtomicInteger joins = new AtomicInteger();
        TreasurySessionAuthenticator auth = new TreasurySessionAuthenticator(
                ignored -> joins.incrementAndGet(), Runnable::run, ignored -> {}, ignored -> {},
                "wss://api.seqwawa.com/ws");
        assertFalse(auth.handleChallenge(NONCE, 0));
        assertEquals(0, joins.get());
    }

    @Test
    void repeatedServerSeedAfterResetCannotReuseOldJoinOrConfirmation() {
        java.util.List<Runnable> queue = new java.util.ArrayList<>();
        java.util.List<TreasuryAuthResponse> responses = new java.util.ArrayList<>();
        AtomicInteger joins = new AtomicInteger();
        TreasurySessionAuthenticator auth = new TreasurySessionAuthenticator(
                ignored -> joins.incrementAndGet(), queue::add, responses::add, ignored -> {},
                "wss://api.seqwawa.com/ws");
        assertTrue(auth.handleChallenge(NONCE, 2));
        auth.reset();
        assertTrue(auth.handleChallenge(NONCE, 2));
        queue.get(0).run();
        assertEquals(0, joins.get());
        assertTrue(responses.isEmpty());
        queue.get(1).run();
        assertEquals(1, joins.get());
        assertFalse(auth.confirm(NONCE, "old-client-nonce"));
        assertTrue(auth.confirm(NONCE, responses.getFirst().client_nonce()));
    }

}
