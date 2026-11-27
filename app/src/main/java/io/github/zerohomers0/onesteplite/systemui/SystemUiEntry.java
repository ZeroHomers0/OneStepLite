package io.github.zerohomers0.onesteplite;

import static io.github.zerohomers0.onesteplite.HostEntry.log;

import static io.github.zerohomers0.onesteplite.HostEntry.sContext;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Executor;

/** Installs SystemUI hooks and starts the workspace triggers after Shell is ready. */
final class SystemUiEntry {
    private SystemUiEntry() {}

    private static final long LAUNCH_DELAY_MS = 12_000L;
    private static volatile Object sTaskViewFactory;
    private static volatile Object sTaskViewTransitions;
    private static volatile Executor sShellExecutor;
    private static volatile boolean sStarted;

    static void install(ClassLoader cl) {
        log("=== attached to " + Packages.SYSTEM_UI + " " + HostEntry.RUNTIME_MARKER);
        try {
            NativeMultiWindowUnlock.install(cl, false);
            NativeRecents.install(cl);
            IslandMonitorInput.install(cl, () -> sContext);
            StatusBarSampling.install(cl);
            StatusBar.install(cl);
            TopNativeCaption.install(cl);
            PanelInput.install(cl);
            BiometricWindow.install(cl);
            MainUiConfiguration.install(cl);
            NotificationLaunch.install(cl, () -> sContext);
            installRetainedWorkspaceWakeHooks(cl);
            NativeFreeformCoordinates.install(cl);
            NativeFreeformPreview.install(cl);
            hookApplication(cl);
            hookShellProviders(cl);
            hookRecentsWallpaper(cl);
            hookBackGestureDiagnostics(cl);
            hookTransitionBounds(cl);
        } catch (Throwable t) {
            log("hooks failed: " + t);
        }
    }

    private static void installMemoryCallbacks(Context context) {
        try {
            context.registerComponentCallbacks(new android.content.ComponentCallbacks2() {
                @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {}
                @Override public void onLowMemory() {
                    Warmup.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
                    WorkspaceMemoryController.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
                }
                @Override public void onTrimMemory(int level) {
                    Warmup.onTrimMemory(level);
                    WorkspaceMemoryController.onTrimMemory(level);
                }
            });
        } catch (Throwable unavailable) {
            log("memory callbacks unavailable: " + unavailable);
        }
    }

    private static void hookApplication(ClassLoader cl) {
        SystemUiHooks.install(cl, context -> {
            sContext=context;Warmup.start(context,cl);installMemoryCallbacks(context);
            log("captured Context -> " + context);scheduleStart();
        }, HostEntry::log);
    }

    private static void hookBackGestureDiagnostics(ClassLoader cl) {
        new BackGestureDiagnostics(() -> HostEntry.sHostZoomed,HostEntry::log).install(cl);
    }

    /**
     * MIUI computes the app open/close animation rectangle before SurfaceControl playback.
     * Keep that temporary visual rectangle inside OneStepLite's main pane; task bounds and the
     * normal full-screen launcher animation are left untouched.
     */
    private static void hookTransitionBounds(ClassLoader cl) {
        new WorkspaceWindowHooks(HostEntry::log).hookTransitionBounds(cl);
    }

    private static void hookRecentsWallpaper(ClassLoader cl) {
        new WorkspaceWindowHooks(HostEntry::log).hookRecentsWallpaper(cl);
    }

    private static void hookShellProviders(ClassLoader cl) {
        ShellProviderHooks.install(cl, new ShellProviderHooks.State() {
            @Override public void rootTaskDisplayArea(Object value) {
                ShellCompat.rootTaskDisplayArea=value;
            }
            @Override public void taskViewTransitions(Object value) {
                sTaskViewTransitions=value;
            }
            @Override public void shellExecutor(Executor value) {
                sShellExecutor=value;log("captured ShellExecutor -> " + value);
            }
            @Override public void taskViewFactory(Object value) {
                sTaskViewFactory=value;log("captured TaskViewFactory -> " + value);
            }
        }, HostEntry::log);
    }

    private static void scheduleStart() {
        if (sStarted) {
            return;
        }
        sStarted = true;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                // 紧急停止阀：只要这个文件存在就什么都不做。
                if (new java.io.File("/data/system/onesteplite-halt").exists()) {
                    log("halt marker present -> skipping test");
                    return;
                }
                log("capabilities: factory=" + sTaskViewFactory
                        + " executor=" + sShellExecutor + " transitions=" + sTaskViewTransitions);
                if (sTaskViewFactory == null || sShellExecutor == null || sTaskViewTransitions == null) {
                    log("missing capability, abort");
                    return;
                }
                // v1.2.1：不再用一次性自锁挡触发器（重启后"呼不出/退不出"的根源）。
                // 触发器本身是热区+广播，安全；真正危险的是早期自动启动应用，已删除。
                installTriggers();
            }
        }, LAUNCH_DELAY_MS);
    }

    private static void installRetainedWorkspaceWakeHooks(ClassLoader cl) {
        RetainedWorkspaceHooks.install(cl, HostEntry::log);
    }

    private static void installTriggers() {
        TriggerHooks.install(sContext, sShellExecutor, sTaskViewFactory,
                sTaskViewTransitions, HostEntry::log);
    }
}
