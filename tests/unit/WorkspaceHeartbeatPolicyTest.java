package io.github.zerohomers0.onesteplite;

public final class WorkspaceHeartbeatPolicyTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        check(WorkspaceHeartbeatPolicy.heartbeatDelay(true) == 60_000L, "leased heartbeat is slow");
        check(WorkspaceHeartbeatPolicy.heartbeatDelay(false) == 8_000L, "fallback heartbeat is bounded");
        check(WorkspaceHeartbeatPolicy.repairDue(59_999L, 0L, true), "first repair is immediate");
        check(!WorkspaceHeartbeatPolicy.repairDue(60_000L, 30_000L, true), "leased repair waits one minute");
        check(WorkspaceHeartbeatPolicy.repairDue(60_000L, 30_000L, false), "fallback repair remains 30 seconds");
        System.out.println("WorkspaceHeartbeatPolicy checks=" + checks + " passed");
    }
}
