package com.seqwawa.seq.network.auth;

import com.mojang.authlib.exceptions.AuthenticationException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Defines the version 2 Minecraft session proof shared with the companion service. */
public final class MinecraftSessionProof {
    public static final int VERSION = 2;

    private static final SecureRandom RANDOM = new SecureRandom();

    private MinecraftSessionProof() {}

    static String derive(String endpoint, String purpose, String challengeId, String seed, String clientNonce) {
        byte[] nonce = decodeNonce(clientNonce, 32);
        byte[] serverSeed;
        if (purpose.equals("account")) {
            if (challengeId == null || !UUID.fromString(challengeId).toString().equals(challengeId)
                    || seed == null || !seed.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("Invalid account authentication challenge.");
            }
            serverSeed = HexFormat.of().parseHex(seed);
        } else if (purpose.equals("treasury")) {
            serverSeed = decodeNonce(seed, 24);
        } else {
            throw new IllegalArgumentException("Unknown session proof purpose.");
        }
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (byte[] field : new byte[][] {
                    "sequoia/minecraft-session-proof/v2".getBytes(StandardCharsets.UTF_8),
                    purpose.getBytes(StandardCharsets.UTF_8),
                    origin(endpoint).getBytes(StandardCharsets.UTF_8),
                    challengeId.getBytes(StandardCharsets.UTF_8), serverSeed, nonce}) {
                hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(field.length).array());
                hash.update(field);
            }
            return HexFormat.of().formatHex(hash.digest(), 0, 20);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static byte[] decodeNonce(String value, int bytes) {
        if (value == null || value.length() != (bytes * 8 + 5) / 6
                || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid session proof nonce.");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (decoded.length != bytes || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
            throw new IllegalArgumentException("Invalid session proof nonce.");
        }
        return decoded;
    }

    private static String origin(String endpoint) {
        URI uri = URI.create(endpoint);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("wss"))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Session proofs require a secure backend origin.");
        }
        int port = uri.getPort();
        return "https://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (port == -1 || port == 443 ? "" : ":" + port);
    }

    /**
     * Joins a proof for one account challenge.
     * @param endpoint build-selected API endpoint
     * @param challengeId canonical challenge UUID
     * @param seed backend's hexadecimal challenge seed
     * @param joiner adapter that sends only the derived ID to Mojang
     * @return completion nonce after a successful join
     * @throws AuthenticationException if Mojang refuses the join
     */
    public static Joined account(String endpoint, String challengeId, String seed, SessionJoiner joiner)
            throws AuthenticationException {
        return join(endpoint, "account", challengeId, seed, joiner);
    }

    /**
     * Joins a treasury proof bound to the configured WebSocket origin.
     * @param endpoint build-selected WebSocket endpoint
     * @param seed backend's base64url challenge seed
     * @param joiner adapter that sends only the derived ID to Mojang
     * @return completion nonce after a successful join
     * @throws AuthenticationException if Mojang refuses the join
     */
    public static Joined treasury(String endpoint, String seed, SessionJoiner joiner)
            throws AuthenticationException {
        return join(endpoint, "treasury", "", seed, joiner);
    }

    private static Joined join(String endpoint, String purpose, String challengeId, String seed, SessionJoiner joiner)
            throws AuthenticationException {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        joiner.join(derive(endpoint, purpose, challengeId, seed, nonce));
        return new Joined(nonce);
    }

    /** Receives only a locally derived server ID, never the backend seed. */
    @FunctionalInterface
    public interface SessionJoiner {
        /**
         * @param serverId locally derived ID to authorize at Mojang
         * @throws AuthenticationException if Mojang refuses the join
         */
        void join(String serverId) throws AuthenticationException;
    }

    /** A completed Mojang join, carrying the nonce needed by the backend verifier. */
    public static final class Joined {
        private final String clientNonce;

        private Joined(String clientNonce) {
            this.clientNonce = clientNonce;
        }

        /** @return the nonce to disclose when completing this successful join */
        public String clientNonce() {
            return clientNonce;
        }
    }
}
