package io.github.zerohomers0.onesteplite;

public final class TaskHandoffPolicyTest {
    public static void main(String[] args) {
        Object token = new Object(), controller = new Object(), nativeOwner = new Object();
        check(TaskHandoffPolicy.owner(token, token, controller, controller, nativeOwner)
                == HandoffOwner.OUR_CONTROLLER);
        // WM/Shell's callback won the race; it is still a successful return.
        check(TaskHandoffPolicy.owner(token, null, nativeOwner, controller, nativeOwner)
                == HandoffOwner.NATIVE_FULLSCREEN);
        check(TaskHandoffPolicy.owner(token, new Object(), nativeOwner, controller, nativeOwner)
                == HandoffOwner.UNAVAILABLE);
        check(TaskHandoffPolicy.owner(token, null, new Object(), controller, nativeOwner)
                == HandoffOwner.UNAVAILABLE);
        check(TaskHandoffPolicy.owner(token, token, nativeOwner, controller, nativeOwner)
                == HandoffOwner.UNAVAILABLE);
        check(TaskHandoffPolicy.owner(token, null, controller, controller, nativeOwner)
                == HandoffOwner.UNAVAILABLE);
        check(TaskHandoffPolicy.owner(token, token, controller, controller, controller)
                == HandoffOwner.UNAVAILABLE);
        check(TaskHandoffPolicy.owner(token, token, controller, controller, null)
                == HandoffOwner.UNAVAILABLE);
        System.out.println("TaskHandoffPolicy checks=8 passed");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
