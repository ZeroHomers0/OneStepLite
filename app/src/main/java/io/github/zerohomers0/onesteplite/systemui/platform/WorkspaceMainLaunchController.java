package io.github.zerohomers0.onesteplite;

import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Looper;
import android.os.SystemClock;
import de.robv.android.xposed.XposedHelpers;

/** Routes shortcuts and desktop launches to the main workspace task. */
final class WorkspaceMainLaunchController {
    private final WorkspaceHost host;
    private android.content.BroadcastReceiver desktopIconReceiver;

    WorkspaceMainLaunchController(WorkspaceHost host) {
        this.host = host;
    }

    void promoteSideTaskForDesktopLaunch(String pkg, int launchUser, ComponentName entry) {
        if (pkg == null || entry == null || !pkg.equals(entry.getPackageName()) || host.scene != WorkspaceHost.ACTIVE) return;
        if (!host.slotRegistry.supportsTaskUser(launchUser)) return;
        String[] packages = new String[host.slots.length - 1];
        int[] users = new int[host.slots.length - 1];
        boolean[] occupied = new boolean[host.slots.length - 1];
        for (int i = 1; i < host.slots.length; i++) {
            packages[i - 1] = host.slots[i].pkg;
            occupied[i - 1] = sideTaskMatchesEntry(host.slots[i], launchUser, entry);
            users[i - 1] = launchUser;
        }
        int sideIndex = DesktopIconLaunchPolicy.uniqueSideSlot(pkg, launchUser, packages, users, occupied);
        if (sideIndex < 0) {
            WorkspaceHost.log("desktop launch has no unique side task package=" + pkg);
            return;
        }
        Slot match = host.slots[sideIndex + 1];
        if (match.index == WorkspaceHost.SLOT_TOP) return; // The original launch has already started.
        WorkspaceHost.log("desktop icon selected side task package=" + pkg + " slot=" + match.index
                + " task=" + match.taskId + "; promote existing task");
        host.promotionController.queueSidePromotion(match, true);
    }

    void launchQuick(QuickApp entry) {
        if (entry == null || entry.component == null || host.scene != WorkspaceHost.ACTIVE
                || host.promotion != null || host.transferBusy || !host.screenOn || host.keyguardLocked) return;
        for (Slot slot : host.slots) {
            if (slot.index > WorkspaceHost.SLOT_MAIN && sideTaskMatchesEntry(slot, host.slotRegistry.currentUserId(), entry.component)) {
                WorkspaceHost.log("top shortcut promotes existing side task slot=" + slot.index);
                host.mainTaskActions.onSlotTapped(slot.index);
                return;
            }
        }
        if(parkMainForQuickLaunch(entry))return;
        launchQuickInMain(entry);
    }

    void shareContentInMain(ComponentName target, Runnable launch) {
        if (host.scene != WorkspaceHost.ACTIVE || host.promotion != null || host.transferBusy || !host.screenOn || host.keyguardLocked) return;
        final long epoch = host.sceneEpoch;
        final int user = host.slotRegistry.currentUserId();
        Runnable guarded = () -> {
            if (host.scene == WorkspaceHost.ACTIVE && host.sceneEpoch == epoch && host.screenOn && !host.keyguardLocked
                    && host.slotRegistry.currentUserId() == user) launch.run();
        };
        for (Slot slot : host.slots) {
            if (slot.index <= WorkspaceHost.SLOT_MAIN || !slot.occupied || !target.getPackageName().equals(slot.pkg)) continue;
            if (!promoteForNotification(target.getPackageName(), user, guarded))
                android.widget.Toast.makeText(host.ctx, "目标小窗暂时无法交接", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        guarded.run();
    }

    private boolean parkMainForQuickLaunch(QuickApp entry) {
        if(!host.workspaceOptions.parkMainOnLaunch || host.topTransition!=null
                || host.cells.motion()!=null || host.workspaceEntering || host.carrierSlotIndex>=0)return false;
        for(Slot slot:host.slots)if(slot.launching || slot.promoting || slot.closing)return false;
        for(int index=1;index<host.CELL_SLOT_COUNT;index++) {
            Slot slot=host.slots[index];
            if(slot.occupied || slot.parked || slot.frozen || slot.controller==null)continue;
            final int user=host.slotRegistry.currentUserId();final long epoch=host.sceneEpoch;
            host.mainTaskActions.captureMainIntoEmptySlot(slot,entry.component,()->{
                if(host.scene==WorkspaceHost.ACTIVE && host.sceneEpoch==epoch && host.screenOn && !host.keyguardLocked
                        && !host.suspendRequested && host.slotRegistry.currentUserId()==user)launchQuickInMain(entry);
            });
            return true;
        }
        return false;
    }

    private void launchQuickInMain(QuickApp entry) {
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(entry.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            host.ctx.startActivity(launch);
            WorkspaceHost.log("top shortcut launch main " + entry.component.flattenToShortString());
            host.carriers.scheduleMainCropClear(1400L);
        } catch (Throwable error) {
            WorkspaceHost.log("top shortcut launch failed: " + error);
            android.widget.Toast.makeText(host.ctx, "无法打开 " + entry.label,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    void openSettingsInMain() {
        if (host.scene != WorkspaceHost.ACTIVE || host.promotion != null || host.transferBusy) return;
        for (Slot slot : host.slots) {
            if (slot.index > WorkspaceHost.SLOT_MAIN && sideTaskMatchesUser(slot, host.slotRegistry.currentUserId())
                    && "com.android.settings".equals(slot.pkg)) {
                host.mainTaskActions.onSlotTapped(slot.index);
                return;
            }
        }
        try {
            Intent settings = new Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            host.ctx.startActivity(settings);
            WorkspaceHost.log("system settings launched in main");
        } catch (Throwable error) {
            WorkspaceHost.log("system settings launch failed: " + error);
            android.widget.Toast.makeText(host.ctx, "无法打开系统设置",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    void preannounceDesktopLaunch(android.os.Bundle binding) {
        if (binding == null || !host.screenOn || host.keyguardLocked) return;
        final boolean hostRoute = binding.getBoolean("hostRoute", false);
        final boolean registeredAsRetained = binding.getBoolean("retainedRoute", false);
        final long registeredScene = binding.getLong("scene", -1L);
        final boolean retainedRoute = hostRoute && host.retainedOnExit && (host.scene == WorkspaceHost.SUSPENDING || host.scene == WorkspaceHost.SUSPENDED)
                && registeredScene >= 0 && registeredScene == (registeredAsRetained ? host.sceneEpoch : host.retainedExitSourceEpoch);
        if (!retainedRoute && (host.scene != WorkspaceHost.ACTIVE || registeredAsRetained || registeredScene != host.sceneEpoch)) return;
        int taskId = binding.getInt("taskId", -1);
        android.os.IBinder token = binding.getBinder("task");
        for (Slot slot : host.slots) {
            if (!host.slotRegistry.isActive(slot) || !slot.occupied || slot.parked || slot.frozen || slot.closing || slot.nativeReleasePending
                    || slot.taskId != taskId || slot.taskToken == null
                    || slot.taskEpoch != binding.getLong("binding", -1L)
                    || !java.util.Objects.equals(slot.pkg, binding.getString("package"))) continue;
            try {
                if (!java.util.Objects.equals(token, XposedHelpers.callMethod(slot.taskToken, "asBinder"))) return;
                if (hostRoute) {
                    if (SystemClock.uptimeMillis() > binding.getLong("deadline", 0L)) {
                        WorkspaceHost.log("desktop host route expired without changing task=" + taskId); return;
                    }
                    if (host.scene == WorkspaceHost.SUSPENDING || host.transferBusy || host.promotion != null || slot.promoting || slot.launching
                            || host.sideRows.isCompacting() || host.sideRows.isDesktopRevealing() || host.sideDismiss != null) {
                        host.main.postDelayed(() -> preannounceDesktopLaunch(binding), 32L);
                    } else if (retainedRoute) {
                        WorkspaceHost.log("desktop launch preannounced retained task=" + taskId + " slot=" + slot.index);
                        host.exits.releaseRetainedTaskToMain(slot);
                    } else if (slot.windowMode == 6 && host.scales.mayWriteSideSurface(slot)) {
                        if (host.sideRows.isScrolling() && slot.sideRow >= 0 && !host.sideRows.isRowInPlace(slot)) {
                            host.sideRows.revealForDesktop(slot, binding);
                            return;
                        }
                        WorkspaceHost.log("desktop host route uses real promotion task=" + taskId);
                        host.mainTaskActions.onSlotTapped(slot.index);
                    }
                } else host.promotionController.queueSidePromotion(slot, true);
            } catch (Throwable unavailable) { WorkspaceHost.log("desktop preannouncement rejected: " + unavailable); }
            return;
        }
    }

    boolean sideTaskMatchesUser(Slot slot, int user) {
        if (user < 0 || slot == null || !slot.occupied || slot.parked || slot.frozen
                || slot.controller == null || slot.taskToken == null) return false;
        try {
            Object info = XposedHelpers.getObjectField(slot.controller, "mTaskInfo");
            return host.slotRegistry.controllerOwns(slot, slot.taskId, slot.taskToken)
                    && XposedHelpers.getIntField(info, "userId") == user;
        } catch (Throwable unavailable) { return false; }
    }

    boolean sideTaskMatchesEntry(Slot slot, int user, ComponentName entry) {
        if (!sideTaskMatchesUser(slot, user)) return false;
        try {
            Object info = XposedHelpers.getObjectField(slot.controller, "mTaskInfo");
            return TaskLaunchIdentity.matches(host.ctx.getPackageManager(), entry, info);
        } catch (Throwable unavailable) { return false; }
    }

    static boolean promoteForNotification(String pkg, int userId, Runnable dispatch) {
        WorkspaceHost h = WorkspaceHost.currentInstance();
        if (h == null || dispatch == null || pkg == null || Looper.myLooper() != h.main.getLooper()
                || h.scene != WorkspaceHost.ACTIVE || !h.screenOn || h.keyguardLocked
                || !h.slotRegistry.supportsTaskUser(userId) || h.promotion != null || h.transferBusy) return false;
        Slot match = null;
        for (Slot slot : h.slots) {
            if (slot.index <= WorkspaceHost.SLOT_MAIN || slot.index >= h.CELL_SLOT_COUNT || !slot.occupied || slot.parked || slot.frozen
                    || slot.carrier || slot.windowMode != 6 || !pkg.equals(slot.pkg)
                    || !h.scales.mayWriteSideSurface(slot)
                    || !h.slotRegistry.controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
            try {
                Object info = XposedHelpers.getObjectField(slot.controller, "mTaskInfo");
                if (XposedHelpers.getIntField(info, "taskId") != slot.taskId
                        || XposedHelpers.getIntField(info, "userId") != userId) continue;
            } catch (Throwable stale) { continue; }
            if (match != null) return false;
            match = slot;
        }
        if (match == null) return false;
        h.mainTaskActions.swapWithMain(match.index);
        Promotion ticket = h.promotion;
        if (ticket == null || ticket.cancelled || ticket.slot != match) return false;
        ticket.notificationLaunch = dispatch;
        WorkspaceHost.log("notification waits for same-task promotion task=" + ticket.taskId + " package=" + pkg);
        return true;
    }

    void registerDesktopIconReceiver() {
        if (desktopIconReceiver != null) return;
        try {
            desktopIconReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (intent != null && intent.getBooleanExtra("before", false)) {
                        preannounceDesktopLaunch(intent.getBundleExtra("binding"));
                        return;
                    }
                    String pkg = intent == null ? null : intent.getStringExtra("package");
                    promoteSideTaskForDesktopLaunch(pkg,
                            intent == null ? -1 : intent.getIntExtra("userId", -1),
                            intent == null ? null : intent.getParcelableExtra("component", ComponentName.class));
                }
            };
            IntentFilter filter = new IntentFilter(Actions.DESKTOP_ICON_LAUNCH);
            host.ctx.registerReceiver(desktopIconReceiver, filter,
                    "android.permission.MANAGE_ACTIVITY_TASKS", host.main,
                    Context.RECEIVER_EXPORTED);
            WorkspaceHost.log("desktop icon launch receiver registered");
        } catch (Throwable error) {
            desktopIconReceiver = null;
            WorkspaceHost.log("desktop icon launch receiver failed: " + error);
        }
    }
}
