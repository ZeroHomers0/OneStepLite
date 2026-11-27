package io.github.zerohomers0.onesteplite;

public final class NavigationPolicyTest {
    private static int checks;

    private static void expect(boolean expected, String reason) {
        checks++;
        boolean actual = NavigationPolicy.shouldSuspend(reason);
        if (actual != expected) {
            throw new AssertionError("reason=" + reason
                    + " expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) {
        expect(true, "homekey");
        expect(false, "recentapps");
        expect(false, "gestureNav");
        expect(false, "back");
        expect(false, null);
        expect(false, "HOMEKEY");
        System.out.println("NavigationPolicy: " + checks + " checks passed");
    }
}
