package com.seqwawa.seq.images;

import com.seqwawa.seq.config.Setting;

public final class ImagePreviewSettings {
    private static final Setting.BooleanSetting MEDIA = new Setting.BooleanSetting("show_media_links", "chat", true);
    private static final Setting.BooleanSetting ENABLED = new Setting.BooleanSetting("image_previews", "chat", false);

    static {
        MEDIA.setPresentation(
                "Show media links",
                "Show images, GIFs and media links from Discord. Turn off to hide media while keeping accompanying text.",
                "Discord chat");
        ENABLED.setParentSetting(MEDIA);
        ENABLED.setPresentation(
                "Show image and GIF previews",
                "Preview supported chat links on hover. The media provider can see your IP address.",
                "Discord chat");
    }

    private ImagePreviewSettings() {}

    public static Setting.BooleanSetting setting() {
        return ENABLED;
    }

    public static Setting.BooleanSetting mediaSetting() {
        return MEDIA;
    }

    public static boolean hidden() {
        return !MEDIA.getValue();
    }

    public static boolean enabled() {
        return ENABLED.getValue() && ENABLED.isEnabled();
    }
}
