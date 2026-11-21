package io.github.zerohomers0.onesteplite;

/**
 * Where a launch from the top rail should land. The workspace has one main region, so a launch
 * that would replace a running app first parks that app in a free cell instead of dropping it.
 */
final class LaunchPlacementPolicy {
    static final int START_IN_MAIN = 0, START_IN_SIDE_AND_PROMOTE = 1, REPLACE_MAIN = 2;

    private LaunchPlacementPolicy() {}

    static int decide(boolean mainOccupied, int emptySideSlot) {
        if (!mainOccupied) return START_IN_MAIN;
        if (emptySideSlot >= 0) return START_IN_SIDE_AND_PROMOTE;
        return REPLACE_MAIN;
    }
}
