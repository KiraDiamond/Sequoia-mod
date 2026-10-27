package com.seqwawa.seq.images;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.managers.DiscordRankChatDecorator;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

public final class ImagePreviews {
    private static final String PREFIX = "seqimg:";
    private static final ThreadPoolExecutor DOWNLOADS =
            new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), r -> {
                var t = new Thread(r, "Sequoia image download");
                t.setDaemon(true);
                return t;
            });
    private static final AtomicBoolean CLEAR_REQUESTED = new AtomicBoolean();
    private static final AtomicBoolean PREPARED = new AtomicBoolean();
    static final PreviewCache CACHE = new PreviewCache(
            DOWNLOADS, task -> Minecraft.getInstance().execute(task), ImagePreviews::load, System::nanoTime);

    public static void initialize() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
                dispatcher.register(ClientCommandManager.literal("seqimages")
                        .then(ClientCommandManager.literal("preview")
                                .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                                        .executes(ImagePreviews::previewCommand)))
                        .then(ClientCommandManager.literal("clear").executes(context -> {
                            clear();
                            return 1;
                        }))));
        // Disconnect fires on the network thread, and the client drops queued tasks while it
        // disconnects. Previews and their textures belong to the render thread, so the next
        // client tick clears them.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            BridgeMedia.clear();
            CLEAR_REQUESTED.set(true);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> maintain());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            clear();
            BridgeMedia.clear();
            DOWNLOADS.shutdownNow();
        });
    }

    private static int previewCommand(CommandContext<FabricClientCommandSource> context) {
        URI uri = MediaLinks.imageUri(StringArgumentType.getString(context, "url"));
        if (uri == null) {
            context.getSource()
                    .sendError(Component.literal("Use a supported Discord, Klipy, Tenor, Giphy, Imgur or FxTwitter media link."));
            return 0;
        }
        context.getSource().sendFeedback(decorate(Component.literal(uri.toString())));
        return 1;
    }

    public static Component decorate(Component source) {
        if (!ImagePreviewSettings.enabled()) return source;
        var links = MediaLinks.matches(source.getString());
        if (links.isEmpty()) return source;
        var result = Component.empty();
        // Find links across style boundaries without losing the surrounding chat formatting.
        int[] offset = {0};
        source.visit(
                (style, text) -> {
                    int start = offset[0], end = start + text.length(), cursor = start;
                    for (var link : links) {
                        if (link.end() <= start || link.start() >= end) continue;
                        int before = Math.max(start, link.start());
                        if (before > cursor)
                            result.append(Component.literal(text.substring(cursor - start, before - start))
                                    .withStyle(style));
                        if (link.start() >= start) {
                            URI uri = link.uri();
                            result.append(Component.literal(MediaLinks.isAnimated(uri) ? "[View GIF]" : "[View Image]")
                                    .withStyle(style.withColor(ChatFormatting.AQUA)
                                            .withBold(false)
                                            .withUnderlined(true)
                                            .withInsertion(PREFIX + uri)
                                            .withClickEvent(new ClickEvent.OpenUrl(uri))
                                            .withHoverEvent(new HoverEvent.ShowText(
                                                    Component.literal("Hover to preview; click to enlarge")))));
                        }
                        cursor = Math.min(end, link.end());
                    }
                    if (cursor < end)
                        result.append(Component.literal(text.substring(cursor - start))
                                .withStyle(style));
                    offset[0] = end;
                    return Optional.empty();
                },
                Style.EMPTY);
        DiscordRankChatDecorator.retainBridgeRail(source, result);
        return result;
    }

    private static URI uri(Style style) {
        if (style == null
                || style.getInsertion() == null
                || !style.getInsertion().startsWith(PREFIX)) return null;
        return MediaLinks.imageUri(style.getInsertion().substring(PREFIX.length()));
    }

    public static boolean hover(GuiGraphics graphics, Style style, int mouseX, int mouseY) {
        URI uri = uri(style);
        if (uri == null) return false;
        if (!ImagePreviewSettings.enabled()) {
            graphics.setTooltipForNextFrame(
                    Minecraft.getInstance().font, Component.literal("Image previews are disabled"), mouseX, mouseY);
            return true;
        }
        var preview = CACHE.get(uri);
        var frames = preview.frames();
        if (frames != null) {
            int maxW = Math.max(1, Math.min(256, graphics.guiWidth() - 20));
            int maxH = Math.max(1, Math.min(180, graphics.guiHeight() - 40));
            double scale = fitScale(frames, maxW, maxH);
            int w = Math.max(1, (int) (frames.width() * scale)), h = Math.max(1, (int) (frames.height() * scale));
            int left = Math.max(4, Math.min(mouseX + 12, graphics.guiWidth() - w - 12));
            int top = Math.max(4, Math.min(mouseY - h - 18, graphics.guiHeight() - h - 24));
            graphics.nextStratum();
            if (CACHE.draw(preview, graphics, left, top, w, h)) return true;
        }
        graphics.setTooltipForNextFrame(
                Minecraft.getInstance().font, Component.literal(preview.status(System.nanoTime())), mouseX, mouseY);
        return true;
    }

    public static boolean click(Style style, boolean shift, Screen parent) {
        URI uri = uri(style);
        if (uri == null) return false;
        if (shift) { // Insert the URL rather than the seqimg: marker.
            if (parent instanceof ChatScreen chat) chat.insertText(uri.toString(), false);
        } else {
            if (!ImagePreviewSettings.enabled()) return false;
            Minecraft.getInstance().setScreen(new PreviewScreen(parent, uri));
        }
        return true;
    }

    /** Largest scale that fits the frames within the given box, keeping their aspect ratio. */
    static double fitScale(MediaFrames frames, double maxWidth, double maxHeight) {
        return Math.min(maxWidth / frames.width(), maxHeight / frames.height());
    }

    /** Starts the helper first so its sandbox setup overlaps the download. */
    private static MediaFrames load(
            URI link, MediaCancellation cancellation, ByteBudget.Lease lease, Consumer<MediaFrames> firstFrame)
            throws Exception {
        try (var decoder = IsolatedDecoder.start(cancellation)) {
            byte[] media = MediaDownload.fetch(link, cancellation, lease);
            cancellation.check();
            return decoder.decode(media, lease, firstFrame);
        }
    }

    private static void maintain() {
        if (CLEAR_REQUESTED.getAndSet(false)) clear();
        if (!ImagePreviewSettings.enabled()) {
            clear();
            BridgeMedia.clear();
            return;
        }
        // Extract, verify and page in the helper once previews are on, without any network request.
        if (PREPARED.compareAndSet(false, true))
            DOWNLOADS.execute(() -> {
                try {
                    IsolatedDecoder.prepare();
                } catch (IOException failure) {
                    SeqClient.LOGGER.debug("[Images] Sandbox preparation deferred until preview", failure);
                }
            });
        CACHE.maintain();
    }

    public static void clear() {
        CACHE.clear();
    }
}
