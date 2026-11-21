package io.github.zerohomers0.onesteplite;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class QuickAppReconcilePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        List<String> installed = Arrays.asList("a", "b", "c");

        check(QuickAppReconcilePolicy.prune(Arrays.asList("c", "a"), installed)
                .equals(Arrays.asList("c", "a")), "saved order is preserved");
        check(QuickAppReconcilePolicy.prune(Arrays.asList("c", "gone", "a"), installed)
                .equals(Arrays.asList("c", "a")), "uninstalled pin is dropped");
        check(QuickAppReconcilePolicy.prune(Arrays.asList("a", "a", "b"), installed)
                .equals(Arrays.asList("a", "b")), "duplicate pins collapse");
        check(QuickAppReconcilePolicy.prune(null, installed).isEmpty(), "missing pins prune to empty");
        check(QuickAppReconcilePolicy.prune(Collections.emptyList(), installed).isEmpty(),
                "empty pins prune to empty");
        check(QuickAppReconcilePolicy.prune(Arrays.asList("a", null, "", "b"), installed)
                .equals(Arrays.asList("a", "b")), "blank pins are skipped");
        check(QuickAppReconcilePolicy.prune(Arrays.asList("a", "b"), null).isEmpty(),
                "no installed apps prunes everything");
        check(QuickAppReconcilePolicy.prune(Arrays.asList("a", "b"), Collections.emptyList()).isEmpty(),
                "empty install list prunes everything");

        check(QuickAppReconcilePolicy.hasStaleKeys(Arrays.asList("a", "gone"), installed),
                "uninstalled pin is stale");
        check(!QuickAppReconcilePolicy.hasStaleKeys(Arrays.asList("a", "b"), installed),
                "installed pins are not stale");
        check(!QuickAppReconcilePolicy.hasStaleKeys(null, installed), "missing pins are not stale");
        check(!QuickAppReconcilePolicy.hasStaleKeys(Collections.emptyList(), installed),
                "empty pins are not stale");
        check(!QuickAppReconcilePolicy.hasStaleKeys(Arrays.asList("a", null, ""), installed),
                "blank pins are not stale");
        check(QuickAppReconcilePolicy.hasStaleKeys(Arrays.asList("a"), Collections.emptyList()),
                "every pin is stale when nothing is installed");

        System.out.println("QuickAppReconcilePolicy: " + checks + " checks passed");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
}
