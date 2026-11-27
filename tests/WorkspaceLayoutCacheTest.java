package io.github.zerohomers0.onesteplite;

public final class WorkspaceLayoutCacheTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        WorkspaceLayoutCache cache = new WorkspaceLayoutCache();
        WorkspaceLayout first = cache.of(1080, 2400, false, 2.75f, false);
        check(first == cache.of(1080, 2400, false, 2.75f, false), "equal viewport reuses layout");
        check(first != cache.of(1080, 2400, true, 2.75f, false), "side changes use a distinct layout");
        check(first != cache.of(1080, 2400, false, 3f, false), "density changes use a distinct layout");
        for (int i = 0; i < 20; i++) cache.of(1000 + i, 2400, false, 2.75f, false);
        check(cache.size() <= 8, "layout cache remains bounded");
        System.out.println("WorkspaceLayoutCache checks=" + checks + " passed");
    }
}
