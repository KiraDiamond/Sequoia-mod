package com.seqwawa.seq.network.auth;

import java.time.Instant;

public record MinecraftAuthChallengeResponse(String challenge_id, String server_id, Instant expires_at, int proof_version) {

    public String challengeId() {
        return challenge_id;
    }

    public String serverId() {
        return server_id;
    }

    public Instant expiresAt() {
        return expires_at;
    }
}
