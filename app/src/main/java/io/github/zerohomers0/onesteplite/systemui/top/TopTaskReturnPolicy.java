package io.github.zerohomers0.onesteplite;

/** Decides whether the upper task survives a return and when the live centre reveal runs. */
final class TopTaskReturnPolicy {

    static boolean keepUpperTask(boolean mainOccupiedOrUnknown) {
        return mainOccupiedOrUnknown;
    }

    static boolean liveCenterReveal(boolean mainOccupiedOrUnknown,
            boolean originatedFromMain, boolean originalSlotEmpty) {
        return !mainOccupiedOrUnknown && originatedFromMain && originalSlotEmpty;
    }

    private TopTaskReturnPolicy() {}
}
