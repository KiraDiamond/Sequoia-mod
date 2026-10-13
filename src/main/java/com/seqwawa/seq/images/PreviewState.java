package com.seqwawa.seq.images;

import java.util.concurrent.TimeUnit;

/** Where one preview is in its lifecycle; each state carries only the data valid in it. */
sealed interface PreviewState {
    String BLOCKED = "Image blocked, unavailable or outside safety limits";
    String BUSY = "Too many images loading; clear previews and try again";

    /** Download and decode are running on a worker. */
    record Loading() implements PreviewState {}

    /** A Discord attachment link waiting for the bridge to deliver its signed URL. */
    record Waiting(long untilNanos) implements PreviewState {
        static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);
    }

    record Ready(MediaFrames frames) implements PreviewState {}

    /** Terminal failure with a message safe to show; internal causes are only logged. */
    record Failed(String message) implements PreviewState {}

    /** The state a finished load moves to when it fails with {@code failure}. */
    static PreviewState failedWith(Throwable failure, long now) {
        return switch (failure) {
            case MediaDownload.PendingMedia pending -> new Waiting(now + Waiting.WAIT_NANOS);
            case MediaDownload.UnresolvedKlipyPage page -> new Failed(page.getMessage());
            case ByteBudget.Exhausted busy -> new Failed(BUSY);
            default -> new Failed(BLOCKED);
        };
    }

    static String status(PreviewState state, long now) {
        return switch (state) {
            case Loading loading -> "Loading image...";
            case Waiting waiting ->
                now - waiting.untilNanos() < 0
                        ? "Waiting for Discord media..."
                        : "Image link unavailable; resend it in Discord";
            case Ready ready -> "Image ready";
            case Failed failed -> failed.message();
        };
    }
}
