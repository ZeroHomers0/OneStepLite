package io.github.zerohomers0.onesteplite;

public final class TopTaskReturnPolicyTest {
    public static void main(String[] args) {
        check(TopTaskReturnPolicy.keepUpperTask(true));
        check(!TopTaskReturnPolicy.keepUpperTask(false));
        check(!TopTaskReturnPolicy.liveCenterReveal(true, true, true));
        check(!TopTaskReturnPolicy.liveCenterReveal(false, false, true));
        check(!TopTaskReturnPolicy.liveCenterReveal(false, true, false));
        check(TopTaskReturnPolicy.liveCenterReveal(false, true, true));
        System.out.println("TopTaskReturnPolicy: 6 checks passed");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("top return policy changed");
    }
}
