package com.seqwawa.seq.network.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnlinkedAccountWarningTest {

    private static final UUID ACCOUNT = UUID.fromString("f1000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("0a000000-0000-0000-0000-000000000001");
    private static final String BACKEND = "https://api.seqwawa.com";
    private static final String OTHER_BACKEND = "https://api.staging.seqwawa.com";

    @Test
    void warningShowsOnEveryAttemptUntilThePlayerDismissesIt() {
        UnlinkedAccountWarning warning = new UnlinkedAccountWarning();

        assertTrue(warning.shouldShow(ACCOUNT, BACKEND, false));
        assertTrue(warning.shouldShow(ACCOUNT, BACKEND, false));

        assertTrue(warning.dismiss(ACCOUNT));
        assertFalse(warning.shouldShow(ACCOUNT, BACKEND, false));
        assertFalse(warning.shouldShow(ACCOUNT, BACKEND, false));
    }

    @Test
    void aSignInThePlayerStartsStillReportsTheResult() {
        UnlinkedAccountWarning warning = new UnlinkedAccountWarning();
        warning.shouldShow(ACCOUNT, BACKEND, false);
        warning.dismiss(ACCOUNT);

        assertTrue(warning.shouldShow(ACCOUNT, BACKEND, true));
        assertFalse(warning.shouldShow(ACCOUNT, BACKEND, false));
    }

    @Test
    void dismissalNeverHidesAnotherAccountOrBackend() {
        UnlinkedAccountWarning warning = new UnlinkedAccountWarning();
        warning.shouldShow(ACCOUNT, BACKEND, false);
        warning.dismiss(ACCOUNT);

        assertTrue(warning.shouldShow(OTHER_ACCOUNT, BACKEND, false));
        assertTrue(warning.shouldShow(ACCOUNT, OTHER_BACKEND, false));
    }

    @Test
    void aStaleDismissClickDoesNothing() {
        UnlinkedAccountWarning warning = new UnlinkedAccountWarning();

        assertFalse(warning.dismiss(ACCOUNT));
        warning.shouldShow(OTHER_ACCOUNT, BACKEND, false);
        assertFalse(warning.dismiss(ACCOUNT));
        assertTrue(warning.shouldShow(OTHER_ACCOUNT, BACKEND, false));
    }

    @Test
    void theNextSessionShowsTheWarningAgain() {
        UnlinkedAccountWarning warning = new UnlinkedAccountWarning();
        warning.shouldShow(ACCOUNT, BACKEND, false);
        warning.dismiss(ACCOUNT);

        warning.reset();

        assertTrue(warning.shouldShow(ACCOUNT, BACKEND, false));
        assertFalse(warning.dismiss(OTHER_ACCOUNT));
    }

    @Test
    void onlyTheNotLinkedFailureCanBeDismissed() {
        for (AuthErrorCode code : AuthErrorCode.values()) {
            if (code == AuthErrorCode.ACCOUNT_NOT_LINKED) {
                assertTrue(UnlinkedAccountWarning.isDismissible(code));
            } else {
                assertFalse(UnlinkedAccountWarning.isDismissible(code), code.code());
            }
        }
    }
}
