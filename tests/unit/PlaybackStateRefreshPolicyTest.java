package io.github.zerohomers0.onesteplite;

import java.util.Map;

public final class PlaybackStateRefreshPolicyTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        Map<String,Integer> playing = Map.of("video", 3);
        check(PlaybackStateRefreshPolicy.shouldPrepare(false, Map.of(), playing), "first observation prepares controls");
        check(!PlaybackStateRefreshPolicy.shouldPrepare(true, playing, Map.of("video", 3)), "identical state avoids redraw");
        check(PlaybackStateRefreshPolicy.shouldPrepare(true, playing, Map.of("video", 2)), "state change refreshes controls");
        System.out.println("PlaybackStateRefreshPolicy checks=" + checks + " passed");
    }
}
