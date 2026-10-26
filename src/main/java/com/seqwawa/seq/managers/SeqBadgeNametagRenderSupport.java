package com.seqwawa.seq.managers;

import java.util.Collection;
import java.util.List;
import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.model.SeqBadge;
import com.seqwawa.seq.model.SeqBadgeType;

final class SeqBadgeNametagRenderSupport {
    static final float BADGE_WIDTH = 19f;
    static final float BADGE_HEIGHT = 18f;
    static final float BADGE_STEP = 21f;
    static final float WYNNTILS_DEFAULT_BADGE_Y_OFFSET = 25f;
    static final float LOWER_BADGE_Y_OFFSET = 10f;

    private SeqBadgeNametagRenderSupport() {}

    static boolean showAnyBadgeType() {
        return showRaidBadges() || showInsigniaBadges();
    }

    static List<SeqBadge> visibleBadges(Collection<SeqBadge> badges) {
        return SeqBadge.sortForRender(badges.stream()
                .filter(badge -> isBadgeTypeVisible(badge.type()))
                .toList());
    }

    static boolean isBadgeTypeVisible(SeqBadgeType type) {
        return switch (type) {
            case WTP, NOL, TCC -> showRaidBadges();
            case INSIGNIA -> showInsigniaBadges();
        };
    }

    static boolean showRaidBadges() {
        return SeqClient.getShowRaidBadgesSetting() == null
                || SeqClient.getShowRaidBadgesSetting().getValue();
    }

    static boolean showInsigniaBadges() {
        return SeqClient.getShowInsigniaBadgesSetting() == null
                || SeqClient.getShowInsigniaBadgesSetting().getValue();
    }

    static boolean showOwnLeaderboardBadge() {
        return SeqClient.getShowOwnLeaderboardBadgeSetting() == null
                || SeqClient.getShowOwnLeaderboardBadgeSetting().getValue();
    }
}
