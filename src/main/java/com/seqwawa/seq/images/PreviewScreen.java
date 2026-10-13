package com.seqwawa.seq.images;

import static com.seqwawa.seq.managers.ThemeManager.color;
import static com.seqwawa.seq.ui.theme.UiColor.*;

import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.render.MinecraftGuiOverlay;
import com.seqwawa.seq.ui.SequoiaUiStyle;
import com.seqwawa.seq.utils.rendering.MinecraftUiRenderer;
import com.seqwawa.seq.utils.rendering.UiCanvas;
import com.seqwawa.seq.utils.rendering.UiRenderMetrics;
import com.seqwawa.seq.utils.rendering.UiRenderer;
import java.awt.Color;
import java.net.URI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/**
 * Enlarged preview in a themed panel. The panel is drawn with UiCanvas; the image is drawn in
 * the Minecraft GUI pass that runs after the UiCanvas frame, so it sits on top of the panel.
 */
final class PreviewScreen extends Screen implements MinecraftGuiOverlay {
    private static final float MARGIN = 16;
    private static final float CLOSE_SIZE = 18;
    private static final float TITLE_TEXT_SIZE = 14;
    private static final float BODY_TEXT_SIZE = 12;

    private final Screen parent;
    private final URI uri;
    private PreviewCache.Preview preview;
    private Layout layout;

    /** Panel geometry in Sequoia UI units. */
    private record Layout(
            float panelX,
            float panelY,
            float panelWidth,
            float panelHeight,
            float contentX,
            float contentY,
            float contentWidth,
            float contentHeight) {
        float closeX() {
            return panelX + panelWidth - SequoiaUiStyle.CONTENT_PADDING - CLOSE_SIZE;
        }

        float closeY() {
            return panelY + (SequoiaUiStyle.HEADER_HEIGHT - CLOSE_SIZE) / 2;
        }

        boolean overClose(float x, float y) {
            return x >= closeX() && x < closeX() + CLOSE_SIZE && y >= closeY() && y < closeY() + CLOSE_SIZE;
        }
    }

    PreviewScreen(Screen parent, URI uri) {
        super(Component.literal("Image Preview"));
        this.parent = parent;
        this.uri = uri;
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
        preview = ImagePreviews.CACHE.get(uri);
        var frames = preview.frames();
        String status = frames == null ? preview.status(System.nanoTime()) : null;
        String font = SeqClient.getFontManager().getSelectedFont();
        var metrics = MinecraftUiRenderer.metrics();
        layout = layout(metrics, frames, status, font);
        var current = layout;
        boolean closeHovered =
                current.overClose(MinecraftUiRenderer.mouseX(mouseX), MinecraftUiRenderer.mouseY(mouseY));
        UiRenderer.renderScreen(this, canvas -> drawPanel(canvas, font, current, closeHovered, status));
    }

    private Layout layout(UiRenderMetrics metrics, MediaFrames frames, String status, String font) {
        float padding = SequoiaUiStyle.CONTENT_PADDING, header = SequoiaUiStyle.HEADER_HEIGHT;
        float maxWidth = Math.max(1, metrics.width() - 2 * (MARGIN + padding));
        float maxHeight = Math.max(1, metrics.height() - 2 * (MARGIN + padding) - header);
        float contentWidth, contentHeight;
        if (frames != null) {
            double scale = ImagePreviews.fitScale(frames, maxWidth, maxHeight);
            contentWidth = (float) (frames.width() * scale);
            contentHeight = (float) (frames.height() * scale);
        } else {
            float statusWidth =
                    UiRenderer.measureText(status, font, BODY_TEXT_SIZE).width();
            contentWidth = Math.min(maxWidth, statusWidth + 2 * padding);
            contentHeight = BODY_TEXT_SIZE + 2 * padding;
        }
        float titleWidth =
                UiRenderer.measureText(title.getString(), font, TITLE_TEXT_SIZE).width();
        float panelWidth = Math.max(contentWidth, titleWidth + CLOSE_SIZE + 3 * padding) + 2 * padding;
        float panelHeight = header + contentHeight + 2 * padding;
        float panelX = (metrics.width() - panelWidth) / 2;
        float panelY = (metrics.height() - panelHeight) / 2;
        return new Layout(
                panelX,
                panelY,
                panelWidth,
                panelHeight,
                panelX + (panelWidth - contentWidth) / 2,
                panelY + header + padding,
                contentWidth,
                contentHeight);
    }

    private void drawPanel(UiCanvas canvas, String font, Layout layout, boolean closeHovered, String status) {
        float width = canvas.metrics().width(), height = canvas.metrics().height();
        canvas.fillRect(0, 0, width, height, color(BACKGROUND_MODAL_OVERLAY));
        canvas.fillRect(
                layout.panelX(),
                layout.panelY(),
                layout.panelWidth(),
                layout.panelHeight(),
                color(BACKGROUND_BODY_OPAQUE));
        canvas.fillRect(
                layout.panelX(),
                layout.panelY(),
                layout.panelWidth(),
                SequoiaUiStyle.HEADER_HEIGHT,
                color(BACKGROUND_HEADER));
        canvas.fillRect(
                layout.panelX(),
                layout.panelY() + SequoiaUiStyle.HEADER_HEIGHT - 1,
                layout.panelWidth(),
                1,
                color(ACCENT_PRIMARY_DARK));
        canvas.drawText(
                title.getString(),
                layout.panelX() + SequoiaUiStyle.CONTENT_PADDING,
                layout.panelY() + SequoiaUiStyle.HEADER_HEIGHT / 2,
                text(font, TITLE_TEXT_SIZE, color(ACCENT_PRIMARY_HOVER), UiCanvas.HorizontalAlign.LEFT));
        canvas.fillRect(
                layout.closeX(),
                layout.closeY(),
                CLOSE_SIZE,
                CLOSE_SIZE,
                color(closeHovered ? CONTROL_INPUT_HOVER : CONTROL_INPUT));
        canvas.drawText(
                "x",
                layout.closeX() + CLOSE_SIZE / 2,
                layout.closeY() + CLOSE_SIZE / 2,
                text(
                        font,
                        BODY_TEXT_SIZE,
                        color(closeHovered ? TEXT_PRIMARY : TEXT_SECONDARY),
                        UiCanvas.HorizontalAlign.CENTER));
        if (status != null)
            canvas.drawText(
                    status,
                    layout.contentX() + layout.contentWidth() / 2,
                    layout.contentY() + layout.contentHeight() / 2,
                    text(font, BODY_TEXT_SIZE, color(TEXT_SECONDARY), UiCanvas.HorizontalAlign.CENTER));
    }

    private static UiCanvas.TextStyle text(String font, float size, Color color, UiCanvas.HorizontalAlign align) {
        return new UiCanvas.TextStyle(font, size, color, align, UiCanvas.VerticalAlign.MIDDLE);
    }

    @Override
    public void renderMinecraftGuiOverlay(GuiGraphics graphics, UiRenderMetrics metrics) {
        var shown = preview;
        var current = layout;
        if (shown == null || current == null || shown.frames() == null) return;
        float guiUnitsPerUiUnit = metrics.guiUnitsPerUiUnit();
        ImagePreviews.CACHE.draw(
                shown,
                graphics,
                Math.round(current.contentX() * guiUnitsPerUiUnit),
                Math.round(current.contentY() * guiUnitsPerUiUnit),
                Math.max(1, Math.round(current.contentWidth() * guiUnitsPerUiUnit)),
                Math.max(1, Math.round(current.contentHeight() * guiUnitsPerUiUnit)));
    }

    @Override
    public boolean mouseClicked(@NotNull MouseButtonEvent click, boolean doubleClick) {
        var current = layout;
        if (click.button() == 0
                && current != null
                && current.overClose(MinecraftUiRenderer.mouseX(click.x()), MinecraftUiRenderer.mouseY(click.y()))) {
            onClose();
            return true;
        }
        return super.mouseClicked(click, doubleClick);
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
