package com.seqwawa.seq.network;

/** Carries the client nonce after Mojang accepts a locally derived session proof. */
public record TreasuryAuthResponse(String type, String nonce, String client_nonce) {
    public static final String CHALLENGE_TYPE = "treasury_auth_challenge";
    public static final String TYPE = "treasury_auth_response";
    public static final String AUTHENTICATED_TYPE = "treasury_authenticated";

    public TreasuryAuthResponse(String nonce, String clientNonce) {
        this(TYPE, nonce, clientNonce);
    }
}
