package com.seqwawa.seq.network.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftAuthServiceTest {

    @Test
    void validateChallengeAcceptsFreshHexServerId() {
        MinecraftAuthChallengeResponse response = new MinecraftAuthChallengeResponse(
                "123e4567-e89b-12d3-a456-426614174000",
                "0123456789abcdef0123456789abcdef01234567",
                Instant.now().plusSeconds(45), 2);

        assertEquals(response, MinecraftAuthService.validateChallenge(response));
    }

    @Test
    void validateChallengeRejectsMalformedServerId() {
        MinecraftAuthChallengeResponse response =
                new MinecraftAuthChallengeResponse("123e4567-e89b-12d3-a456-426614174000", "not-a-server-id", Instant.now().plusSeconds(45), 2);

        AuthException exception = assertThrows(AuthException.class, () -> MinecraftAuthService.validateChallenge(response));

        assertEquals(AuthErrorCode.MALFORMED_RESPONSE, exception.getCode());
    }

    @Test
    void validateChallengeRejectsExpiredChallenge() {
        MinecraftAuthChallengeResponse response = new MinecraftAuthChallengeResponse(
                "123e4567-e89b-12d3-a456-426614174000",
                "0123456789abcdef0123456789abcdef01234567",
                Instant.now().minusSeconds(1), 2);

        AuthException exception = assertThrows(AuthException.class, () -> MinecraftAuthService.validateChallenge(response));

        assertEquals(AuthErrorCode.CHALLENGE_EXPIRED, exception.getCode());
    }

    @Test
    void toStoredSessionUsesAuthoritativeBackendIdentity() {
        MinecraftAuthCompleteResponse response = new MinecraftAuthCompleteResponse(
                "backend-token",
                Instant.now().plusSeconds(300),
                new AuthenticatedMinecraftUser("123e4567-e89b-12d3-a456-426614174000", "VerifiedPlayer"));

        StoredAuthSession session = MinecraftAuthService.toStoredSession(response);

        assertEquals("backend-token", session.token());
        assertEquals("123e4567-e89b-12d3-a456-426614174000", session.minecraftUuid());
        assertEquals("VerifiedPlayer", session.minecraftUsername());
    }

    @Test
    void clearedSessionInvalidatesADeferredAuthenticationAttempt() {
        long startedFor = 7;

        assertTrue(MinecraftAuthService.isCurrentAuthenticationAttempt(startedFor, 7));
        assertFalse(MinecraftAuthService.isCurrentAuthenticationAttempt(startedFor, 8));
    }
    @Test
    void rejectsLegacyBackendBeforeSessionJoin() {
        var legacy = new MinecraftAuthChallengeResponse("123e4567-e89b-12d3-a456-426614174000",
                "0123456789abcdef0123456789abcdef01234567", Instant.now().plusSeconds(45), 0);
        assertThrows(AuthException.class, () -> MinecraftAuthService.validateChallenge(legacy));
    }

    @Test
    void actualSessionServiceReceivesOnlyDerivedProofForMainAndRaidOrigins() {
        String seed = "0123456789abcdef0123456789abcdef01234567";
        String id = "123e4567-e89b-12d3-a456-426614174000";
        var joined = new java.util.concurrent.atomic.AtomicReference<String>();
        var minecraftService = (com.mojang.authlib.minecraft.MinecraftSessionService)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {com.mojang.authlib.minecraft.MinecraftSessionService.class},
                        (proxy, method, args) -> {
                            if (!method.getName().equals("joinServer")) {
                                throw new AssertionError("Unexpected session-service operation");
                            }
                            joined.set((String) args[2]);
                            return null;
                        });
        var user = new net.minecraft.client.User("SyntheticPlayer", java.util.UUID.fromString(id),
                "synthetic-offline-fixture", java.util.Optional.empty(), java.util.Optional.empty());
        var auth = new MinecraftAuthService() {
            @Override
            net.minecraft.client.User requireLoggedInUser() { return user; }

            @Override
            com.mojang.authlib.minecraft.MinecraftSessionService resolveSessionService() {
                return minecraftService;
            }
        };
        for (String endpoint : new String[] {"https://api.seqwawa.com/api", "https://staging.seqwawa.com/api"}) {
            var challenge = new MinecraftAuthChallengeResponse(id, seed, Instant.now().plusSeconds(45), 2);
            var completion = auth.joinServer(challenge, endpoint);
            org.junit.jupiter.api.Assertions.assertNotEquals(seed, joined.get());
            assertEquals(MinecraftSessionProof.derive(endpoint, "account", id, seed, completion.client_nonce()),
                    joined.get());
            assertEquals(id, completion.challengeId());
            assertEquals("SyntheticPlayer", completion.username());
        }
        joined.set(null);
        assertThrows(AuthException.class, () -> auth.joinServer(new MinecraftAuthChallengeResponse(
                id, seed, Instant.now().minusSeconds(1), 2), "https://api.seqwawa.com/api"));
        org.junit.jupiter.api.Assertions.assertNull(joined.get());
    }

}
