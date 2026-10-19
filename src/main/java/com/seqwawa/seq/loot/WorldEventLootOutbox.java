package com.seqwawa.seq.loot;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;

/** Account-isolated bounded queue. Server/world transitions deliberately do not own this lifetime. */
public final class WorldEventLootOutbox {
    private final ArrayDeque<WorldEventLootSample> pending = new ArrayDeque<>();
    private UUID account;
    private boolean enabled;
    private long generation;

    public boolean update(UUID currentAccount, boolean collectionEnabled) {
        if (Objects.equals(account, currentAccount) && enabled == collectionEnabled) return false;
        account = currentAccount;
        enabled = collectionEnabled;
        generation++;
        pending.clear();
        return true;
    }

    public void add(WorldEventLootSample sample) {
        if (pending.size() >= 60) pending.removeFirst();
        pending.addLast(sample);
    }

    public WorldEventLootSample first() { return pending.peekFirst(); }
    public void remove(WorldEventLootSample sample) { pending.remove(sample); }
    public long generation() { return generation; }

    public boolean matchesAccount(String authenticatedUuid) {
        return enabled && account != null && account.toString().equalsIgnoreCase(authenticatedUuid);
    }
}
