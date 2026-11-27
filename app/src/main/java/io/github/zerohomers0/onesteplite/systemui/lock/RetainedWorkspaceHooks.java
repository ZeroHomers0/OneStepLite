package io.github.zerohomers0.onesteplite;

import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Native wake/unlock signals used by the retained workspace transition. */
final class RetainedWorkspaceHooks {
    private RetainedWorkspaceHooks() {}

    static void install(ClassLoader loader, Consumer<String> log) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.keyguard.KeyguardLifecyclesDispatcher$KeyguardLifecycleHandler",
                    loader, "handleMessage", android.os.Message.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            int event = ((android.os.Message) param.args[0]).what;
                            // Native turning-on/waking packets precede SCREEN_ON and the first frame.
                            if (event == 0 || event == 4) WorkspaceSystemEvents.onNativeWake();
                        }
                    });
            log.accept("retained workspace native wake hook armed");
        } catch (Throwable unavailable) {
            log.accept("retained workspace native wake hook unavailable: " + unavailable);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager",
                    loader, "hide", long.class, long.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            WorkspaceSystemEvents.onNativeWake();
                        }
                    });
            log.accept("retained workspace native unlock hook armed");
        } catch (Throwable unavailable) {
            log.accept("retained workspace native unlock hook unavailable: " + unavailable);
        }
    }
}
