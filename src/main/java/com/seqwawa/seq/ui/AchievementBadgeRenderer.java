package com.seqwawa.seq.ui;

import static com.seqwawa.seq.managers.ThemeManager.color;
import static com.seqwawa.seq.ui.theme.UiColor.*;

import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.managers.AssetManager;
import com.seqwawa.seq.utils.rendering.UiCanvas;
import java.util.Locale;

/** Uses bundled artwork, with the Sequoia icon for categories awaiting their own asset. */
final class AchievementBadgeRenderer {
    private AchievementBadgeRenderer() {}

    static boolean draw(UiCanvas canvas, AchievementsScreen.Row row, float x, float y, float size) {
        String key = assetKey(row);
        AssetManager.Asset asset = SeqClient.assetManager == null ? null : SeqClient.assetManager.getAsset(key);
        boolean fallback = asset == null || asset.getImage() == null;
        if (fallback && SeqClient.assetManager != null) asset = SeqClient.assetManager.getAsset("icon");
        if (asset == null || asset.getImage() == null) return false;

        boolean framed = fallback || "raid".equals(row.kind());
        if (framed) {
            canvas.fillCircle(x + size / 2, y + size / 2, size / 2, color(CONTROL_INPUT));
            canvas.strokeCircle(x + size / 2, y + size / 2, size / 2 - 1, 1.5f,
                    row.unknown() ? color(TEXT_MUTED) : AchievementsScreen.tierColor(row.tier()));
        }
        float available = framed ? size - 8 : size;
        float scale = available / Math.max(asset.getWidth(), asset.getHeight());
        float width = asset.getWidth() * scale;
        float height = asset.getHeight() * scale;
        canvas.drawImage(asset.getImage(), x + (size - width) / 2, y + (size - height) / 2,
                width, height, row.unknown() ? 0.4f : 1f);
        return true;
    }

    static String assetKey(AchievementsScreen.Row row) {
        String key = row.key() == null ? "" : row.key().toLowerCase(Locale.ROOT);
        if (row.icon() != null && ("event".equals(row.kind()) || "badge".equals(row.kind())))
            return localArtworkKey(row.icon());
        if ("badge".equals(row.kind()) && key.startsWith("badge:")) {
            return localArtworkKey(key.substring("badge:".length()));
        }
        if ("role".equals(row.kind()) && "role:insignia".equals(key)
                && !row.unknown() && row.tier() != null) {
            return "badge:insignia_" + row.tier().name().toLowerCase(Locale.ROOT);
        }
        return "raid".equals(row.kind()) ? row.icon() : null;
    }

    private static String localArtworkKey(String value) {
        String key = value.toLowerCase(Locale.ROOT);
        if (key.contains("/")) key = key.substring(key.lastIndexOf('/') + 1);
        if (key.endsWith(".png")) key = key.substring(0, key.length() - 4);
        if (key.startsWith("badge.")) key = key.substring(6);
        if (key.startsWith("badge:")) key = key.substring(6);
        if (key.startsWith("raid.")) key = key.substring(5);
        key = key.replace('.', '_');
        if (key.startsWith("wartorn_palace_")) key = "wtp_" + key.substring(15);
        if (key.startsWith("nexus_of_light_")) key = "nol_" + key.substring(15);
        if (key.startsWith("twp_")) key = "wtp_" + key.substring(4);
        return "badge:" + ("tcc_single".equals(key) ? "tcc" : key);
    }
}
