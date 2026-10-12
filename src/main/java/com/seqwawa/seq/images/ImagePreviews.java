package com.seqwawa.seq.images;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.seqwawa.seq.managers.DiscordRankChatDecorator;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ImagePreviews {
    private static final String PREFIX = "seqimg:";
    private static final Logger LOG = LoggerFactory.getLogger("Sequoia Images");
    private static final ThreadPoolExecutor DOWNLOADS =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(16),
                    r -> {
                        var t = new Thread(r, "Sequoia image download");
                        t.setDaemon(true);
                        return t;
                    });
    private static final AtomicBoolean CLEAR_REQUESTED = new AtomicBoolean();
    private static final PreviewCache CACHE =
            new PreviewCache(
                    DOWNLOADS,
                    task -> Minecraft.getInstance().execute(task),
                    ImagePreviews::load,
                    System::nanoTime);

    public static void initialize() {
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registry) ->
                        dispatcher.register(
                                ClientCommandManager.literal("seqimages")
                                        .then(
                                                ClientCommandManager.literal("preview")
                                                        .then(
                                                                ClientCommandManager.argument(
                                                                                "url",
                                                                                StringArgumentType
                                                                                        .greedyString())
                                                                        .executes(
                                                                                ImagePreviews
                                                                                        ::previewCommand)))
                                        .then(
                                                ClientCommandManager.literal("clear")
                                                        .executes(
                                                                context -> {
                                                                    clear();
                                                                    return 1;
                                                                }))));
        // Disconnect fires on the network thread, and the client drops queued tasks while it
        // disconnects. Previews and their textures belong to the render thread, so the next
        // client tick clears them.
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    BridgeMedia.clear();
                    CLEAR_REQUESTED.set(true);
                });
        ClientTickEvents.END_CLIENT_TICK.register(client -> maintain());
        ClientLifecycleEvents.CLIENT_STOPPING.register(
                client -> {
                    clear();
                    BridgeMedia.clear();
                    DOWNLOADS.shutdownNow();
                });
        DOWNLOADS.execute(
                () -> {
                    try {
                        IsolatedDecoder.prepare();
                    } catch (java.io.IOException failure) {
                        LOG.debug("Sandbox preparation deferred until preview", failure);
                    }
                });
        LOG.info("Sequoia Images ready");
    }

    private static int previewCommand(CommandContext<FabricClientCommandSource> context) {
        URI uri = MediaLinks.imageUri(StringArgumentType.getString(context, "url"));
        if (uri == null) {
            context.getSource()
                    .sendError(
                            Component.literal(
                                    "Use a Discord, Klipy, Tenor, Giphy or Imgur GIF/PNG/JPEG link."));
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
                            result.append(
                                    Component.literal(
                                                    text.substring(cursor - start, before - start))
                                            .withStyle(style));
                        if (link.start() >= start) {
                            URI uri = link.uri();
                            boolean gif =
                                    uri.getPath()
                                                    .toLowerCase(java.util.Locale.ROOT)
                                                    .endsWith(".gif")
                                            || MediaLinks.isTenorPage(uri)
                                            || MediaLinks.isKlipyPage(uri)
                                            || MediaLinks.isKlipyVideo(uri);
                            result.append(
                                    Component.literal(gif ? "[View GIF]" : "[View Image]")
                                            .withStyle(
                                                    style.withColor(ChatFormatting.AQUA)
                                                            .withBold(false)
                                                            .withUnderlined(true)
                                                            .withInsertion(PREFIX + uri)
                                                            .withClickEvent(
                                                                    new ClickEvent.OpenUrl(uri))
                                                            .withHoverEvent(
                                                                    new HoverEvent.ShowText(
                                                                            Component.literal(
                                                                                    "Hover to preview; click to enlarge")))));
                        }
                        cursor = Math.min(end, link.end());
                    }
                    if (cursor < end)
                        result.append(
                                Component.literal(text.substring(cursor - start)).withStyle(style));
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
                    Minecraft.getInstance().font,
                    Component.literal("Image previews are disabled"),
                    mouseX,
                    mouseY);
            return true;
        }
        var preview = get(uri);
        var frames = preview.frames();
        if (frames != null) {
            int maxW = Math.max(1, Math.min(256, graphics.guiWidth() - 20));
            int maxH = Math.max(1, Math.min(180, graphics.guiHeight() - 40));
            double scale = fitScale(frames, maxW, maxH);
            int w = Math.max(1, (int) (frames.width() * scale)),
                    h = Math.max(1, (int) (frames.height() * scale));
            int left = Math.max(4, Math.min(mouseX + 12, graphics.guiWidth() - w - 12));
            int top = Math.max(4, Math.min(mouseY - h - 18, graphics.guiHeight() - h - 24));
            graphics.nextStratum();
            // Still showable when drawing waits a tick for texture memory.
            if (draw(preview, graphics, left, top, w, h) || preview.frames() != null) return true;
        }
        graphics.setTooltipForNextFrame(
                Minecraft.getInstance().font,
                Component.literal(preview.status(System.nanoTime())),
                mouseX,
                mouseY);
        return true;
    }

    public static boolean click(Style style, boolean shift, Screen parent) {
        URI uri = uri(style);
        if (uri == null) return false;
        if (shift) { // Insert the URL rather than the seqimg: marker.
            if (parent instanceof net.minecraft.client.gui.screens.ChatScreen chat)
                chat.insertText(uri.toString(), false);
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

    static PreviewCache.Preview get(URI uri) {
        return CACHE.get(uri);
    }

    /**
     * Draws a ready preview; returns false while it is loading, after it failed, or while it
     * waits a tick for texture memory.
     */
    static boolean draw(
            PreviewCache.Preview preview, GuiGraphics graphics, int x, int y, int w, int h) {
        return CACHE.draw(preview, graphics, x, y, w, h);
    }

    /** Starts the helper first so its sandbox setup overlaps the download. */
    private static MediaFrames load(
            URI link,
            MediaCancellation cancellation,
            ByteBudget.Lease lease,
            Consumer<MediaFrames> firstFrame)
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
        CACHE.maintain();
    }

    public static void clear() {
        CACHE.clear();
    }
}
