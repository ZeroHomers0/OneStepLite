package io.github.zerohomers0.onesteplite;

public final class WorkspaceRevealCurveTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(WorkspaceRevealCurve.top(0f) == 0f, "top strip starts fully covered");
        check(WorkspaceRevealCurve.top(1f) == 1f, "top strip ends fully revealed");
        check(WorkspaceRevealCurve.side(0f) == 0f, "side rail starts fully covered");
        check(WorkspaceRevealCurve.side(1f) == 1f, "side rail ends fully revealed");
        // The bar must be uncovered before the receding edge reaches it, the cells after.
        for (int step = 1; step < 20; step++) {
            float progress = step / 20f;
            check(WorkspaceRevealCurve.top(progress) > progress,
                    "top strip leads the main edge at " + progress);
            check(WorkspaceRevealCurve.side(progress) < progress,
                    "side rail trails the main edge at " + progress);
        }
        // Both curves are monotonic so a reversed exit animation never jumps back.
        float previousTop = 0f;
        float previousSide = 0f;
        for (int step = 0; step <= 100; step++) {
            float progress = step / 100f;
            float top = WorkspaceRevealCurve.top(progress);
            float side = WorkspaceRevealCurve.side(progress);
            check(top >= previousTop && side >= previousSide, "reveal curves never reverse");
            previousTop = top;
            previousSide = side;
        }
        // Out-of-range progress is clamped instead of overshooting the layout.
        check(WorkspaceRevealCurve.top(-1f) == 0f && WorkspaceRevealCurve.side(-1f) == 0f,
                "negative progress clamps to covered");
        check(WorkspaceRevealCurve.top(2f) == 1f && WorkspaceRevealCurve.side(2f) == 1f,
                "overshooting progress clamps to revealed");
        // Exiting, the main edge advances and the cells are live task surfaces:
        // a trailing crop would cut them away before the application covers them.
        check(WorkspaceRevealCurve.side(0f, true) == 0f && WorkspaceRevealCurve.side(1f, true) == 1f,
                "exit side rail spans the same endpoints");
        for (int step = 1; step < 20; step++) {
            float progress = step / 20f;
            check(WorkspaceRevealCurve.side(progress, true) > progress,
                    "exit side rail leads the advancing main at " + progress);
            check(WorkspaceRevealCurve.side(progress, true) > WorkspaceRevealCurve.side(progress),
                    "exit side rail runs ahead of its entry curve at " + progress);
            check(WorkspaceRevealCurve.top(progress) > WorkspaceRevealCurve.side(progress, true),
                    "exit keeps the bar ahead of the cells at " + progress);
        }
        float previousExitSide = 0f;
        for (int step = 0; step <= 100; step++) {
            float side = WorkspaceRevealCurve.side(step / 100f, true);
            check(side >= previousExitSide, "exit side curve never reverses");
            previousExitSide = side;
        }
        check(WorkspaceRevealCurve.side(-1f, true) == 0f, "exit negative progress clamps to covered");
        check(WorkspaceRevealCurve.side(2f, true) == 1f, "exit overshooting progress clamps to revealed");
        System.out.println("WorkspaceRevealCurve: 246 checks passed");
    }
}
