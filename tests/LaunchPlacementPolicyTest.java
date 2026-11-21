package io.github.zerohomers0.onesteplite;

public final class LaunchPlacementPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(LaunchPlacementPolicy.decide(false, -1) == LaunchPlacementPolicy.START_IN_MAIN,
                "empty main starts in the main region");
        check(LaunchPlacementPolicy.decide(false, 2) == LaunchPlacementPolicy.START_IN_MAIN,
                "empty main ignores a free cell");
        check(LaunchPlacementPolicy.decide(true, 1) == LaunchPlacementPolicy.START_IN_SIDE_AND_PROMOTE,
                "occupied main parks the running app in a free cell");
        check(LaunchPlacementPolicy.decide(true, 0) == LaunchPlacementPolicy.START_IN_SIDE_AND_PROMOTE,
                "first cell is a valid parking spot");
        check(LaunchPlacementPolicy.decide(true, -1) == LaunchPlacementPolicy.REPLACE_MAIN,
                "no free cell replaces the main app");
        check(LaunchPlacementPolicy.decide(true, -2) == LaunchPlacementPolicy.REPLACE_MAIN,
                "negative cell index replaces the main app");
        System.out.println("LaunchPlacementPolicy: " + checks + " checks passed");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
}
