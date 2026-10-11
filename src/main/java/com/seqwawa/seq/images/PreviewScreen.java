package com.seqwawa.seq.images;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;

final class PreviewScreen extends Screen {
    private final Screen parent;
    private final URI uri;
    private PlainTextButton closeButton;

    PreviewScreen(Screen parent, URI uri) {
        super(Component.literal("Image Preview"));
        this.parent = parent;
        this.uri = uri;
    }

    @Override
    protected void init() {
        closeButton =
                addRenderableWidget(
                        new PlainTextButton(
                                Math.max(0, width - 20),
                                8,
                                12,
                                12,
                                Component.literal("X"),
                                button -> onClose(),
                                font));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Screen's default background would blur and cover the world.
        minecraft.gui.renderDeferredSubtitles();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (!ImagePreviewSettings.enabled()) {
            onClose();
            return;
        }
        var preview = ImagePreviews.get(uri);
        if (preview.animation == null) {
            closeButton.setPosition(Math.max(0, width - 20), 8);
            graphics.drawCenteredString(
                    font,
                    Component.literal(preview.statusMessage(System.nanoTime())),
                    width / 2,
                    height / 2,
                    0xFFE8FFFF);
        } else {
            var animation = preview.animation;
            double scale =
                    Math.min(
                            (double) Math.max(1, width - 48) / animation.width(),
                            (double) Math.max(1, height - 32) / animation.height());
            int w = Math.max(1, (int) (animation.width() * scale)),
                    h = Math.max(1, (int) (animation.height() * scale));
            int left = (width - w) / 2, top = (height - h) / 2;
            preview.draw(graphics, left, top, w, h);
            closeButton.setPosition(left + Math.max(0, w - 14), top + 2);
        }
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
