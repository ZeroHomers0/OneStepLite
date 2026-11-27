package io.github.zerohomers0.onesteplite;

import java.util.LinkedHashSet;

/** One automatic action per fullscreen episode, scoped to the real task token and user. */
final class WideVideoPolicy {
    private final LinkedHashSet<TopTaskState.Key> handled = new LinkedHashSet<>();
    void clear() { handled.clear(); }

    void observe(TopTaskState.Key key, boolean landscape, boolean fullscreen, int windowMode) {
        // Moving a player into TOP changes its configuration; that is not a new episode.
        if (windowMode == 1 && (!landscape || !fullscreen)) handled.remove(key);
    }

    boolean eligible(TopTaskState.Key key, boolean landscape, boolean fullscreen,
            boolean focused, int windowMode) {
        return key != null && landscape && fullscreen && focused && windowMode == 1
                && !handled.contains(key);
    }

    void handled(TopTaskState.Key key) {
        handled.add(key);
        while (handled.size() > 64) handled.remove(handled.iterator().next());
    }

    static boolean fresh(long now, long issued) {
        return issued > 0 && now >= issued && now - issued <= 2500;
    }
}
