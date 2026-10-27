package com.seqwawa.seq.network.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class MinecraftSessionProofTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String SEED = "0123456789abcdef0123456789abcdef01234567";
    private static final String NONCE = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @Test
    void matchesIndependentProtocolVectors() {
        assertEquals("463d51c029c31006303ea6c6f5ce87dd25a39c1d",
                MinecraftSessionProof.derive("https://api.seqwawa.com/api", "account", ID, SEED, NONCE));
        assertEquals("3c5216f45011b25bea14315e35c3b989cb8838d5",
                MinecraftSessionProof.derive("https://staging.seqwawa.com/api", "account", ID, SEED, NONCE));
        assertEquals("b52e145de14ee3706f276adf3af6f199e6a474f6",
                MinecraftSessionProof.derive("https://api.seqwawa.com:8443/api", "account", ID, SEED, NONCE));
        assertEquals("9a957456542e34da739e5630f4b6ebb789546035",
                MinecraftSessionProof.derive("wss://api.seqwawa.com/ws", "treasury", "",
                        "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX", NONCE));
        assertEquals(MinecraftSessionProof.derive("https://api.seqwawa.com", "account", ID, SEED, NONCE),
                MinecraftSessionProof.derive("https://API.SEQWAWA.COM:443/api/", "account", ID, SEED, NONCE));
    }

    @Test
    void rejectsMissingMalformedOrNoncanonicalClientNonce() {
        for (String nonce : new String[] {null, "", "short", NONCE + "=", NONCE.substring(0, 42) + "9"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> MinecraftSessionProof.derive("https://api.seqwawa.com", "account", ID, SEED, nonce));
        }
    }

    @Test
    void rejectsInsecureOrAmbiguousOrigins() {
        for (String origin : new String[] {"http://api.seqwawa.com", "https://user@api.seqwawa.com",
                "https://api.seqwawa.com?audience=evil", "https://api.seqwawa.com#fragment", "https:///missing"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> MinecraftSessionProof.derive(origin, "account", ID, SEED, NONCE));
        }
    }
    @Test
    void accountJoinUsesFreshClientEntropyAndNeverAuthorizesTheRemoteSeed() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> joined = new java.util.concurrent.atomic.AtomicReference<>();
        MinecraftSessionProof.Joined first = MinecraftSessionProof.account(
                "https://api.seqwawa.com/api", ID, SEED, joined::set);
        assertNotEquals(SEED, joined.get());
        assertEquals(MinecraftSessionProof.derive("https://api.seqwawa.com", "account", ID, SEED,
                first.clientNonce()), joined.get());
        MinecraftSessionProof.Joined second = MinecraftSessionProof.account(
                "https://api.seqwawa.com/api", ID, SEED, joined::set);
        assertNotEquals(first.clientNonce(), second.clientNonce());
        assertTrue(joined.get().matches("[0-9a-f]{40}"));
    }

    @Test
    void failedJoinDoesNotReturnACompletionNonce() {
        assertThrows(com.mojang.authlib.exceptions.AuthenticationException.class,
                () -> MinecraftSessionProof.account("https://api.seqwawa.com", ID, SEED,
                        ignored -> { throw new com.mojang.authlib.exceptions.AuthenticationException("denied"); }));
    }

    @Test
    void bindsClientNonceChallengeAndSeed() {
        String original = MinecraftSessionProof.derive("https://api.seqwawa.com", "account", ID, SEED, NONCE);
        assertNotEquals(original, MinecraftSessionProof.derive("https://api.seqwawa.com", "account",
                "123e4567-e89b-12d3-a456-426614174001", SEED, NONCE));
        assertNotEquals(original, MinecraftSessionProof.derive("https://api.seqwawa.com", "account",
                ID, "0123456789abcdef0123456789abcdef01234568", NONCE));
        assertNotEquals(original, MinecraftSessionProof.derive("https://api.seqwawa.com", "account",
                ID, SEED, "B" + NONCE.substring(1)));
    }

}
