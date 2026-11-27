package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;

/** One side-cell layout origin captured before compaction moves it. */
final class CellMovePlan {
    final Slot slot;
    final Rect from;

    CellMovePlan(Slot slot, Rect from) {
        this.slot = slot;
        this.from = from;
    }
}
