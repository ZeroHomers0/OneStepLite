package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import android.view.View;
import de.robv.android.xposed.XposedHelpers;
import java.util.function.Consumer;

/** Owns carrier, mirror and leash-POC state for one WorkspaceHost. */
final class WorkspaceCarrierCoordinator {
    private final WorkspaceHost host;
    private final Consumer<String> log;
    private static volatile java.lang.reflect.Constructor<?> surfaceTransactionConstructor;
    private MirrorExperiment mirrorExperiment;
    private LeashExperiment leashExperiment;
    private volatile int leashPocSlot = -1;

    WorkspaceCarrierCoordinator(WorkspaceHost host, Consumer<String> log) {
        this.host = host;
        this.log = log;
    }

    int leashPocSlot() { return leashPocSlot; }


    MirrorExperiment mirrorExperiment() {
        if(mirrorExperiment==null)mirrorExperiment=new MirrorExperiment(host.ctx,host.main,new MirrorExperiment.Host() {
            @Override public int resolveTask(int requested) throws Throwable { return resolveMirrorTask(requested); }
            @Override public Object leash(int task) throws Throwable { return mirrorLeash(task); }
            @Override public View root() { return host.root; }
            @Override public int width() { return host.screenW; }
            @Override public int height() { return host.screenH; }
            @Override public boolean direct(Object leash,Rect destination) { return applyDirectCarrier(leash,destination,true); }
            @Override public void restoreDirect(Object leash) { restoreDirectCarrier(leash); }
        },WorkspaceHost::log);
        return mirrorExperiment;
    }
    void createMirrorLayer() { mirrorExperiment().createMirrorLayer(); }
    void setMirrorLayerVisible(boolean visible) {
        if(!visible && leashExperiment!=null)leashExperiment.close();
        if(mirrorExperiment!=null)mirrorExperiment.setMirrorLayerVisible(visible);
    }
    void startMirrorPoc(int task,Rect destination,int holdMs,int mode) {
        mirrorExperiment().startMirrorPoc(task,destination,holdMs,mode);
    }
    /** The task to mirror: the requested one, or whatever currently owns the main cell. */
    int resolveMirrorTask(int requestedTask) throws Throwable {
        if (requestedTask >= 0) return requestedTask;
        LiveTask main = host.slotRegistry.findMainTask();
        if (main == null) {
            log.accept("mirror POC: no main task");
            android.widget.Toast.makeText(host.ctx, "mirror: 主区无应用",
                    android.widget.Toast.LENGTH_SHORT).show();
            return -1;
        }
        return main.id;
    }

    /** The leash of a task, or null when no controller exposes a ShellTaskOrganizer. */
    Object mirrorLeash(int task) throws Throwable {
        Object organizer = null;
        for (Slot slot : host.slots) {
            if (slot.controller != null) {
                organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
                break;
            }
        }
        if (organizer == null) {
            log.accept("mirror POC: no ShellTaskOrganizer on controllers");
            return null;
        }
        Object leash = ShellCompat.taskLeash(organizer, task);
        if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
            log.accept("mirror POC: leash unavailable task=" + task + " leash=" + leash);
            return null;
        }
        return leash;
    }

    /**
     * 免 mirror 承载：屏幕坐标 = 放大倍率 × 显示内容坐标 + 主区偏移，因此把格子的
     * 屏幕矩形反算成任务 leash 的位置/缩放。原任务窗口仍占满屏、输入通道不变。
     */
    boolean applyDirectCarrier(Object leash, Rect dest, boolean logSuccess) {
        try {
            final float ratio = host.layout.mainScale;
            final float sx = dest.width() / (ratio * host.screenW);
            final float sy = dest.height() / (ratio * host.screenH);
            final float px = (dest.left - host.layout.mainOffsetX) / ratio;
            final float py = (dest.top - host.layout.mainOffsetY) / ratio;
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setPosition", leash, px, py);
                XposedHelpers.callMethod(tx, "setMatrix", leash, sx, 0f, 0f, sy);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            }
            if (logSuccess) {
                log.accept("direct carrier transform ratio=" + ratio + " scale=" + sx + "x" + sy
                        + " pos=" + px + "," + py);
            }
            return true;
        } catch (Throwable error) {
            log.accept("direct carrier failed: " + error);
            return false;
        }
    }

    void restoreDirectCarrier(Object leash) {
        try {
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f);
                XposedHelpers.callMethod(tx, "setMatrix", leash, 1f, 0f, 0f, 1f);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            }
            log.accept("direct carrier restored task to main");
        } catch (Throwable error) {
            log.accept("direct carrier restore failed: " + error);
        }
    }

    // ---------------------------------------------------------------- mirror 承载（fullscreen-only 应用进副格）

    Object shellOrganizer() {
        for (Slot slot : host.slots) {
            if (slot.controller != null) {
                return XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
            }
        }
        return null;
    }

    void removeCarrierBackdrop() {
        View box = host.carrierBackdrop;
        host.carrierBackdrop = null;
        if (box != null && host.root != null) {
            try { host.root.removeView(box); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            host.root.requestLayout();
            host.root.invalidate();
        }
    }

    /** 心跳/系统重排后重新声明“原任务 leash 在格子里的位置/缩放”。 */
    void reassertCarrier() {
        for (Slot slot : host.slots) {
            if (!slot.carrier) continue;
            Object leash = slot.carrierLeash;
            if (leash == null || slot.rect == null) continue;
            try {
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                    log.accept("carrier leash invalid; restoring task to main");
                    restoreCarrierToMain();
                    return;
                }
                Rect dest = new Rect(slot.rect[0], slot.rect[1], slot.rect[2], slot.rect[3]);
                applyDirectCarrier(leash, dest, false);
            } catch (Throwable error) {
                log.accept("carrier reassert failed: " + error);
            }
        }
    }

    /** 把承载中的任务移回主区（点击格子/主区、退出一步、系统返回都走这里）。 */
    void restoreCarrierToMain() {
        boolean restored = false;
        for (Slot slot : host.slots) {
            if (!slot.carrier) continue;
            Object leash = slot.carrierLeash;
            try {
                if (leash != null
                        && Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                    restoreDirectCarrier(leash);
                }
            } catch (Throwable error) {
                log.accept("carrier restore failed: " + error);
            }
            slot.carrier = false;
            slot.carrierTaskId = -1;
            slot.carrierLeash = null;
            slot.carrierMirror = null;
            View tap = slot.carrierTapView;
            slot.carrierTapView = null;
            if (tap != null && host.root != null) {
                try { host.root.removeView(tap); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            }
            slot.occupied = false;
            slot.taskId = -1;
            slot.taskToken = null;
            slot.pkg = null;
            slot.taskVisible = false;
            View backing = host.sideBackingViews[slot.index];
            if (backing != null) backing.setVisibility(View.VISIBLE);
            if (slot.hint != null) slot.hint.setVisibility(View.VISIBLE);
            log.accept("carrier restored slot=" + slot.index + " task to main");
            restored = true;
        }
        host.carrierSlotIndex = -1;
        removeCarrierBackdrop();
        if (host.root != null) {
            host.root.setOnTouchListener(null);
        }
        if (restored && host.root != null) {
            host.root.requestLayout();
            host.root.invalidate();
        }
    }

    /**
     * 清掉主任务 leash 上可能残留的过渡裁切。旧版 clampOpeningTransition 把动画期的
     * crop 写进了 finish 事务，任务回到常驻后仍被裁到主区矩形（顶栏启动的应用只显示
     * 一部分）；这里在进入一步、快捷启动后、主任务变化时主动清一次。
     */
    void clearMainTaskLeashCrop(int taskId) {
        if (taskId < 0) return;
        try {
            Object organizer = shellOrganizer();
            if (organizer == null) return;
            Object leash = ShellCompat.taskLeash(organizer, taskId);
            if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                return;
            }
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setCrop",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                android.graphics.Rect.class}, leash, null);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            }
            log.accept("main task leash crop cleared task=" + taskId);
        } catch (Throwable error) {
            log.accept("clear main task crop failed task=" + taskId + ": " + error);
        }
    }

    /** 延时清一次主任务 leash 裁切（进入一步/恢复/快捷启动后调用）。 */
    void scheduleMainCropClear(long delayMs) {
        host.main.postDelayed(() -> {
            if (host.root == null || host.scene == WorkspaceHost.SUSPENDED) return;
            if (!host.executeOnShell(() -> {
                try {
                    LiveTask mainTask = host.slotRegistry.findMainTask();
                    if (mainTask != null) clearMainTaskLeashCrop(mainTask.id);
                } catch (Throwable error) {
                    log.accept("main task crop clear failed: " + error);
                }
            })) {
                log.accept("main task crop clear skipped: no shell queue");
            }
        }, delayMs);
    }

    void consumePendingMainSide() {
        int pending = host.pendingMainSide;
        if (pending < 0) return;
        host.pendingMainSide = -1;
        boolean right = pending == 1;
        if (right != host.mainOnRight) {
            log.accept("pending main side applied: " + (right ? "right" : "left"));
            host.mainSide.setMainSide(right);
        }
    }





    /** POC 期间该格的 SF 底板必须停止心跳重建，否则会盖住动画中的真实任务画面。 */

    void runLeashAnimPoc(int slotIndex, long durationMs, String pkg) {
        if (host.scene != WorkspaceHost.ACTIVE || host.layout == null || slotIndex <= WorkspaceHost.SLOT_MAIN
                || slotIndex >= WorkspaceHost.SLOT_COUNT || host.slots[slotIndex].rect == null) {
            log.accept("leash anim POC skipped slot=" + slotIndex + " scene=" + host.scene);
            return;
        }
        final int index = slotIndex;
        if (!host.executeOnShell(() -> {
            try {
                LiveTask task = null;
                try {
                    task = host.slotRegistry.findMainTask();
                } catch (Throwable error) {
                    log.accept("leash anim: findMainTask failed: " + error);
                }
                if (task == null && pkg != null) {
                    try {
                        task = host.slotRegistry.findPackageTask(pkg);
                        log.accept("leash anim: package fallback task="
                                + (task == null ? -1 : task.id) + " pkg=" + pkg);
                    } catch (Throwable error) {
                        log.accept("leash anim: findPackageTask failed: " + error);
                    }
                }
                if (task == null) {
                    log.accept("leash anim POC: no main task pkg=" + pkg);
                    return;
                }
                final LiveTask resolved = task;
                Object organizer = shellOrganizer();
                Object leash = organizer == null ? null
                        : ShellCompat.taskLeash(organizer, resolved.id);
                if (leash == null
                        || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                    log.accept("leash anim POC: no leash task=" + resolved.id);
                    return;
                }
                host.main.post(() -> animateLeashPoc(leash, host.slots[index].rect, index, resolved.id, durationMs));
            } catch (Throwable error) {
                log.accept("leash anim POC setup failed: " + error);
            }
        })) {
            log.accept("leash anim POC skipped: no shell queue");
        }
    }

    void animateLeashPoc(Object leash,int[] rect,int slotIndex,int taskId,long durationMs) {
        final Rect dest=new Rect(rect[0],rect[1],rect[2],rect[3]);
        final float ratio=host.layout.mainScale;
        if(leashExperiment==null)leashExperiment=new LeashExperiment(host.main,this::applyLeashTransform,
                this::hideSlotChromeForPoc,WorkspaceHost::log);
        leashExperiment.start(leash,slotIndex,taskId,
                (dest.left-host.layout.mainOffsetX)/ratio,(dest.top-host.layout.mainOffsetY)/ratio,
                dest.width()/(ratio*host.screenW),dest.height()/(ratio*host.screenH),durationMs);
    }

    /** POC 期间隐藏目标格的宿主底板，让真实任务画面能在格子里可见（正式交换也会这么做）。 */
    void hideSlotChromeForPoc(int slotIndex, boolean hide) {
        try {
            leashPocSlot = hide ? slotIndex : -1;
            View backing = host.sideBackingViews[slotIndex];
            if (backing != null) {
                backing.setVisibility(hide ? View.GONE : View.VISIBLE);
            }
            Slot slot = host.slots[slotIndex];
            if (slot != null) {
                if (hide) {
                    if (slot.hint != null) slot.hint.setVisibility(View.GONE);
                    host.cellSurfaces.removeCellBackground(slot);
                } else if (slot.hint != null) {
                    slot.hint.setVisibility(View.VISIBLE);
                }
            }
            if (host.root != null) {
                host.root.requestLayout();
                host.root.invalidate();
            }
            log.accept("leash anim POC slot chrome hidden=" + hide + " slot=" + slotIndex);
        } catch (Throwable error) {
            log.accept("leash anim chrome failed: " + error);
        }
    }

    static Object newSurfaceTransaction() throws Exception {
        java.lang.reflect.Constructor<?> cached = surfaceTransactionConstructor;
        if (cached == null) {
            cached = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor();
            surfaceTransactionConstructor = cached;
        }
        return cached.newInstance();
    }

    void applyLeashTransform(Object leash, float x, float y, float sx, float sy) {
        try {
            Object tx = newSurfaceTransaction();
            try {
                XposedHelpers.callMethod(tx, "setPosition", leash, x, y);
                XposedHelpers.callMethod(tx, "setMatrix", leash, sx, 0f, 0f, sy);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            }
        } catch (Throwable error) {
            log.accept("leash anim frame failed: " + error);
        }
    }


    void resetMainTaskLeash(int taskId) {
        if (taskId < 0) return;
        try {
            Object organizer = shellOrganizer();
            if (organizer == null) return;
            Object leash = ShellCompat.taskLeash(organizer, taskId);
            if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                return;
            }
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setPosition",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                float.class, float.class}, leash, 0f, 0f);
                XposedHelpers.callMethod(tx, "setMatrix",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                float.class, float.class, float.class, float.class},
                        leash, 1f, 0f, 0f, 1f);
                XposedHelpers.callMethod(tx, "setAlpha",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                float.class}, leash, 1f);
                XposedHelpers.callMethod(tx, "setCrop",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                android.graphics.Rect.class}, leash, null);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            }
            log.accept("main task leash reset task=" + taskId);
        } catch (Throwable error) {
            log.accept("reset main task leash failed task=" + taskId + ": " + error);
        }
    }
}
