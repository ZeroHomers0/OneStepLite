package io.github.zerohomers0.onesteplite;

/** Direction metadata for one exact top-task page request. */
record TopDirection(boolean landscape, String page, int request, int mode,
        int viewportOrientation) {}
