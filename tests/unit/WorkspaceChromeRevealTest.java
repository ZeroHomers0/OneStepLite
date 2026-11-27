package io.github.zerohomers0.onesteplite;

public final class WorkspaceChromeRevealTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // Main task has moved right and down: the exposed left corner must
        // reveal a complete control even when it crosses the main's top edge.
        check(WorkspaceChromeReveal.alpha(0, 90, 40, 130, 80, 100, 400, 800, 12) == 1f,
                "uncovered left control stays whole");
        check(WorkspaceChromeReveal.alpha(100, 90, 140, 130, 80, 100, 400, 800, 12) == 0f,
                "partly covered control stays hidden");
        check(WorkspaceChromeReveal.alpha(440, 90, 480, 130, 0, 100, 400, 800, 12) == 1f,
                "mirrored right control stays whole");
        check(WorkspaceChromeReveal.alpha(100, 70, 140, 88, 80, 100, 400, 800, 12) == 1f,
                "control clears vertically");
        float previous = 0f;
        for (int gap = 0; gap <= 12; gap++) {
            float alpha = WorkspaceChromeReveal.alpha(0, 90, 80 - gap, 130,
                    80, 100, 400, 800, 12);
            check(alpha >= previous && alpha <= 1f, "clearance gives a reversible smooth reveal");
            previous = alpha;
        }
        System.out.println("WorkspaceChromeReveal: 17 checks passed");
    }
}
