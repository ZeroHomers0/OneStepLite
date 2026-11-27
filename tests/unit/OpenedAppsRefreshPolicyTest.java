package io.github.zerohomers0.onesteplite;

public final class OpenedAppsRefreshPolicyTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        check(OpenedAppsRefreshPolicy.delay(10_000L, 0L) == 100L, "first refresh coalesces a task burst");
        check(OpenedAppsRefreshPolicy.delay(10_000L, 9_500L) == 500L, "refresh waits for the one-second floor");
        check(OpenedAppsRefreshPolicy.delay(10_090L, 9_100L) == 100L, "minimum delay stays bounded");
        check(OpenedAppsRefreshPolicy.delay(11_000L, 10_000L) == 100L, "expired interval only coalesces");
        check(OpenedAppsRefreshPolicy.delay(9_000L, 10_000L) == 100L, "clock reset never blocks refresh");
        System.out.println("OpenedAppsRefreshPolicy checks=" + checks + " passed");
    }
}
