package com.seqwawa.seq.images;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/**
 * Plays one preview from its compressed frames. Every frame is uploaded once into a texture
 * atlas, so after the first loop drawing is a single textured quad with no per-frame CPU or
 * upload cost. Frames after the first are rebuilt on a virtual thread. Render thread only;
 * close it outside rendering, because the GUI renderer samples textures at the end of a frame.
 */
final class PreviewPlayer implements AutoCloseable {
    private static final int READY_FRAMES = 3;
    private static long textureCounter;

    private final MediaFrames frames;
    private final FrameAtlas atlas;
    private final Identifier id;
    private final AtlasTexture texture;
    private FrameProducer producer;
    private int uploaded;
    private long startNanos;
    private long lastDrawn;

    /** Creates a player for an animation with at least its first frame published. */
    PreviewPlayer(MediaFrames frames, FrameAtlas atlas, long now) throws IOException {
        this.frames = frames;
        this.atlas = atlas;
        id = Identifier.fromNamespaceAndPath("seq", "image_preview_" + textureCounter++);
        texture = new AtlasTexture(atlas, frames.frameCount() == 1);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        var reader = frames.reader();
        try {
            // The first frame is uploaded here so the first draw already shows the image.
            var staging = ByteBuffer.allocateDirect(frames.frameBytes());
            staging.put(reader.next()).flip();
            upload(0, staging);
            uploaded = 1;
            if (frames.frameCount() > 1) producer = new FrameProducer(frames, reader, staging);
            else reader.close();
        } catch (IOException | RuntimeException failure) {
            reader.close();
            close();
            throw failure;
        }
        startNanos = now;
        lastDrawn = now;
    }

    /** The atlas layout this GPU allows for {@code frames}. Render thread only. */
    static FrameAtlas atlasFor(MediaFrames frames) throws IOException {
        return FrameAtlas.of(
                frames.width(),
                frames.height(),
                frames.frameCount(),
                RenderSystem.getDevice().getMaxTextureSize());
    }

    long textureBytes() {
        return atlas.bytes();
    }

    long lastDrawn() {
        return lastDrawn;
    }

    /**
     * Draws the frame due at {@code now}. While the first loop is still being decoded or uploaded, playback
     * holds on the newest uploaded frame rather than skipping ahead.
     */
    void draw(GuiGraphics graphics, int x, int y, int width, int height, long now) throws IOException {
        lastDrawn = now;
        drain();
        int frame = frames.frameAt((now - startNanos) / 1_000_000);
        if (frame >= uploaded) {
            frame = uploaded - 1;
            startNanos = now - frames.startMs(frame) * 1_000_000;
        }
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                id,
                x,
                y,
                atlas.x(frame),
                atlas.y(frame),
                width,
                height,
                atlas.frameWidth(),
                atlas.frameHeight(),
                atlas.width(),
                atlas.height());
    }

    private void drain() throws IOException {
        if (producer == null) return;
        FrameProducer.Ready ready;
        while ((ready = producer.ready.poll()) != null) {
            upload(ready.frame(), ready.pixels());
            uploaded = ready.frame() + 1;
            producer.free.offer(ready.pixels());
        }
        if (producer.failure != null) throw producer.failure;
        if (uploaded == frames.frameCount()) producer = null;
    }

    private void upload(int frame, ByteBuffer pixels) {
        RenderSystem.getDevice()
                .createCommandEncoder()
                .writeToTexture(
                        texture.getTexture(),
                        pixels,
                        NativeImage.Format.RGBA,
                        0,
                        0,
                        atlas.x(frame),
                        atlas.y(frame),
                        atlas.frameWidth(),
                        atlas.frameHeight());
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.thread.interrupt();
            producer = null;
        }
        Minecraft.getInstance().getTextureManager().release(id);
    }

    /** Rebuilds frames in order into a few direct buffers that the render thread uploads. */
    private static final class FrameProducer implements Runnable {
        record Ready(int frame, ByteBuffer pixels) {}

        final ArrayBlockingQueue<Ready> ready = new ArrayBlockingQueue<>(READY_FRAMES);
        final ArrayBlockingQueue<ByteBuffer> free = new ArrayBlockingQueue<>(READY_FRAMES);
        final Thread thread;
        private final MediaFrames frames;
        private final MediaFrames.Reader reader;
        volatile IOException failure;

        FrameProducer(MediaFrames frames, MediaFrames.Reader reader, ByteBuffer first) {
            this.frames = frames;
            this.reader = reader;
            free.add(first.clear());
            for (int i = 1; i < READY_FRAMES; i++) free.add(ByteBuffer.allocateDirect(first.capacity()));
            thread = Thread.ofVirtual().name("Sequoia preview frames").start(this);
        }

        @Override
        public void run() {
            try (reader) {
                for (int frame = 1; frame < frames.frameCount(); frame++) {
                    // A failed stream is replaced by the cache; there is nothing more to show.
                    if (!frames.awaitFrame(frame)) return;
                    byte[] pixels = reader.next();
                    ByteBuffer target = free.take();
                    target.clear().put(pixels).flip();
                    ready.put(new Ready(frame, target));
                }
            } catch (InterruptedException closed) {
                // The player was released.
            } catch (IOException corrupt) {
                failure = corrupt;
            }
        }
    }

    /** RGBA texture written frame by frame; unlike DynamicTexture it keeps no CPU copy. */
    private static final class AtlasTexture extends AbstractTexture {
        AtlasTexture(FrameAtlas atlas, boolean stillImage) {
            var device = RenderSystem.getDevice();
            texture = device.createTexture(
                    () -> "Sequoia image preview",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    TextureFormat.RGBA8,
                    atlas.width(),
                    atlas.height(),
                    1,
                    1);
            // Single-frame textures have no neighboring frames for linear sampling to bleed into.
            sampler = RenderSystem.getSamplerCache().getClampToEdge(stillImage ? FilterMode.LINEAR : FilterMode.NEAREST);
            textureView = device.createTextureView(texture);
        }
    }
}
