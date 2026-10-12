package com.seqwawa.seq.utils.rendering;

/** Immutable conversion between framebuffer, Minecraft GUI, and Sequoia UI coordinates. */
public record UiRenderMetrics(
        int framebufferWidth,
        int framebufferHeight,
        double minecraftGuiScale,
        float uiScale) {
    public static final float BASE_PIXEL_RATIO = 2f;
    public static final float RENDER_QUALITY_MULTIPLIER = 1.5f;
    public static final float MAX_OVERSAMPLED_PIXEL_RATIO = 4f;
    public static final float MIN_UI_SCALE = 0.1f;
    public static final float MAX_UI_SCALE = 10f;

    public UiRenderMetrics {
        framebufferWidth = Math.max(1, framebufferWidth);
        framebufferHeight = Math.max(1, framebufferHeight);
        minecraftGuiScale = positiveOrDefault(minecraftGuiScale, 1.0);
        uiScale = clampUiScale(uiScale);
    }

    public float pixelRatio() {
        return BASE_PIXEL_RATIO * uiScale;
    }

    /** NanoVG raster density, kept separate so higher quality does not alter layout or input coordinates. */
    public float renderPixelRatio() {
        float layoutPixelRatio = pixelRatio();
        float oversampledPixelRatio = Math.min(
                layoutPixelRatio * RENDER_QUALITY_MULTIPLIER,
                MAX_OVERSAMPLED_PIXEL_RATIO);
        return Math.max(layoutPixelRatio, oversampledPixelRatio);
    }

    /** Minecraft GUI units per Sequoia UI unit, for Minecraft drawing placed by a UI layout. */
    public float guiUnitsPerUiUnit() {
        return pixelRatio() / (float) minecraftGuiScale;
    }

    public float width() {
        return framebufferWidth / pixelRatio();
    }

    public float height() {
        return framebufferHeight / pixelRatio();
    }

    public float mouseX(double rawX) {
        return (float) (rawX * minecraftGuiScale / pixelRatio());
    }

    public float mouseY(double rawY) {
        return (float) (rawY * minecraftGuiScale / pixelRatio());
    }

    public double mouseDelta(double rawDelta) {
        return rawDelta * minecraftGuiScale / pixelRatio();
    }

    public static float clampUiScale(float value) {
        if (!Float.isFinite(value)) {
            return 1f;
        }
        return Math.max(MIN_UI_SCALE, Math.min(MAX_UI_SCALE, value));
    }

    private static double positiveOrDefault(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }
}
