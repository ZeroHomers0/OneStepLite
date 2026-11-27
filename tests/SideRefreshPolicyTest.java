package io.github.zerohomers0.onesteplite;

public final class SideRefreshPolicyTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        check(SideRefreshPolicy.target(false, false, false) == 30f, "idle side pane is 30fps");
        check(SideRefreshPolicy.target(false, true, false) == 60f, "focused side pane is 60fps");
        check(SideRefreshPolicy.target(false, false, true) == 60f, "transitioning side pane is 60fps");
        check(SideRefreshPolicy.target(true, false, false) == 60f, "TOP always uses 60fps");
        System.out.println("SideRefreshPolicy checks=" + checks + " passed");
    }
}
