package com.seqwawa.seq.network.auth;

public record MinecraftAuthCompleteRequest(String challenge_id, String username, String client_nonce) {

    public String challengeId() {
        return challenge_id;
    }
}
