package io.github.zerohomers0.onesteplite;

public final class WorkspaceSceneTrackerTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        check(WorkspaceSceneTracker.allowed(0, 1), "active may suspend");
        check(WorkspaceSceneTracker.allowed(3, 0), "resuming may become active");
        check(!WorkspaceSceneTracker.allowed(2, 1), "suspended cannot jump into suspending");
        WorkspaceSceneTracker tracker = new WorkspaceSceneTracker();
        check(tracker.observe(0) == null, "first observation establishes scene");
        check(tracker.observe(2) != null, "invalid jump is reported");
        check(tracker.snapshot().contains("SUSPENDED"), "snapshot exposes current scene");
        System.out.println("WorkspaceSceneTracker checks=" + checks + " passed");
    }
}
