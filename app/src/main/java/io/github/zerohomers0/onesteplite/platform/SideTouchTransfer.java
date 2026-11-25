package io.github.zerohomers0.onesteplite;

import android.content.Intent;
import android.view.WindowManager;
import de.robv.android.xposed.XposedHelpers;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Transfers an existing physical gesture; InputDispatcher maps task coordinates once. */
final class SideTouchTransfer {
    private SideTouchTransfer() {}

    static void transfer(Intent intent, Supplier<ZoomTarget> zoomTarget,
            Supplier<Float> zoomScale, Consumer<String> log) {
        ZoomTarget target = zoomTarget.get();
        android.os.Bundle tokens = intent.getBundleExtra("tokens");
        if (target == null || tokens == null) return;
        android.os.IBinder hostToken = tokens.getBinder("host");
        android.os.IBinder taskToken = tokens.getBinder("task");
        int taskId = intent.getIntExtra("taskId", -1);
        long eventTime = intent.getLongExtra("eventTime", 0L);
        target.handler.post(() -> {
            long age = android.os.SystemClock.uptimeMillis() - eventTime;
            if (zoomTarget.get() != target || zoomScale.get() >= 1f || age < 0 || age > 500
                    || hostToken == null || taskToken == null || taskId < 0) return;
            try {
                Object from;
                Object to;
                Object input;
                synchronized (target.globalLock) {
                    Object service = XposedHelpers.getObjectField(target.display, "mWmService");
                    java.util.Map<?, ?> windows = (java.util.Map<?, ?>)
                            XposedHelpers.getObjectField(service, "mWindowMap");
                    Object host = windows.get(hostToken);
                    if (host == null) return;
                    WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                            XposedHelpers.getObjectField(host, "mAttrs");
                    if (!"WorkspaceHost".contentEquals(attrs.getTitle())
                            || !Packages.SYSTEM_UI.equals(attrs.packageName)
                            || !Boolean.TRUE.equals(XposedHelpers.callMethod(host, "isVisible"))) return;
                    Class<?> container = XposedHelpers.findClass(
                            "com.android.server.wm.WindowContainer",
                            target.display.getClass().getClassLoader());
                    Object task = XposedHelpers.callStaticMethod(container, "fromBinder", taskToken);
                    if (task == null || XposedHelpers.getIntField(task, "mTaskId") != taskId
                            || !Integer.valueOf(6).equals(
                                    XposedHelpers.callMethod(task, "getWindowingMode"))
                            || XposedHelpers.callMethod(task, "getDisplayContent") != target.display)
                        return;
                    Object[] app = {null};
                    Consumer<Object> pick = window -> {
                        if (app[0] != null) return;
                        WindowManager.LayoutParams wa = (WindowManager.LayoutParams)
                                XposedHelpers.getObjectField(window, "mAttrs");
                        if ((wa.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                                || wa.type == WindowManager.LayoutParams.TYPE_APPLICATION)
                                && Boolean.TRUE.equals(XposedHelpers.callMethod(window, "isVisible"))
                                && XposedHelpers.getObjectField(window, "mInputChannelToken") != null) {
                            app[0] = window;
                        }
                    };
                    XposedHelpers.callMethod(task, "forAllWindows", pick, true);
                    if (app[0] == null) return;
                    from = XposedHelpers.getObjectField(host, "mInputChannelToken");
                    to = XposedHelpers.getObjectField(app[0], "mInputChannelToken");
                    input = XposedHelpers.getObjectField(service, "mInputManager");
                }
                if (from == null || to == null) return;
                Object result;
                try {
                    result = XposedHelpers.callMethod(
                            input, "transferTouchGesture", from, to, true);
                } catch (NoSuchMethodError olderApi) {
                    result = XposedHelpers.callMethod(input, "transferTouchGesture", from, to);
                }
                log.accept("side touch transferred task=" + taskId
                        + " result=" + result + " age=" + age);
            } catch (Throwable error) {
                log.accept("side touch transfer failed task=" + taskId + ": " + error);
            }
        });
    }
}
