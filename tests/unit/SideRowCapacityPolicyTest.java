package io.github.zerohomers0.onesteplite;

public final class SideRowCapacityPolicyTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        check(SideRowCapacityPolicy.canAppend(0), "initial rows can grow");
        check(SideRowCapacityPolicy.canAppend(SideRowCapacityPolicy.MAX_ROWS - 1), "last slot may expose one add row");
        check(!SideRowCapacityPolicy.canAppend(SideRowCapacityPolicy.MAX_ROWS), "capacity prevents another row");
        check(!SideRowCapacityPolicy.canAppend(-1), "invalid row count is rejected");
        System.out.println("SideRowCapacityPolicy checks=" + checks + " passed");
    }
}
