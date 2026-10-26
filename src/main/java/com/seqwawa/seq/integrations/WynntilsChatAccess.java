package com.seqwawa.seq.integrations;

import com.mojang.logging.LogUtils;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;

/** Keeps optional Wynntils classes out of the vanilla chat mixin and guild-tag cache. */
public final class WynntilsChatAccess {
    private static final WynntilsChatAccess INSTANCE = new WynntilsChatAccess(
            () -> FabricLoader.getInstance().isModLoaded("wynntils"),
            () -> (Bridge) Class.forName("com.seqwawa.seq.integrations.WynntilsChatBridge")
                    .getDeclaredConstructor().newInstance(),
            error -> LogUtils.getLogger().warn("[Wynntils] Chat integration is unavailable", error));

    private final BooleanSupplier modLoaded;
    private final BridgeResolver resolver;
    private final Consumer<Throwable> logFailure;
    private Bridge bridge;
    private boolean resolved;
    private boolean failureLogged;

    WynntilsChatAccess(BooleanSupplier modLoaded, BridgeResolver resolver, Consumer<Throwable> logFailure) {
        this.modLoaded = modLoaded;
        this.resolver = resolver;
        this.logFailure = logFailure;
    }

    public static boolean isAvailable() {
        return INSTANCE.resolve() != null;
    }

    public static CompletableFuture<String> lookupGuildTag(String username) {
        return INSTANCE.lookup(username);
    }

    public static Component revealNickname(Component name, String username) {
        return INSTANCE.reveal(name, username);
    }

    Component reveal(Component name, String username) {
        Bridge integration = resolve();
        if (integration != null) try {
            Component revealed = integration.revealNickname(name, username);
            if (revealed != null) return revealed;
        } catch (RuntimeException | LinkageError error) { warnOnce(error); }
        return name;
    }

    public static void postAddedLine(GuiMessage message, GuiMessage.Line line) {
        INSTANCE.post(message, line);
    }

    private Bridge resolve() {
        if (!resolved) {
            resolved = true;
            if (modLoaded.getAsBoolean()) {
                try {
                    bridge = resolver.resolve();
                } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    warnOnce(error);
                }
            }
        }
        return bridge;
    }

    CompletableFuture<String> lookup(String username) {
        Bridge integration = resolve();
        if (integration == null) return CompletableFuture.completedFuture("");
        try {
            return integration.lookupGuildTag(username);
        } catch (RuntimeException | LinkageError error) {
            warnOnce(error);
            return CompletableFuture.failedFuture(error);
        }
    }

    void post(GuiMessage message, GuiMessage.Line line) {
        Bridge integration = resolve();
        if (integration == null) return;
        try {
            integration.postAddedLine(message, line);
        } catch (RuntimeException | LinkageError error) {
            warnOnce(error);
        }
    }

    private void warnOnce(Throwable error) {
        if (!failureLogged) {
            failureLogged = true;
            logFailure.accept(error);
        }
    }

    public interface Bridge {
        default Component revealNickname(Component name, String username) { return name; }

        CompletableFuture<String> lookupGuildTag(String username);

        void postAddedLine(GuiMessage message, GuiMessage.Line line);
    }

    @FunctionalInterface
    interface BridgeResolver {
        Bridge resolve() throws ReflectiveOperationException;
    }
}
