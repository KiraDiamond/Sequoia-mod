package com.seqwawa.seq.images;

import com.seqwawa.seq.config.Setting;

public final class ImagePreviewSettings {
    private static final Setting.BooleanSetting ENABLED = new Setting.BooleanSetting("image_previews", "chat", true);

    static {
        ENABLED.setPresentation(
                "Show image and GIF previews",
                "Preview supported chat links on hover. The media provider can see your IP address.",
                "Discord chat");
    }

    private ImagePreviewSettings() {}

    public static Setting.BooleanSetting setting() {
        return ENABLED;
    }

    public static boolean enabled() {
        return ENABLED.getValue() && ENABLED.isEnabled();
    }
}
