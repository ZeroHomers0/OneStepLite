package io.github.zerohomers0.onesteplite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** SystemUI boot warmup. No host windows, tasks, magnification, activity or service launches. */
final class Warmup {
    private static final Object LOCK = new Object();
    private static final String USER_SWITCHED = "android.intent.action.USER_SWITCHED";
    private static final String PREFS = "workspace_warmup";
    private static final String USED = "workspace_used";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService WORK = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "OneStepLiteBootWarmup");
        thread.setDaemon(true);
        return thread;
    });
    private static final List<PendingApps> pendingApps = new ArrayList<>();
    private static List<QuickApp> apps = Collections.emptyList();
    private static boolean appsLoaded, wallpaperPrepared;
    private static final long APPS_REFRESH_MILLIS = 60L * 1000L;
    private static long appsPreparedAt;
    private static int cacheUser = -1;
    private static long generation;
    private static Bitmap wallpaper,wellWallpaper;
    private static int wallpaperWidth, wallpaperHeight;
    private static boolean started, classesPrepared;
    private static ClassLoader loader;
    private static ScheduledFuture<?> scheduled;
    private static int failuresRemaining = 3;

    private Warmup() {}

    static void prepareTaskViews(Context context, java.util.concurrent.Executor executor, Object factory) {
        TaskViewWarmup.configure(context, executor, factory);
        schedule(context, 800L);
    }

    /** Install at SystemUIService.onCreate; first work waits 12s and for user unlock. */
    static void start(Context context, ClassLoader classLoader) {
        if (context == null) return;
        MAIN.post(() -> {
            synchronized (LOCK) {
                if (started) return;
                started = true;
                loader = classLoader;
            }
            BroadcastReceiver userEvents = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    String action = intent.getAction();
                    if (USER_SWITCHED.equals(action) || Intent.ACTION_WALLPAPER_CHANGED.equals(action))
                        invalidate();
                    if (USER_SWITCHED.equals(action)) MAIN.post(TaskViewWarmup::userChanged);
                    if (USER_SWITCHED.equals(action)) MAIN.post(() -> ChromeWarmup.invalidate(true));
                    if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action))
                        MAIN.post(() -> ChromeWarmup.invalidate(false));
                    schedule(context, 800L);
                }
            };
            IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            userFilter.addAction(Intent.ACTION_USER_PRESENT);
            userFilter.addAction(USER_SWITCHED);
            userFilter.addAction(Intent.ACTION_WALLPAPER_CHANGED);
            userFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            try {
                context.registerReceiver(userEvents, userFilter, Context.RECEIVER_NOT_EXPORTED);
            } catch (Throwable error) { failed("user receiver", error); }
            BroadcastReceiver contentEvents = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    invalidate();
                    schedule(context, 1200L);
                }
            };
            IntentFilter packageFilter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            packageFilter.addDataScheme("package");
            try {
                context.registerReceiver(contentEvents, packageFilter, Context.RECEIVER_NOT_EXPORTED);
            } catch (Throwable error) { failed("package receiver", error); }
            schedule(context, 12000L);
        });
    }

    /** Cache only; a cold open never enumerates packages or loads icons on its UI thread. */
    static List<QuickApp> quickApps() {
        int user = currentUser();
        if (user < 0) return Collections.emptyList();
        synchronized (LOCK) {
            return cacheUser == user && appsLoaded ? apps : Collections.emptyList();
        }
    }

    /** One main-thread delivery. A missing boot cache is populated on the worker. */
    static void whenQuickAppsReady(Context context, Consumer<List<QuickApp>> ready) {
        if (context == null || ready == null) return;
        int user = currentUser();
        if (user < 0) return;
        List<QuickApp> cached;
        synchronized (LOCK) {
            cached = cacheUser == user && appsLoaded
                    && SystemClock.elapsedRealtime() - appsPreparedAt < APPS_REFRESH_MILLIS ? apps : null;
            if (cached == null) {
                if (pendingApps.size() >= 32) pendingApps.remove(0);
                pendingApps.add(new PendingApps(user, ready));
            }
        }
        if (cached != null) deliver(context, new PendingApps(user, ready), cached);
        else {
            boolean firstUse = !usedBefore(context);
            markUsed(context);
            schedule(context, 0L, firstUse);
        }
    }

    /** Nonblocking ownership transfer: no capture, rendering or Binder calls on the opening frame. */
    static Bitmap[] takePreparedWallpaper(int user,int width,int height) {
        synchronized(LOCK) {
            if(cacheUser!=user || wallpaper==null || wallpaper.isRecycled()
                    || wallpaperWidth!=width || wallpaperHeight!=height)return null;
            Bitmap[] ready={wallpaper,wellWallpaper};wallpaper=null;wellWallpaper=null;return ready;
        }
    }

    private static void schedule(Context context, long delayMillis) {
        schedule(context, delayMillis, false);
    }

    private static void schedule(Context context, long delayMillis, boolean firstUseOnly) {
        synchronized (LOCK) {
            if (scheduled != null) scheduled.cancel(false);
            scheduled = WORK.schedule(() -> prepare(context, firstUseOnly), delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private static void prepare(Context base, boolean firstUseOnly) {
        try {
            preloadClasses();
            if (!firstUseOnly && !usedBefore(base)) return;
            boolean prewarmHeavy = !firstUseOnly;
            int user = currentUser();
            if (user < 0) return;
            Context context = userContext(base, user);
            UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (userManager == null || !userManager.isUserUnlocked()) return;
            if (prewarmHeavy) whenUiIdle(TaskViewWarmup::prepare);
            long request;
            boolean needApps, needWallpaper;
            synchronized (LOCK) {
                request = generation;
                needApps = cacheUser != user || !appsLoaded
                        || SystemClock.elapsedRealtime() - appsPreparedAt >= APPS_REFRESH_MILLIS;
                needWallpaper = cacheUser != user || !wallpaperPrepared;
            }
            List<QuickApp> loaded = needApps
                    ? Collections.unmodifiableList(new ArrayList<>(QuickApp.load(context))) : null;
            List<PendingApps> callbacks = new ArrayList<>();
            List<QuickApp> ready;
            int observedUser = currentUser();
            synchronized (LOCK) {
                if (request != generation || user != observedUser) return;
                cacheUser = user;
                if (loaded != null) {
                    apps = loaded;
                    appsLoaded = true;
                    appsPreparedAt = SystemClock.elapsedRealtime();
                }
                ready = apps;
                if (appsLoaded) {
                    for (int i = pendingApps.size() - 1; i >= 0; i--) {
                        PendingApps pending = pendingApps.get(i);
                        if (pending.user == user) callbacks.add(pendingApps.remove(i));
                    }
                }
            }
            // Shortcut delivery never waits for the heavier wallpaper blur.
            for (PendingApps pending : callbacks) deliver(base, pending, ready, request);
            if (prewarmHeavy) prepareChrome(base,user,ready,request);
            Bitmap blurred = null,well=null;
            int width = 0, height = 0;
            if (prewarmHeavy && needWallpaper) {
                WindowManager windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (windows != null) {
                    Point size = new Point();
                    windows.getDefaultDisplay().getRealSize(size);
                    width = size.x;
                    height = size.y;
                    if (width > 0 && height > 0 && (long) width * height <= 5000000L) {
                        blurred = WorkspaceFrost.finish(HyperOsStyle.blurredWallpaper(context, width, height));
                        if(blurred!=null)well=HyperOsStyle.blurredBitmap(blurred,Math.max(1,width/6),Math.max(1,height/6));
                    }
                }
            }
            observedUser = currentUser();
            synchronized (LOCK) {
                if (request != generation || user != observedUser) {
                    if (blurred != null && !blurred.isRecycled()) blurred.recycle();
                    if(well!=null)well.recycle();
                    return;
                }
                if (blurred != null) {
                    if (wallpaper != null && !wallpaper.isRecycled()) wallpaper.recycle();
                    if(wellWallpaper!=null && !wellWallpaper.isRecycled())wellWallpaper.recycle();
                    wallpaper = blurred;wellWallpaper=well;
                    wallpaperPrepared = true;
                    wallpaperWidth = width;
                    wallpaperHeight = height;
                }
            }
        } catch (Throwable error) { failed("background cache", error); }
    }

    static void onTrimMemory(int level) {
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                && level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return;
        TaskViewWarmup.trimForMemory();
        ChromeWarmup.invalidate(false);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            synchronized (LOCK) {
                generation++;
                apps = Collections.emptyList();appsLoaded=false;appsPreparedAt=0L;
                wallpaperPrepared=false;
                if(wallpaper!=null && !wallpaper.isRecycled())wallpaper.recycle();
                if(wellWallpaper!=null && !wellWallpaper.isRecycled())wellWallpaper.recycle();
                wallpaper=null;wellWallpaper=null;
            }
        }
    }

    private static boolean usedBefore(Context context) {
        try {
            return context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(USED, false);
        } catch (Throwable unavailable) { return false; }
    }

    private static void markUsed(Context context) {
        try {
            context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(USED, true).apply();
        } catch (Throwable ignored) { /* Warmup remains optional. */ }
    }

    private static void whenUiIdle(Runnable action) {
        MAIN.post(() -> MAIN.getLooper().getQueue().addIdleHandler(() -> {
            action.run();return false;
        }));
    }

    private static void prepareChrome(Context context,int user,List<QuickApp> apps,long request) {
        if(!ChromeWarmup.needsPreparation())return;
        WorkspaceOptions options=WorkspaceOptions.DEFAULT;
        try {
            options=WorkspaceSettingsStore.decode(context.getContentResolver().call(
                    WorkspaceSettingsStore.URI,WorkspaceSettingsStore.READ,null,null));
        } catch(Throwable unavailable) { failed("header settings",unavailable); }
        final WorkspaceOptions ready=options;
        whenUiIdle(() -> {
            synchronized(LOCK) { if(request!=generation)return; }
            ChromeWarmup.prepare(context,user,apps,ready);
        });
    }

    private static void deliver(Context context, PendingApps pending, List<QuickApp> ready) {
        long request;
        synchronized (LOCK) { request = generation; }
        deliver(context, pending, ready, request);
    }

    private static void deliver(Context context, PendingApps pending, List<QuickApp> ready, long request) {
        MAIN.post(() -> {
            if (pending.user != currentUser()) return;
            boolean stale;
            synchronized (LOCK) { stale = request != generation; }
            if (stale) {
                whenQuickAppsReady(context, pending.callback);
                return;
            }
            try {
                UserManager users = (UserManager) userContext(context, pending.user)
                        .getSystemService(Context.USER_SERVICE);
                if (users != null && users.isUserUnlocked()) pending.callback.accept(ready);
                else synchronized (LOCK) { pendingApps.add(pending); }
            } catch (Throwable error) { failed("shortcut delivery", error); }
        });
    }

    private static void invalidate() {
        MAIN.post(() -> ChromeWarmup.invalidate(false));
        int user = currentUser();
        synchronized (LOCK) {
            generation++;
            apps = Collections.emptyList();
            appsLoaded = false;
            appsPreparedAt = 0L;
            wallpaperPrepared = false;
            cacheUser = -1;
            if (wallpaper != null && !wallpaper.isRecycled()) wallpaper.recycle();
            wallpaper = null;
            if(wellWallpaper!=null && !wellWallpaper.isRecycled())wellWallpaper.recycle();wellWallpaper=null;
            pendingApps.removeIf(pending -> pending.user != user);
        }
    }

    private static void preloadClasses() {
        if (classesPrepared || loader == null) return;
        classesPrepared = true;
        String[] types = {
            "com.android.wm.shell.taskview.TaskView",
            "com.android.wm.shell.taskview.TaskViewTaskController",
            "com.android.wm.shell.taskview.TaskViewTransitions"
        };
        for (String name : types) {
            try { Class.forName(name, false, loader); }
            catch (Throwable ignored) { /* Capability capture remains the authority at open. */ }
        }
    }

    private static int currentUser() { return CurrentUser.id(); }

    private static Context userContext(Context base, int user) {
        if (user < 0) throw new IllegalStateException("current user unavailable");
        try {
            return UserContextCompat.createAsUser(base,user);
        } catch (Throwable unavailable) {
            throw new IllegalStateException("current user context unavailable", unavailable);
        }
    }

    private static void failed(String operation, Throwable error) {
        synchronized (LOCK) {
            if (failuresRemaining-- <= 0) return;
        }
        android.util.Log.i(Tags.PROBE, "boot warmup " + operation + " unavailable: "
                + error.getClass().getSimpleName());
    }

    private static final class PendingApps {
        final int user;
        final Consumer<List<QuickApp>> callback;
        PendingApps(int user, Consumer<List<QuickApp>> callback) {
            this.user = user;
            this.callback = callback;
        }
    }
}
