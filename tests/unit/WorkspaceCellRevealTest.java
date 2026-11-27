package io.github.zerohomers0.onesteplite;

public final class WorkspaceCellRevealTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for (boolean right : new boolean[]{false, true}) {
            WorkspaceLayout layout = WorkspaceLayout.of(1200, 2608, right, 3f);
            for (int step = 0; step <= 100; step++) {
                float p = step / 100f, scale = 1f + (layout.mainScale - 1f) * p;
                float ml = layout.mainOffsetX * p, mt = layout.mainOffsetY * p;
                float mr = ml + 1200 * scale, mb = mt + 2608 * scale;
                int previousWidth = -1;
                for (int row = 0; row < layout.cellCount; row++) {
                    int[] r = layout.cellBounds(row);
                    int[] c = WorkspaceCellReveal.crop(r[0], r[1], r[2], r[3], layout.topStrip,
                            2608, false, right, p < 1, ml, mt, mr, mb);
                    check(c[0] >= 0 && c[2] >= c[0] && c[2] <= r[2] - r[0], "valid horizontal crop");
                    check(previousWidth < 0 || previousWidth == c[2] - c[0], "all rows reveal together");
                    previousWidth = c[2] - c[0];
                    if (step == 0) check(c[2] == c[0], "no task before main starts shrinking");
                    if (step == 100) check(c[0] == 0 && c[2] == r[2] - r[0], "settled task restores full crop");
                    if (c[2] > c[0] && c[3] > c[1])
                        check(r[0]+c[2] <= ml || r[0]+c[0] >= mr || r[1]+c[3] <= mt
                                || r[1]+c[1] >= mb, "live cell never overlaps moving main");
                }
            }
        }
        int[] scrolled = WorkspaceCellReveal.crop(0, 450, 300, 1100, 600, 2600,
                false, true, false, 300, 600, 1200, 2600);
        check(scrolled[1] == 150 && scrolled[3] == 650, "scroll clipping survives transition cleanup");
        int[] top = WorkspaceCellReveal.crop(0, 90, 400, 500, 0, 2000,
                true, true, true, 200, 250, 1200, 2000);
        check(top[3] == 160, "upper task stays above the main's moving top edge");
        System.out.println("WorkspaceCellReveal: " + checks + " checks passed");
    }
}
