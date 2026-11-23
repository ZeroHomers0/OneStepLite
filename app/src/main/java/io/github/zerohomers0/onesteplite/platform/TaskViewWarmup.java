package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/** One-shot unattached TaskViews. No app launch, window attachment, render loop or replenishment. */
final class TaskViewWarmup {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<Object> READY = new ArrayDeque<>();
    private static Context base, context;
    private static Executor executor;
    private static Object factory;
    private static int user = -1, generation, pending, attempts;
    private static boolean prepared, claimed;
    private static String configuration;
    private TaskViewWarmup() {}

    static void configure(Context next, Executor callback, Object creator) {
        MAIN.post(() -> {
            if (base != null && factory == creator) return;
            clear();base = next;executor = callback;factory = creator;prepared = false;claimed = false;attempts = 0;
        });
    }
    static void prepare() {
        if (base == null || factory == null || prepared || claimed || pending != 0 || attempts >= 3
                || !EntryGuard.canOpen(base)) return;
        int current = CurrentUser.id();
        if (current < 0) return;
        try {
            if (!claimBootAttempt(current)) { prepared = true;return; }
            clear();user = current;attempts++;prepared = true;
            Display display = ((DisplayManager) base.getSystemService(Context.DISPLAY_SERVICE))
                    .getDisplay(Display.DEFAULT_DISPLAY);
            context = base.createDisplayContext(display).createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            configuration = configuration(context);
            create(generation, 5);
        } catch (Throwable unavailable) { clear();prepared = true; }
    }
    private static void create(int owner, int remaining) {
        if (remaining == 0 || owner != generation) return;
        if (!EntryGuard.canOpen(base) || CurrentUser.id() != user) { clear();prepared = false;return; }
        pending = 1;
        try {
            ShellCompat.createTaskView(factory, context, executor, view -> MAIN.post(() -> {
                if (owner != generation) { release(view);return; }
                pending = 0;
                if (claimed) { release(view);return; }
                if (!EntryGuard.canOpen(base) || CurrentUser.id() != user) {
                    release(view);clear();prepared = false;return;
                }
                READY.add(view);create(owner, remaining - 1);
            }));
        } catch (Throwable unavailable) { pending = 0; }
    }
    static Object take(Context target, Object creator) {
        claimed = true;
        if (factory != creator || user != CurrentUser.id()
                || !configuration(target).equals(configuration)) { clear();return null; }
        return READY.poll();
    }
    static void userChanged() { clear();prepared = false;claimed = false;attempts = 0; }
    private static boolean claimBootAttempt(int current) {
        int boot = android.provider.Settings.Global.getInt(base.getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, -1);
        if (boot < 0) return false;
        android.content.SharedPreferences prefs = base.createDeviceProtectedStorageContext()
                .getSharedPreferences("taskview_warmup", Context.MODE_PRIVATE);
        String key = "attempted_boot_" + current;
        // Claim before native construction: a SystemUI restart must not retry a crashing factory.
        return prefs.getInt(key, -1) != boot && prefs.edit().putInt(key, boot).commit();
    }
    private static String configuration(Context ctx) {
        android.content.res.Configuration config = ctx.getResources().getConfiguration();
        return config.orientation + ":" + config.screenWidthDp + ":" + config.screenHeightDp
                + ":" + config.densityDpi + ":" + config.uiMode + ":" + config.fontScale;
    }
    private static void clear() {
        generation++;pending = 0;
        while (!READY.isEmpty()) release(READY.poll());
    }
    private static void release(Object view) {
        try { ShellCompat.call(view, "release"); }
        catch (Throwable ignored) { /* A never-attached, never-launched view owns no application task. */ }
    }
}
