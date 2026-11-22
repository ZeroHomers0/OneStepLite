package io.github.zerohomers0.onesteplite;

public final class QuickAppUsageOrderTest {
    static int checks;
    static void check(boolean condition) {
        if (!condition) throw new AssertionError("usage rank " + checks);
        checks++;
    }
    static int compare(long timeA, long lastA, String nameA, String componentA,
            long timeB, long lastB, String nameB, String componentB) {
        return QuickAppUsageOrder.compare(timeA, lastA, nameA, componentA,
                timeB, lastB, nameB, componentB);
    }
    public static void main(String[] args) {
        check(compare(100,3*86400000L,"Z","z",60000,86400000L,"A","a")<0);
        check(compare(60000,3*86400000L,"Z","z",100,3*86400000L+1000,"A","a")<0);
        check(compare(0,4*86400000L,"A","a",100,86400000L,"B","b")>0);
        check(compare(60000, 1, "Z", "z", 100, 99, "A", "a") < 0);
        check(compare(100, 99, "Z", "z", 100, 1, "A", "a") < 0);
        check(compare(0, 99, "A", "a", 0, 1, "B", "b") < 0);
        check(compare(-1, 99, "A", "a", 0, 0, "A", "b") < 0);
        check(compare(Long.MAX_VALUE, 0, "B", "b", 1, 0, "A", "a") < 0);
        check(compare(0, 0, "A", "a", 0, 0, "a", "a") == 0);
        long[] times = {Long.MIN_VALUE, -1, 0, 1, 100, Long.MAX_VALUE};
        for (long a : times) for (long b : times) {
            int ab = compare(a, 5, "A", "a", b, 10, "B", "b");
            int ba = compare(b, 10, "B", "b", a, 5, "A", "a");
            check(Integer.signum(ab) == -Integer.signum(ba));
        }
        System.out.println("QuickAppUsageOrder: " + checks + " checks passed");
    }
}
