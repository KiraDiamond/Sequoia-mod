package com.seqwawa.seq.images;

import static com.seqwawa.seq.managers.ThemeManager.color;
import static com.seqwawa.seq.ui.theme.UiColor.*;

import com.seqwawa.seq.ui.theme.UiColor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.NotNull;

import java.net.URI;

/**
 * Enlarged preview in a themed panel. Drawn with GuiGraphics so the image composites in the same
 * pass as its frame; theme colors keep their configured alpha.
 */
final class PreviewScreen extends Screen {
    private static final int PADDING = 8;
    private static final int HEADER = 20;
    private static final int CLOSE = 14;
    private static final int MARGIN = 16;

    private final Screen parent;
    private final URI uri;
    private int closeX;
    private int closeY;

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
        var preview = ImagePreviews.get(uri);
        var frames = preview.frames();
        String status = preview.status(System.nanoTime());
        int contentW, contentH;
        if (frames != null) {
            double scale =
                    Math.min(
                            (double) Math.max(1, width - 2 * (MARGIN + PADDING)) / frames.width(),
                            (double) Math.max(1, height - 2 * (MARGIN + PADDING) - HEADER)
                                    / frames.height());
            contentW = Math.max(1, (int) (frames.width() * scale));
            contentH = Math.max(1, (int) (frames.height() * scale));
        } else {
            contentW = Math.min(width - 2 * (MARGIN + PADDING), font.width(status) + 2 * PADDING);
            contentH = font.lineHeight + 2 * PADDING;
        }
        int panelW = Math.max(contentW, font.width(title) + CLOSE + 3 * PADDING) + 2 * PADDING;
        int panelH = HEADER + contentH + 2 * PADDING;
        int left = (width - panelW) / 2, top = (height - panelH) / 2;
        graphics.fill(0, 0, width, height, argb(BACKGROUND_MODAL_OVERLAY));
        graphics.fill(left, top, left + panelW, top + panelH, argb(BACKGROUND_BODY_OPAQUE));
        graphics.fill(left, top, left + panelW, top + HEADER, argb(BACKGROUND_HEADER));
        graphics.fill(
                left, top + HEADER - 1, left + panelW, top + HEADER, argb(ACCENT_PRIMARY_DARK));
        graphics.drawString(
                font,
                title,
                left + PADDING,
                top + (HEADER - font.lineHeight) / 2 + 1,
                argb(ACCENT_PRIMARY_HOVER),
                false);
        closeX = left + panelW - PADDING - CLOSE;
        closeY = top + (HEADER - CLOSE) / 2;
        boolean closeHovered = overClose(mouseX, mouseY);
        graphics.fill(
                closeX,
                closeY,
                closeX + CLOSE,
                closeY + CLOSE,
                argb(closeHovered ? ACCENT_PRIMARY_DARK_HOVER : CONTROL_INPUT_HOVER));
        graphics.drawCenteredString(
                font,
                "x",
                closeX + CLOSE / 2,
                closeY + (CLOSE - font.lineHeight) / 2 + 1,
                argb(closeHovered ? TEXT_PRIMARY : TEXT_SECONDARY));
        int contentX = left + (panelW - contentW) / 2, contentY = top + HEADER + PADDING;
        if (frames == null
                || !ImagePreviews.draw(preview, graphics, contentX, contentY, contentW, contentH))
            graphics.drawCenteredString(
                    font,
                    preview.status(System.nanoTime()),
                    left + panelW / 2,
                    contentY + (contentH - font.lineHeight) / 2,
                    argb(TEXT_SECONDARY));
    }

    private boolean overClose(double mouseX, double mouseY) {
        return mouseX >= closeX
                && mouseX < closeX + CLOSE
                && mouseY >= closeY
                && mouseY < closeY + CLOSE;
    }

    private static int argb(UiColor token) {
        return color(token).getRGB();
    }

    @Override
    public boolean mouseClicked(@NotNull MouseButtonEvent click, boolean doubleClick) {
        if (click.button() == 0 && overClose(click.x(), click.y())) {
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
