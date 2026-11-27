package io.github.zerohomers0.onesteplite;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Owns side-slot task launch, PendingIntent creation and TaskView listener admission. */
final class WorkspaceLaunchController {
    private static final int SLOT_MAIN = WorkspaceHost.coordinatorSlotMain();
    private static final int SLOT_TOP = WorkspaceHost.coordinatorSlotTop();
    private static final int SLOT_COUNT = WorkspaceHost.coordinatorSlotCount();
    private static final long LAUNCH_TIMEOUT_MS = 4000L;

    private final WorkspaceHost host;
    private Slot launchCurrentSlot;

    WorkspaceLaunchController(WorkspaceHost host) {
        this.host = host;
    }

    void onTaskCreated(Slot slot) {
        if (slot == launchCurrentSlot) launchCurrentSlot = null;
    }

    void launchWhenReady(int slot, String pkg, int attempt) {
        if (slot <= SLOT_MAIN || slot >= SLOT_COUNT) {
            return;
        }
        if (host.slots[slot].view == null && attempt < 8) {
            host.main.postDelayed(() -> launchWhenReady(slot, pkg, attempt + 1), 400L);
            return;
        }
        launchPackage(slot, pkg);
    }

    void launchPackage(int slotIndex, String pkg) {
        if (slotIndex <= SLOT_MAIN || slotIndex >= SLOT_COUNT) {
            WorkspaceHost.log("launchPackage bad slot=" + slotIndex);
            return;
        }
        Intent i = host.ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null && pkg.contains("home")) {
            // 桌面（home）没有普通 LAUNCHER intent，用 CATEGORY_HOME 显式构造。
            i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setPackage(pkg);
            WorkspaceHost.log("launchPackage: using CATEGORY_HOME for " + pkg);
        }
        if (i == null) {
            WorkspaceHost.log("launchPackage: no launcher for " + pkg);
            return;
        }
        launchIntoSlot(slotIndex, i, pkg);
    }

    void launchIntoSlot(final int slotIndex, Intent launch, String label) {
        final Slot slot = host.slots[slotIndex];
        if (!host.isActive(slot) || slot.view == null || slot.launching || slot.promoting || host.promotion != null) {
            WorkspaceHost.log("launchIntoSlot " + slotIndex + ": no view");
            return;
        }
        final String pkg = launch.getComponent() != null
                ? launch.getComponent().getPackageName() : launch.getPackage();
        if (pkg == null || Packages.LAUNCHER.equals(pkg)) return;
        for (Slot other : host.slots) {
            if (other != slot && pkg.equals(other.pkg)
                    && (other.launching || host.sideTaskMatchesEntry(other, host.currentUserId(), launch.getComponent()))) {
                WorkspaceHost.log("launch rejected: application entry already in side slot=" + other.index);
                return;
            }
        }
        if (!host.boundsHooksReady || host.scene != WorkspaceHost.ACTIVE || host.transferBusy) return;
        Runnable choose = () -> chooseExistingOrLaunch(slot, launch, label, pkg);
        if (slot.occupied) {
            host.returnToSystem(slot, false, () -> { host.clearSlot(slot); choose.run(); },
                    error -> WorkspaceHost.log("replacement stopped; previous live task retained: " + error));
        } else choose.run();
    }

    private void chooseExistingOrLaunch(Slot slot, Intent launch, String label, String pkg) {
        if (!host.isActive(slot) || host.scene != WorkspaceHost.ACTIVE) return;
        slot.launching = true;
        if (!host.executeOnShell(() -> {
            try {
                LiveTask existing = host.findPackageTask(pkg, launch.getComponent());
                host.main.post(() -> {
                    if (!host.isActive(slot) || host.scene != WorkspaceHost.ACTIVE) { slot.launching = false; return; }
                    if (existing == null) {
                        startSlot(slot, launch, label);
                    } else {
                        slot.launching = false;
                        host.adoptExisting(slot, existing, host.resumes::finishPendingSuspend,
                                error -> {
                                    WorkspaceHost.log("existing task admission failed; no launcher fallback: " + error);
                                    host.resumes.finishPendingSuspend();
                                });
                    }
                });
            } catch (Throwable error) {
                host.main.post(() -> { slot.launching = false; WorkspaceHost.log("choose task stopped: " + error);
                    host.resumes.finishPendingSuspend(); });
            }
        })) { slot.launching = false; WorkspaceHost.log("no Shell queue for selection"); }
    }

    private void startSlot(final Slot slot, Intent launch, String label) {
        if (!host.isActive(slot) || host.scene != WorkspaceHost.ACTIVE || slot.promoting) { slot.launching = false; return; }
        final int index = slot.index;
        host.resetIdleTaskView(slot);
        host.cellSurfaces.prepareCellSurface(slot);
        slot.modeGuardUntil = SystemClock.uptimeMillis() + 2500L;
        slot.modeRepairAttempts = 0;
        slot.modeAdmissionPending = true;
        slot.pkg = launch.getComponent() != null
                ? launch.getComponent().getPackageName() : launch.getPackage();
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // v0.9：任务一律按"整屏手机"起（1080×2460），完整 UI 不受窗口最小宽限制；
        // 显示时用矩阵缩放塞进格子（官方OneStepLite的等比缩小语义）。
        Rect rect = new Rect(0, 0, host.screenW, host.screenH);
        ActivityOptions creator = ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                        visibleHostBackgroundStartMode());
        PendingIntent pi = PendingIntent.getActivity(host.ctx, 1000 + index, launch,
                PendingIntent.FLAG_IMMUTABLE, creator.toBundle());
        ActivityOptions options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                        visibleHostBackgroundStartMode());
        callHidden(options, "setLaunchWindowingMode", 6);          // WINDOWING_MODE_MULTI_WINDOW
        callHidden(options, "setRemoveWithTaskOrganizer", Boolean.FALSE);
        try {
            Method start = findStartActivity(slot.view.getClass());
            if (start == null) {
                slot.launching = false;
                WorkspaceHost.log("startSlot " + index + ": startActivity not found");
                return;
            }
            start.setAccessible(true);
            start.invoke(slot.view, pi, launch, options, rect);
            launchCurrentSlot = slot;
            host.main.postDelayed(() -> {
                if (launchCurrentSlot == slot) {
                    launchCurrentSlot = null;
                    slot.launching = false;
                    WorkspaceHost.log("launch watchdog fired slot=" + index + " label=" + label);
                    host.resumes.finishPendingSuspend();
                }
            }, LAUNCH_TIMEOUT_MS);
            WorkspaceHost.log("startActivity slot=" + index + " label=" + label
                    + " pkg=" + launch.getPackage() + " rect=" + rect);
        } catch (Throwable t) {
            slot.launching = false;
            WorkspaceHost.log("startSlot " + index + " FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    private static int visibleHostBackgroundStartMode() {
        // API 36 adds ALLOW_IF_VISIBLE = 4. API 35 uses the older explicit opt-in;
        // startSlot requires the visible WorkspaceHost.ACTIVE workspace before creating a PI.
        return Build.VERSION.SDK_INT >= 36 ? 4
                : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
    }

    private static void callHidden(Object target, String name, Object... args) {
        try {
            XposedHelpers.callMethod(target, name, args);
        } catch (Throwable t) {
            WorkspaceHost.log("hidden " + name + " failed: " + t);
        }
    }

    private static Method findStartActivity(Class<?> cls) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod("startActivity",
                        PendingIntent.class, Intent.class, ActivityOptions.class, Rect.class);
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

}
