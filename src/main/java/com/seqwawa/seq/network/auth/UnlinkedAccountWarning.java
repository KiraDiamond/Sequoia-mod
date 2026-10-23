package com.seqwawa.seq.network.auth;

import java.util.Objects;
import java.util.UUID;

/**
 * The player's choice to hide the not-linked sign-in warning for the rest of this server
 * session. It is kept in memory only and is scoped to one Minecraft account on one backend, so
 * it never hides another account's warning, another backend's or any other failure. Sign-in
 * retries continue unchanged.
 */
public final class UnlinkedAccountWarning {

    private static final UnlinkedAccountWarning INSTANCE = new UnlinkedAccountWarning();

    private Scope shown;
    private Scope dismissed;

    UnlinkedAccountWarning() {}

    public static UnlinkedAccountWarning getInstance() {
        return INSTANCE;
    }

    /** Only the not-linked warning can be hidden; every other sign-in failure stays visible. */
    public static boolean isDismissible(AuthErrorCode code) {
        return code == AuthErrorCode.ACCOUNT_NOT_LINKED;
    }

    /**
     * Records that the warning is due for this account and backend, and answers whether to show
     * it. A sign-in the player started always shows its result.
     */
    public synchronized boolean shouldShow(UUID profileId, String backend, boolean playerStarted) {
        Scope scope = new Scope(profileId, backend);
        shown = scope;
        return playerStarted || !scope.equals(dismissed);
    }

    /** Hides the warning last shown for this account; a click from an older session does nothing. */
    public synchronized boolean dismiss(UUID profileId) {
        if (shown == null || !shown.profileId().equals(profileId)) {
            return false;
        }
        dismissed = shown;
        return true;
    }

    /** Ends the session: after a successful sign-in, an account change or leaving the server. */
    public synchronized void reset() {
        shown = null;
        dismissed = null;
    }

    private record Scope(UUID profileId, String backend) {
        private Scope {
            Objects.requireNonNull(profileId);
            Objects.requireNonNull(backend);
        }
    }
}
