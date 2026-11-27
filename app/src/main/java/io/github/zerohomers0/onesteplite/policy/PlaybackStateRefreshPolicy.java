package io.github.zerohomers0.onesteplite;

import java.util.Map;

/** Avoids rebuilding media controls when the observed playback state is unchanged. */
final class PlaybackStateRefreshPolicy {
    private PlaybackStateRefreshPolicy() {}

    static boolean shouldPrepare(boolean known, Map<String,Integer> previous,
            Map<String,Integer> next) {
        return !known || previous == null || next == null || !previous.equals(next);
    }
}
