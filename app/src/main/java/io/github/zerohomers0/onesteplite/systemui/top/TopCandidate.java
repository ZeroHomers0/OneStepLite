package io.github.zerohomers0.onesteplite;

/** One admitted top-task candidate and the exact source binding it came from. */
final class TopCandidate {
    final LiveTask task;
    final TopTaskState.Key key;
    final Slot source;
    final boolean landscape;
    final boolean tryLandscape;
    final boolean navigation;

    TopCandidate(LiveTask task, TopTaskState.Key key, Slot source, boolean landscape,
            boolean tryLandscape, boolean navigation) {
        this.task = task;
        this.key = key;
        this.source = source;
        this.landscape = landscape;
        this.tryLandscape = tryLandscape;
        this.navigation = navigation;
    }
}
