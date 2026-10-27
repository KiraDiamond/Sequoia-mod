package com.seqwawa.seq.network;

import com.seqwawa.seq.network.auth.MinecraftSessionProof;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Proves ownership of the active Minecraft session without exposing its access token. */
final class TreasurySessionAuthenticator {
    private static final Pattern NONCE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{32}$");

    private final MinecraftSessionProof.SessionJoiner sessionJoiner;
    private final String endpoint;
    private final Executor executor;
    private final Consumer<TreasuryAuthResponse> responseSender;
    private final Consumer<Throwable> failureHandler;

    private State state = State.WAITING_CHALLENGE;
    private String nonce;
    private String clientNonce;
    private long generation;

    TreasurySessionAuthenticator(
            MinecraftSessionProof.SessionJoiner sessionJoiner,
            Executor executor,
            Consumer<TreasuryAuthResponse> responseSender,
            Consumer<Throwable> failureHandler,
            String endpoint) {
        this.sessionJoiner = Objects.requireNonNull(sessionJoiner);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.executor = Objects.requireNonNull(executor);
        this.responseSender = Objects.requireNonNull(responseSender);
        this.failureHandler = Objects.requireNonNull(failureHandler);
    }

    boolean handleChallenge(String challengeNonce, int proofVersion) {
        long startedFor;
        IllegalArgumentException invalidNonceFailure = null;
        synchronized (this) {
            if (state != State.WAITING_CHALLENGE) {
                return false;
            }
            startedFor = generation;
            if (proofVersion != MinecraftSessionProof.VERSION || !isValidNonce(challengeNonce)) {
                state = State.FAILED;
                nonce = null;
                invalidNonceFailure = new IllegalArgumentException(
                        "Backend supplied an invalid Treasury authentication nonce.");
            } else {
                nonce = challengeNonce;
                state = State.JOINING_SESSION;
            }
        }

        if (invalidNonceFailure != null) {
            failureHandler.accept(invalidNonceFailure);
            return false;
        }

        CompletableFuture.supplyAsync(() -> {
                    synchronized (this) {
                        if (startedFor != generation || state != State.JOINING_SESSION) {
                            throw new java.util.concurrent.CancellationException("Authentication was reset.");
                        }
                    }
                    try {
                        return MinecraftSessionProof.treasury(endpoint, challengeNonce, sessionJoiner);
                    } catch (Exception exception) {
                        throw new CompletionException(exception);
                    }
                }, executor)
                .whenComplete((proof, error) -> completeJoin(startedFor, challengeNonce, proof, error));
        return true;
    }

    synchronized boolean confirm(String confirmedNonce, String confirmedClientNonce) {
        if (state != State.WAITING_BACKEND || !Objects.equals(nonce, confirmedNonce)
                || !Objects.equals(clientNonce, confirmedClientNonce)) {
            return false;
        }
        state = State.AUTHENTICATED;
        nonce = null;
        clientNonce = null;
        return true;
    }

    synchronized boolean isAuthenticated() {
        return state == State.AUTHENTICATED;
    }

    synchronized State state() {
        return state;
    }

    synchronized void reset() {
        generation++;
        state = State.WAITING_CHALLENGE;
        nonce = null;
        clientNonce = null;
    }

    static boolean isValidNonce(String nonce) {
        return nonce != null && NONCE_PATTERN.matcher(nonce).matches();
    }

    private void completeJoin(long startedFor, String completedNonce, MinecraftSessionProof.Joined proof, Throwable error) {
        Throwable failure = unwrap(error);
        synchronized (this) {
            if (startedFor != generation || state != State.JOINING_SESSION || !Objects.equals(nonce, completedNonce)) {
                return;
            }
            if (failure != null) {
                state = State.FAILED;
            } else {
                clientNonce = proof.clientNonce();
                state = State.WAITING_BACKEND;
            }
        }

        if (failure != null) {
            failureHandler.accept(failure);
        } else {
            responseSender.accept(new TreasuryAuthResponse(completedNonce, proof.clientNonce()));
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    enum State {
        WAITING_CHALLENGE,
        JOINING_SESSION,
        WAITING_BACKEND,
        AUTHENTICATED,
        FAILED
    }
}
