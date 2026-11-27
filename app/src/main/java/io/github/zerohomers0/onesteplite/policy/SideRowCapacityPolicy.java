package io.github.zerohomers0.onesteplite;

/** Bounds retained side rows before an empty add row would grow without limit. */
final class SideRowCapacityPolicy {
    static final int MAX_ROWS = 12;

    private SideRowCapacityPolicy() {}

    static boolean canAppend(int rows) {
        return rows >= 0 && rows < MAX_ROWS;
    }
}
