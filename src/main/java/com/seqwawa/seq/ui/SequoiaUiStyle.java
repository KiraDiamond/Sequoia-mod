package com.seqwawa.seq.ui;

import static com.seqwawa.seq.managers.ThemeManager.color;
import static com.seqwawa.seq.ui.theme.UiColor.*;

import java.awt.Color;
import com.seqwawa.seq.managers.AssetManager;
import com.seqwawa.seq.utils.rendering.UiCanvas;
import com.seqwawa.seq.utils.rendering.UiRenderer;

/** Shared navigation and search conventions, matching Party Finder. */
public final class SequoiaUiStyle {
    public static final float HEADER_HEIGHT = 30;
    public static final float CONTENT_PADDING = 8;
    static final float HEADER_CONTROL_HEIGHT = DropdownMenu.CONTROL_HEIGHT;
    private static final float SEARCH_BAR_WIDTH = 140;

    private SequoiaUiStyle() {}

    static void drawSidebarTitle(UiCanvas canvas, String font, float sidebarWidth) {
        drawSidebarTitle(canvas, font, sidebarWidth, "Sequoia", 16, color(ACCENT_PRIMARY));
    }

    static void drawSidebarTitle(
            UiCanvas canvas, String font, float sidebarWidth, String title, float fontSize, Color textColor) {
        var icon = AssetManager.getAssetsMap().get("icon");
        boolean hasIcon = icon != null && icon.getImage() != null;
        float iconHeight = 24;
        float iconWidth = hasIcon ? iconHeight * icon.getWidth() / icon.getHeight() : 0;
        float gap = hasIcon ? 6 : 0;
        float textWidth = UiRenderer.measureText(title, font, fontSize).width();
        float startX = (sidebarWidth - iconWidth - gap - textWidth) / 2f;
        if (hasIcon) {
            canvas.drawImage(icon.getImage(), startX, 22 - iconHeight / 2f, iconWidth, iconHeight, 1f);
        }
        canvas.drawText(title, startX + iconWidth + gap, 22, new UiCanvas.TextStyle(
                font, fontSize, textColor, UiCanvas.HorizontalAlign.LEFT, UiCanvas.VerticalAlign.MIDDLE));
    }

    /** Uses the same overlay, body, and header layers as Party Finder. */
    static void drawPanelFrame(UiCanvas canvas, float headerHeight) {
        float x = SequoiaSidebarNavigation.WIDTH;
        float width = Math.max(0, canvas.metrics().width() - x);
        canvas.fillRect(0, 0, canvas.metrics().width(), canvas.metrics().height(), color(BACKGROUND_OVERLAY));
        canvas.fillRect(x, 0, width, canvas.metrics().height(), color(BACKGROUND_BODY));
        canvas.fillRect(x, 0, width, headerHeight, color(BACKGROUND_HEADER));
    }

    static float searchWidth(float availableWidth) {
        return Math.max(0, Math.min(SEARCH_BAR_WIDTH, availableWidth));
    }

    static Color sidebarButtonColor(boolean active, boolean hovered) {
        return color(active ? ACCENT_PRIMARY_DARK : hovered ? BACKGROUND_CONTENT_FOCUSED : BACKGROUND_CONTENT);
    }
}
