package io.github.zerohomers0.onesteplite;

import android.view.View;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Observes native transactions and restores the surfaces owned by the current scene. */
final class WorkspaceRetainedSurfaceHooks {
    private static boolean surfaceHooksReady;
    private final WorkspaceHost host;
    private static final int MAX_OVERLAY_REFRESH_ATTEMPTS = 24;
    private Object surfaceObserver;
    private boolean overlayRefreshScheduled;
    private int overlayRefreshAttempts;
    private final Runnable overlayRefresh = this::refreshOverlayAfterTransition;

    WorkspaceRetainedSurfaceHooks(WorkspaceHost host) {
        this.host = host;
    }

    void installRetainedSurfaces(ClassLoader cl) {
        if (!surfaceHooksReady) {
            java.util.List<XC_MethodHook.Unhook> installed = new java.util.ArrayList<>();
            try {
                Class<?> tx = Class.forName("android.view.SurfaceControl$Transaction");
                installTransactionHooks(tx, installed);
                if (installed.size() < 3) throw new IllegalStateException("missing finish transaction hooks");
                KeyguardTransition.installNativeStart(cl, (info, start) -> {
                    WorkspaceHost h = WorkspaceHost.currentInstance();
                    if (h != null && h.retainedForLock && h.keyguardWindowHidden)
                        h.keyguardTransactions.mark(start, java.util.Collections.singletonList(h.sceneEpoch));
                });
                surfaceHooksReady = true;
            } catch (Throwable error) {
                for (XC_MethodHook.Unhook hook : installed) hook.unhook();
                WorkspaceHost.log("retained surface hooks unavailable: " + error);
                return;
            }
        }
        host.executeOnShell(() -> registerSurfaceObserver(cl));
    }

    static void installTransactionHooks(Class<?> tx, java.util.List<XC_MethodHook.Unhook> installed) {
        installed.addAll(XposedBridge.hookAllMethods(tx, "merge", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                WorkspaceHost h = WorkspaceHost.currentInstance();
                if (h != null && !p.hasThrowable() && p.args.length == 1) {
                    h.finishTransactions.merge(p.thisObject, p.args[0]);
                    h.workspaceFinishes.merge(p.thisObject, p.args[0]);
                    h.nativeObservations.merge(p.thisObject, p.args[0]);
                    h.keyguardTransactions.merge(p.thisObject, p.args[0]);
                    h.unlockFinishTransactions.merge(p.thisObject, p.args[0]);
                }
            }
        }));
        installed.addAll(XposedBridge.hookAllMethods(tx, "apply", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                WorkspaceHost h = WorkspaceHost.currentInstance();
                if (h == null) return;
                java.util.List<Long> unlockEpochs = h.keyguardTransactions.take(p.thisObject);
                h.keyguard.appendHiddenKeyguardSurfaces(p.thisObject, unlockEpochs);
                java.util.List<RenderBinding> frames = h.finishTransactions.take(p.thisObject);
                if (!frames.isEmpty()) h.retainedSurfaces.appendRetainedSurfaces(p.thisObject, frames);
                h.retainedSurfaces.appendWorkspaceSurfaces(p.thisObject, h.workspaceFinishes.take(p.thisObject));
                h.keyguard.appendUnlockGeometry(p.thisObject, unlockEpochs);
                h.retainedSurfaces.appendTopTransitionSurfaces(p.thisObject);
                h.keyguard.appendUnlockFinishCommit(p.thisObject);
                java.util.List<NativeObservation> checks = h.nativeObservations.take(p.thisObject);
                if (!checks.isEmpty()) {
                    try {
                        Executor uiExecutor = action -> h.main.post(action);
                        XposedHelpers.callMethod(p.thisObject, "addTransactionCommittedListener",
                                uiExecutor, (android.view.SurfaceControl.TransactionCommittedListener) () -> {
                                    for (NativeObservation check : checks) check.run();
                                });
                    } catch (Throwable unavailable) {
                        WorkspaceHost.log("commit signal unavailable; retain bounded observation watchdog");
                    }
                }
            }
        }));
        installed.addAll(XposedBridge.hookAllMethods(tx, "close", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                WorkspaceHost h = WorkspaceHost.currentInstance();
                if (h != null) {
                    h.finishTransactions.forget(p.thisObject);
                    h.workspaceFinishes.forget(p.thisObject);
                    h.nativeObservations.forget(p.thisObject);
                    h.keyguardTransactions.forget(p.thisObject);
                    h.unlockFinishTransactions.forget(p.thisObject);
                }
            }
        }));
    }

    private void appendTopTransitionSurfaces(Object transaction) {
        TopTaskTransition bridge=host.topTransition;
        if(bridge==null)return;
        Slot upper=host.slots[WorkspaceHost.SLOT_TOP];
        Object parent=WorkspaceNativeHooks.getSurfaceField(upper.controller,"mSurfaceControl");
        Object leash=WorkspaceNativeHooks.getSurfaceField(upper.controller,"mTaskLeash");
        try {bridge.appendDestination(transaction,parent,leash);}
        catch(Throwable error) {WorkspaceHost.log("upper transition opacity guard: "+error);}
    }

    private void registerSurfaceObserver(ClassLoader cl) {
        try {
            Class<?> iface = XposedHelpers.findClass(
                    "com.android.wm.shell.transition.Transitions$TransitionObserver", cl);
            surfaceObserver = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    (self, method, args) -> {
                        try {
                            String name = method.getName();
                            if ("onTransitionReady".equals(name) && args != null && args.length == 4) {
                                if ((host.retainedForLock || host.zoomed)
                                        && (((Integer) XposedHelpers.callMethod(args[1], "getFlags")) & 0x100) != 0) {
                                    host.keyguardTransactions.mark(args[2], java.util.Collections.singletonList(host.sceneEpoch));
                                    host.keyguardTransactions.mark(args[3], java.util.Collections.singletonList(host.sceneEpoch));
                                    host.keyguard.trackUnlockFinish(args[3]);
                                }
                                SwapTrace trace = host.debug.swapTrace();
                                if (trace != null) trace.transition(args[1], args[2], args[3]);
                                trackRetainedSurfaces(args[1], args[2], args[3]);
                                scheduleOverlayRefresh();
                            }
                            else if ("onTransitionMerged".equals(name)
                                    || "onTransitionFinished".equals(name)) {
                                host.debug.traceSwapEvent(name);
                                host.main.post(() -> { if (host.topBar != null) host.topBar.requestOpenedAppsRefresh(); });
                            }
                            else if ("hashCode".equals(name)) return System.identityHashCode(self);
                            else if ("equals".equals(name)) return self == args[0];
                            else if ("toString".equals(name)) return "OneStepLiteRetainedSurfaceObserver";
                            // Target onTransitionFinished precedes finishT.apply: never restore here.
                        } catch (Throwable error) { WorkspaceHost.log("surface observer stopped: " + error); }
                        return null;
                    });
            Object shellTransitions = XposedHelpers.getObjectField(host.transitions, "mTransitions");
            XposedHelpers.callMethod(shellTransitions, "registerObserver", surfaceObserver);
            WorkspaceHost.log("retained surface observer registered; restore only in marked final apply");
        } catch (Throwable error) { WorkspaceHost.log("surface observer unavailable: " + error); }
    }

    private void trackRetainedSurfaces(Object info, Object start, Object finish) {
        NativeObservation observation = host.pendingNativeObservation;
        if (observation != null && !observation.isConsumed()) {
            java.util.List<?> observedChanges = (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges");
            for (Object change : observedChanges) {
                Object task = XposedHelpers.callMethod(change, "getTaskInfo");
                if (task != null && observation.token.equals(XposedHelpers.getObjectField(task, "token"))) {
                    host.nativeObservations.mark(finish, java.util.Collections.singletonList(observation));
                    break;
                }
            }
        }
        if (!host.screenOn || (host.scene != WorkspaceHost.ACTIVE && host.scene != WorkspaceHost.RESUMING) || host.root == null) return;
        // A main activity's rotation/cleanup can reparent a previously adopted side
        // task even when that side token is absent from TransitionInfo. Resolve its
        // current canonical binding at the final apply, after native merges.
        host.workspaceFinishes.mark(finish, java.util.Collections.singletonList(host.sceneEpoch));
        // Rotation and live task exchanges can briefly lift untouched peers out of their
        // TaskViews. Preserve those peers in the native start and finish transactions.
        if (host.promotion != null || Integer.valueOf(6).equals(XposedHelpers.callMethod(info, "getType")))
            host.workspaceFinishes.mark(start, java.util.Collections.singletonList(host.sceneEpoch));
        boolean relevant = host.promotion != null || host.transferBusy;
        for (Slot slot : host.slots) if (slot.launching) relevant = true;
        java.util.List<?> changes = (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges");
        for (Object change : changes) {
            Object task = XposedHelpers.callMethod(change, "getTaskInfo");
            if (task == null) continue;
            Object token = XposedHelpers.getObjectField(task, "token");
            if (host.slotRegistry.isSideToken(token)) relevant = true;
        }
        if (!relevant) return;
        java.util.List<RenderBinding> frames = new java.util.ArrayList<>();
        for (Slot slot : host.slots) {
            if (!host.slotRegistry.canRender(slot) || !slot.occupied || slot.promoting || slot.launching || slot.frozen
                    || !host.scales.mayWriteSideSurface(slot)
                    || !host.slotRegistry.controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
            Object leash = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mTaskLeash");
            Object parent = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mSurfaceControl");
            if (leash != null && parent != null && leash != parent)
                frames.add(new RenderBinding(slot, host.sceneEpoch, leash, parent));
        }
        if (!frames.isEmpty() && !host.finishTransactions.mark(finish, frames))
            WorkspaceHost.log("retained finish transaction cap; do not retry or touch unrelated transactions");
    }

    private void scheduleOverlayRefresh() {
        if (overlayRefreshScheduled) return;
        overlayRefreshScheduled = true;
        overlayRefreshAttempts = 0;
        host.main.removeCallbacks(overlayRefresh);
        host.main.postDelayed(overlayRefresh, 60L);
        if (host.topBar != null) host.topBar.requestOpenedAppsRefresh();
    }

    private void refreshOverlayAfterTransition() {
        if (host.scene != WorkspaceHost.ACTIVE || host.root == null || host.keyguardLocked) {
            overlayRefreshScheduled = false;
            overlayRefreshAttempts = 0;
            return;
        }
        boolean waiting = host.cellMovePreview != null || host.promotion != null || host.transferBusy;
        for (Slot slot : host.slots) if (slot.closing) { waiting = true; break; }
        if (waiting) {
            // Relayouting the host window during the visible bridge brings its
            // SurfaceView children forward for one composition. Bound the wait so a
            // lost native callback cannot leave a permanent 120 ms main-thread poll.
            if (++overlayRefreshAttempts >= MAX_OVERLAY_REFRESH_ATTEMPTS) {
                overlayRefreshScheduled = false;
                overlayRefreshAttempts = 0;
                WorkspaceHost.log("overlay refresh deadline reached; retain current scene");
                return;
            }
            host.main.postDelayed(overlayRefresh, 120L);
            return;
        }
        overlayRefreshScheduled = false;
        overlayRefreshAttempts = 0;
        try {
            host.root.setVisibility(View.VISIBLE);
            host.root.setAlpha(1f);
            host.root.requestLayout();
            host.root.invalidate();
            if (host.root.getWindowToken() != null && host.wm != null && host.lp != null)
                host.wm.updateViewLayout(host.root, host.lp);
            WorkspaceHost.log("overlay refreshed for transition");
        } catch (Throwable error) {
            WorkspaceHost.log("overlay refresh failed: " + error);
        }
    }

    void appendWorkspaceSurfaces(Object tx, java.util.List<Long> epochs) {
        if (!host.screenOn || (host.scene != WorkspaceHost.ACTIVE && host.scene != WorkspaceHost.RESUMING) || host.root == null
                || !epochs.contains(host.sceneEpoch)) return;
        java.util.List<RenderBinding> frames = new java.util.ArrayList<>();
        for (Slot slot : host.slots) {
            if (slot.index <= WorkspaceHost.SLOT_MAIN || !host.slotRegistry.canRender(slot) || !slot.occupied
                    || slot.parked || slot.promoting || slot.launching || slot.frozen
                    || !host.scales.mayWriteSideSurface(slot)
                    || !host.slotRegistry.controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
            Object leash = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mTaskLeash");
            Object parent = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mSurfaceControl");
            if (leash != null && parent != null && leash != parent)
                frames.add(new RenderBinding(slot, host.sceneEpoch, leash, parent));
        }
        appendRetainedSurfaces(tx, frames);
    }

    void appendRetainedSurfaces(Object tx, java.util.List<RenderBinding> frames) {
        java.util.Set<Slot> restored = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int count = 0;
        for (RenderBinding frame : frames) {
            Slot slot = frame.slot;
            if (restored.contains(slot) || !host.slotRegistry.canRender(slot) || slot.promoting || slot.launching || slot.frozen
                    || LiveSwap64.isBorrowingPeer(slot.controller, frame.id, frame.token)
                    || !host.scales.mayWriteSideSurface(slot)
                    || frame.scene != host.sceneEpoch || frame.binding != slot.taskEpoch
                    || frame.id != slot.taskId || !frame.token.equals(slot.taskToken)
                    || !host.slotRegistry.controllerOwns(slot, frame.id, frame.token)
                    || frame.leash != WorkspaceNativeHooks.getSurfaceField(slot.controller, "mTaskLeash")
                    || frame.parent != WorkspaceNativeHooks.getSurfaceField(slot.controller, "mSurfaceControl")) continue;
            try {
                // Same parent/crop/position/show contract as target TaskViewTransitions.updateSurface.
                XposedHelpers.callMethod(tx, "reparent", frame.leash, frame.parent);
                XposedHelpers.callMethod(tx, "setLayer", frame.leash, 0);
                XposedHelpers.callMethod(tx, "setPosition", frame.leash, 0f, 0f);
                XposedHelpers.callMethod(tx, "setWindowCrop", frame.leash, host.slotRegistry.logicalBounds(slot));
                host.scales.appendSlotViewport(tx, slot);
                float scale = host.scales.scaleFor(slot);
                XposedHelpers.callMethod(tx, "setMatrix", frame.leash, scale, 0f, 0f, scale);
                // A late native TaskView finish must not reveal the first retained
                // task before the whole group has passed the entry frame gate.
                XposedHelpers.callMethod(tx, "setAlpha", frame.leash, host.workspaceAlpha > 0f ? 1f : 0f);
                XposedHelpers.callMethod(tx, "show", frame.leash);
                restored.add(slot);
                count++;
            } catch (Throwable error) { WorkspaceHost.log("retained surface skipped task=" + frame.id + ": " + error); }
        }
        if (count > 0) WorkspaceHost.log("final transaction retained own side surfaces=" + count);
    }

    void scheduleSideVisibilityRepair(Slot slot) {
        scheduleSideVisibilityRepair(slot, false);
    }

    void scheduleSideVisibilityRepair(Slot slot, boolean warming) {
        if (!warming && !host.cellSurfaces.presentsSlot(slot)) return;
        if (!host.slotRegistry.canRender(slot) || host.scene != WorkspaceHost.ACTIVE || !host.screenOn || host.keyguardLocked
                || slot.promoting || slot.launching
                || slot.frozen || !slot.occupied || slot.taskVisible || slot.reassertPending) return;
        final long epoch = host.sceneEpoch, binding = slot.taskEpoch;
        final int taskId = slot.taskId;
        final Object token = slot.taskToken;
        slot.reassertPending = true;
        host.main.postDelayed(() -> {
            slot.reassertPending = false;
            if (!visibilityRepairCurrent(slot, epoch, binding, taskId, token, warming)) return;
            for (Slot side : host.slots) if (side.promoting || side.launching || side.frozen) return;
            host.executeOnShell(() -> {
                try {
                    if (!visibilityRepairCurrent(slot, epoch, binding, taskId, token, warming)
                            || !host.slotRegistry.controllerOwns(slot, taskId, token)) return;
                    Object foreground = null;
                    java.util.List<?> roots = host.slotRegistry.rootInfos();
                    for (Object info : roots) {
                        if (!XposedHelpers.getBooleanField(info, "isVisible")
                                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                                || host.slotRegistry.isSideToken(XposedHelpers.getObjectField(info, "token"))) continue;
                        int type = (Integer) XposedHelpers.callMethod(info, "getActivityType");
                        if (type != 1 && type != 2) continue;
                        if (foreground != null) return; // Ambiguous split/overlay scene: do not guess.
                        foreground = XposedHelpers.getObjectField(info, "token");
                    }
                    if (!slot.visibilityRepair.admit(epoch, foreground)) return;
                    Object wct = TransactionCompat.newWindowTransaction();
                    int count = 0;
                    for (Slot side : host.slots) {
                        if ((!host.cellSurfaces.presentsSlot(side) && (!warming || side != slot))
                                || !host.slotRegistry.canRender(side) || side.promoting || side.launching || side.frozen
                                || !side.occupied || !host.slotRegistry.controllerOwns(side, side.taskId, side.taskToken)) continue;
                        Object core = null;
                        for (Object info : roots) if (new LiveTask(side).matches(info)) { core = info; break; }
                        if (!host.slotRegistry.ordinaryRoot(core, 6)) continue;
                        XposedHelpers.callMethod(wct, "setHidden", side.taskToken, false);
                        XposedHelpers.callMethod(wct, "setForceTranslucent", side.taskToken, true);
                        XposedHelpers.callMethod(wct, "setAlwaysOnTop", side.taskToken, true);
                        XposedHelpers.callMethod(wct, "setFocusable", side.taskToken,
                                side.sideRow < 0 || side.sideShown);
                        XposedHelpers.callMethod(wct, "reorder", side.taskToken, true);
                        count++;
                    }
                    if (count == 0 || !visibilityRepairCurrent(slot, epoch, binding, taskId, token, warming)) return;
                    boolean applied = WorkspaceNativeHooks.applyWct("retain-side-order", wct);
                    if (applied) {
                        for (Slot side : host.slots) {
                            if ((host.cellSurfaces.presentsSlot(side) || (warming && side == slot))
                                    && host.slotRegistry.canRender(side) && side.occupied && !side.promoting && !side.frozen
                                    && host.slotRegistry.controllerOwns(side, side.taskId, side.taskToken))
                                XposedHelpers.callMethod(host.transitions, "setTaskViewVisible", side.controller, true);
                        }
                    }
                    WorkspaceHost.log("side order repair kept live tasks=" + count + " foreground=" + foreground
                            + " applied=" + applied + "; no remove/relaunch");
                } catch (Throwable error) {
                    WorkspaceHost.log("side order repair stopped; retain original task: " + error);
                }
            });
        }, warming ? 48L : 160L);
    }

    void reassertSideTaskViews(String reason) {
        final long epoch = host.sceneEpoch;
        if (!host.executeOnShell(() -> {
            if (!host.screenOn || (host.scene != WorkspaceHost.ACTIVE && host.scene != WorkspaceHost.RESUMING)
                    || host.root == null || epoch != host.sceneEpoch) return;
            int count = 0;
            java.util.List<RenderBinding> frames = new java.util.ArrayList<>();
            for (Slot side : host.slots) {
                if (side.index <= WorkspaceHost.SLOT_MAIN || !host.cellSurfaces.presentsSlot(side) || !host.slotRegistry.canRender(side) || !side.occupied
                        || (side.index==WorkspaceHost.SLOT_TOP && !host.topFace) || side.parked || side.promoting || side.launching || side.frozen
                        || side.controller == null
                        || !host.slotRegistry.controllerOwns(side, side.taskId, side.taskToken)) continue;
                try {
                    XposedHelpers.callMethod(host.transitions, "setTaskViewVisible", side.controller, true);
                    Object leash = WorkspaceNativeHooks.getSurfaceField(side.controller, "mTaskLeash");
                    Object parent = WorkspaceNativeHooks.getSurfaceField(side.controller, "mSurfaceControl");
                    if (leash != null && parent != null && leash != parent)
                        frames.add(new RenderBinding(side, epoch, leash, parent));
                    count++;
                } catch (Throwable error) {
                    WorkspaceHost.log("side reassert failed slot=" + side.index + ": " + error);
                }
            }
            if (!frames.isEmpty()) {
                Object tx = null;
                try {
                    tx = TransactionCompat.newSurfaceTransaction();
                    // setTaskViewVisible is a no-op for an already-visible task.
                    // A rotation finish can still have returned its canonical leash
                    // to the display. Restore the owned parent and geometry together.
                    host.retainedSurfaces.appendRetainedSurfaces(tx, frames);
                    XposedHelpers.callMethod(tx, "apply");
                } catch (Throwable error) {
                    WorkspaceHost.log("side parent reassert failed reason=" + reason + ": " + error);
                } finally { host.sideRows.closeSurfaceObject(tx, "close"); }
            }
            final int visible = count;
            host.main.post(() -> {
                for (Slot side : host.slots) {
                    if (side.index > WorkspaceHost.SLOT_MAIN && side.occupied && !side.promoting && !side.parked)
                        host.scales.reapplyScale(side);
                }
                WorkspaceHost.log("side TaskViews reasserted reason=" + reason + " count=" + visible);
            });
        })) WorkspaceHost.log("side TaskView reassert skipped reason=" + reason + ": no Shell queue");
    }

    private boolean visibilityRepairCurrent(Slot slot, long epoch, long binding, int id, Object token,
            boolean warming) {
        return (warming || host.cellSurfaces.presentsSlot(slot)) && host.slotRegistry.canRender(slot) && host.scene == WorkspaceHost.ACTIVE
                && !slot.promoting && !slot.launching && !slot.frozen
                && host.sceneEpoch == epoch && slot.taskEpoch == binding && slot.taskId == id
                && token != null && token.equals(slot.taskToken);
    }
}
