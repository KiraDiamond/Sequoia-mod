package com.seqwawa.seq.integrations;

import com.wynntils.core.components.Models;
import com.seqwawa.seq.client.SeqClient;
import net.fabricmc.loader.api.FabricLoader;

/** Optional identity hint, read while the event is active, before completion clears it. */
public final class WynntilsWorldEventAccess {
    private static boolean warned;
    private WynntilsWorldEventAccess() {}

    public record Observation(String name, String status) {}

    public static Observation observe() {
        try {
            if (!FabricLoader.getInstance().isModLoaded("wynntils")) return new Observation(null, "not_installed");
            var event = Models.WorldEvent.getCurrentWorldEvent();
            return event == null ? new Observation(null, "no_active_event") : new Observation(event.getName(), "ready");
        } catch (LinkageError | RuntimeException error) {
            if (!warned) {
                warned = true;
                String version = FabricLoader.getInstance().getModContainer("wynntils")
                        .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
                SeqClient.LOGGER.warn("[WorldEventLoot] Cannot read Wynntils event identity (version={})", version, error);
            }
            return new Observation(null, "unavailable:" + error.getClass().getSimpleName());
        }
    }
}
