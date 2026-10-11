package com.seqwawa.seq.images;

import com.mojang.blaze3d.platform.NativeImage;
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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ImagePreviews {
    private static final String PREFIX = "seqimg:";
    private static final Logger LOG = LoggerFactory.getLogger("Sequoia Images");
    private static final LinkedHashMap<String, Preview> CACHE =
            new LinkedHashMap<>(16, 0.75f, true);
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
    private static final ThreadPoolExecutor COMPRESSION =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1),
                    r -> {
                        var t = new Thread(r, "Sequoia cache compression");
                        t.setDaemon(true);
                        return t;
                    });
    private static long textureCounter;

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
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    clear();
                    BridgeMedia.clear();
                });
        ClientTickEvents.END_CLIENT_TICK.register(client -> maintain());
        ClientLifecycleEvents.CLIENT_STOPPING.register(
                client -> {
                    clear();
                    BridgeMedia.clear();
                    DOWNLOADS.shutdownNow();
                    COMPRESSION.shutdownNow();
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
        Preview preview = get(uri);
        if (preview.animation == null) {
            graphics.setTooltipForNextFrame(
                    Minecraft.getInstance().font,
                    Component.literal(preview.statusMessage(System.nanoTime())),
                    mouseX,
                    mouseY);
            return true;
        }
        int maxW = Math.max(1, Math.min(256, graphics.guiWidth() - 20));
        int maxH = Math.max(1, Math.min(180, graphics.guiHeight() - 40));
        double scale =
                Math.min(
                        (double) maxW / preview.animation.width(),
                        (double) maxH / preview.animation.height());
        int w = Math.max(1, (int) (preview.animation.width() * scale)),
                h = Math.max(1, (int) (preview.animation.height() * scale));
        int left = Math.max(4, Math.min(mouseX + 12, graphics.guiWidth() - w - 12));
        int top = Math.max(4, Math.min(mouseY - h - 18, graphics.guiHeight() - h - 24));
        graphics.nextStratum();
        preview.draw(graphics, left, top, w, h);
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

    static Preview get(URI uri) {
        String key = uri.toString();
        Preview found = CACHE.get(key);
        URI resolved = BridgeMedia.resolve(uri);
        if (found != null && resolved.equals(found.source)) {
            found.lastUsed = System.nanoTime();
            if (found.animation == null
                    && found.stored != null
                    && !found.loading
                    && found.error == null) {
                var stored = found.stored;
                load(key, found, cancellation -> CacheCodec.decode(stored, cancellation));
            }
            return found;
        }
        if (found != null) found.close();
        Preview preview = new Preview();
        preview.source = resolved;
        CACHE.put(key, preview);
        trim();
        load(
                key,
                preview,
                cancellation -> {
                    byte[] bytes = MediaDownload.fetch(uri, cancellation);
                    cancellation.check();
                    return IsolatedDecoder.decode(bytes, cancellation);
                });
        return preview;
    }

    @FunctionalInterface
    private interface DecodeTask {
        ImageDecoder.Animation run(MediaCancellation cancellation) throws Exception;
    }

    private static void load(String key, Preview preview, DecodeTask task) {
        preview.loading = true;
        preview.error = null;
        preview.waitingUntil = 0;
        preview.work =
                new FutureTask<>(
                        () -> {
                            ImageDecoder.Animation animation = null;
                            Exception error = null;
                            try {
                                preview.cancellation.check();
                                animation = task.run(preview.cancellation);
                                preview.cancellation.check();
                            } catch (Exception failure) {
                                error = failure;
                            }
                            if (preview.cancellation.isCancelled()) return null;
                            var decoded = animation;
                            var failure = error;
                            Minecraft.getInstance()
                                    .execute(
                                            () -> {
                                                if (CACHE.get(key) != preview
                                                        || preview.cancellation.isCancelled())
                                                    return;
                                                preview.loading = false;
                                                if (failure == null) {
                                                    preview.animation = decoded;
                                                    preview.start = System.nanoTime();
                                                    compress(key, preview);
                                                    trim();
                                                } else {
                                                    preview.failed(failure, System.nanoTime());
                                                    LOG.debug(
                                                            "Image preview failed: {}",
                                                            preview.statusMessage(
                                                                    System.nanoTime()));
                                                }
                                            });
                            return null;
                        });
        try {
            DOWNLOADS.execute(preview.work);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            preview.loading = false;
            preview.error = "Too many images loading; clear previews and try again";
        }
    }

    private static void compress(String key, Preview preview) {
        if (preview.stored != null
                || preview.animation == null
                || preview.compressing
                || preview.compressionFailed) return;
        var animation = preview.animation;
        preview.compressing = true;
        try {
            CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return CacheCodec.encode(animation, preview.cancellation);
                                } catch (Exception e) {
                                    throw new java.util.concurrent.CompletionException(e);
                                }
                            },
                            COMPRESSION)
                    .whenComplete(
                            (stored, error) -> {
                                if (preview.cancellation.isCancelled()) return;
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (CACHE.get(key) != preview
                                                            || preview.cancellation.isCancelled())
                                                        return;
                                                    preview.compressing = false;
                                                    if (error == null) {
                                                        preview.stored = stored;
                                                        if (System.nanoTime() - preview.lastUsed
                                                                > 1_000_000_000L)
                                                            preview.releaseDecoded();
                                                        trim();
                                                    } else {
                                                        preview.compressionFailed = true;
                                                        LOG.warn(
                                                                "Preview cache compression failed; keeping decoded preview",
                                                                error);
                                                    }
                                                });
                            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            preview.compressing = false;
        }
    }

    private static void maintain() {
        if (!ImagePreviewSettings.enabled()) {
            clear();
            BridgeMedia.clear();
            return;
        }
        long now = System.nanoTime();
        for (var entry : CACHE.entrySet()) {
            var preview = entry.getValue();
            if (preview.stored != null && now - preview.lastUsed > 1_000_000_000L)
                preview.releaseDecoded();
            else compress(entry.getKey(), preview);
        }
    }

    private static void trim() {
        long bytes = CACHE.values().stream().mapToLong(Preview::cacheBytes).sum();
        while (CACHE.size() > 16 || bytes > 64L * 1024 * 1024) {
            var iterator = CACHE.entrySet().iterator();
            var preview = iterator.next().getValue();
            iterator.remove();
            bytes -= preview.cacheBytes();
            preview.close();
        }
    }

    public static void clear() {
        CACHE.values().forEach(Preview::close);
        CACHE.clear();
    }

    static final class Preview {
        final MediaCancellation cancellation = new MediaCancellation();
        FutureTask<Void> work;
        URI source;
        ImageDecoder.Animation animation;
        CacheCodec.Encoded stored;
        boolean loading, compressing, compressionFailed;
        long lastUsed = System.nanoTime();
        String error;
        long waitingUntil;

        void failed(Throwable failure, long now) {
            while ((failure instanceof java.util.concurrent.CompletionException
                            || failure instanceof java.util.concurrent.ExecutionException)
                    && failure.getCause() != null) failure = failure.getCause();
            if (failure instanceof MediaDownload.PendingMedia) {
                error = null;
                waitingUntil = now + TimeUnit.SECONDS.toNanos(30);
            } else {
                waitingUntil = 0;
                error = "Image blocked, unavailable or outside safety limits";
            }
        }

        String statusMessage(long now) {
            if (waitingUntil != 0)
                return now - waitingUntil < 0
                        ? "Waiting for Discord media..."
                        : "Image link unavailable; resend it in Discord";
            return error == null ? "Loading image..." : error;
        }

        DynamicTexture texture;
        Identifier textureId;
        int uploadedFrame = -1;
        long start = System.nanoTime();

        void draw(GuiGraphics graphics, int x, int y, int w, int h) {
            if (animation == null) return;
            int frame = animation.frameAt((System.nanoTime() - start) / 1_000_000);
            if (texture == null) {
                textureId =
                        Identifier.fromNamespaceAndPath("seq", "image_preview_" + textureCounter++);
                texture =
                        new DynamicTexture(
                                () -> "Sequoia image preview",
                                new NativeImage(animation.width(), animation.height(), false));
                Minecraft.getInstance().getTextureManager().register(textureId, texture);
            }
            if (frame != uploadedFrame) {
                var pixels = texture.getPixels();
                int[] argb = animation.frames().get(frame).argb();
                for (int py = 0; py < animation.height(); py++)
                    for (int px = 0; px < animation.width(); px++)
                        pixels.setPixel(px, py, argb[py * animation.width() + px]);
                texture.upload();
                uploadedFrame = frame;
            }
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    textureId,
                    x,
                    y,
                    0,
                    0,
                    w,
                    h,
                    animation.width(),
                    animation.height(),
                    animation.width(),
                    animation.height());
        }

        long cacheBytes() {
            return (stored != null ? stored.bytes() : 0)
                    + (animation != null ? animation.bytes() : 0);
        }

        void close() {
            cancellation.cancel();
            if (work != null) {
                work.cancel(true);
                DOWNLOADS.remove(work);
            }
            releaseDecoded();
            stored = null;
        }

        void releaseDecoded() {
            if (textureId != null) Minecraft.getInstance().getTextureManager().release(textureId);
            texture = null;
            textureId = null;
            uploadedFrame = -1;
            animation = null;
        }
    }
}
