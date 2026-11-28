package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Installs and owns the SystemUI TaskView identity/transaction hooks. */
final class WorkspaceNativeHooks {
    private WorkspaceNativeHooks() {}

    private static final ThreadLocal<java.util.List<SideClose>> nativeCloses = new ThreadLocal<>();
    private static final ThreadLocal<Object> closeStartTransaction = new ThreadLocal<>();
    private static final ThreadLocal<java.util.List<?>> externalChanges = new ThreadLocal<>();
    private static final ThreadLocal<Object> closeFinishTransaction = new ThreadLocal<>();
    static void install(ClassLoader cl) {
        if (WorkspaceHost.boundsHooksReady) return;
        java.util.List<XC_MethodHook.Unhook> installed = new java.util.ArrayList<>();
        try {
            Class<?> view = XposedHelpers.findClass("com.android.wm.shell.taskview.TaskView", cl);
            Class<?> trans = XposedHelpers.findClass("com.android.wm.shell.taskview.TaskViewTransitions", cl);
            Class<?> control = XposedHelpers.findClass("com.android.wm.shell.taskview.TaskViewTaskController", cl);
            Class<?> listener = XposedHelpers.findClass("com.android.wm.shell.taskview.TaskView$Listener", cl);
            boolean bridgeTaskInfo = installTaskViewHooks(cl, trans, control, listener, installed);
            installBoundsGuards(cl, view, trans, control, installed);
            installExternalChangeGuards(cl, trans, control, installed);
            WorkspaceTaskStateController.setLegacyTaskInfoCallbacks(bridgeTaskInfo);
            WorkspaceHost.boundsHooksReady = true;
            WorkspaceHost.log("logical bounds guards installed: getter/queue/consumer/unfold");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : installed) hook.unhook();
            WorkspaceHost.log("logical bounds guards unavailable; no task admission: " + error);
        }
    }

    /** Keyguard handoff, the legacy task-info bridge, and the side-close animation hooks. */
    private static boolean installTaskViewHooks(ClassLoader cl, Class<?> trans, Class<?> control,
            Class<?> listener,
            java.util.List<XC_MethodHook.Unhook> installed) {
        installed.addAll(KeyguardTransition.install(trans, owner -> {
            WorkspaceHost h = WorkspaceHost.currentInstance();
            return h != null && h.transitions == owner && h.retainedForLock && h.keyguardLocked
                    && (h.scene == WorkspaceHost.ACTIVE || h.scene == WorkspaceHost.RESUMING);
        }));
        final boolean bridgeTaskInfo = !ShellCompat.hasMethod(listener, "onTaskInfoChanged", 1);
        if (bridgeTaskInfo) {
            java.util.Set<XC_MethodHook.Unhook> callbacks = XposedBridge.hookAllMethods(control,
                    "onTaskInfoChanged", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    WorkspaceHost h = WorkspaceHost.currentInstance();
                    if (p.hasThrowable() || p.args.length != 1 || h == null) return;
                    Slot slot = h.slotRegistry.slotForObject(p.thisObject);
                    if (slot == null) return;
                    final Object info = p.args[0];
                    h.postForSlot(slot, owner -> h.taskStates.onTaskInfo(owner.index, info));
                }
            });
            if (callbacks.isEmpty()) throw new IllegalStateException("no legacy task-info callback");
            installed.addAll(callbacks);
        }
        installed.addAll(XposedBridge.hookAllMethods(trans, "startAnimation", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                WorkspaceHost h = WorkspaceHost.currentInstance();
                if ((p.args.length != 5 && p.args.length != 6) || h == null || h.transitions != p.thisObject) return;
                if (!ShellCompat.hasMethod(trans, "isValidTaskView", 2)
                        && XposedHelpers.callMethod(p.thisObject, "findPending", p.args[0]) == null) {
                    externalChanges.set((java.util.List<?>) XposedHelpers.callMethod(p.args[1], "getChanges"));
                    p.setObjectExtra("onesteplite.external", true);
                }
                int callbackIndex = p.args.length - 1;
                java.util.List<SideClose> closes = h.sideCloses.closingTasks(p.args[1]);
                if (closes.isEmpty()) return;
                p.setObjectExtra("onesteplite.closes", closes);
                nativeCloses.set(closes);
                closeStartTransaction.set(p.args[callbackIndex - 2]);
                closeFinishTransaction.set(p.args[callbackIndex - 1]);
                Object original = p.args[callbackIndex];
                Class<?> callback = Class.forName("com.android.wm.shell.transition.Transitions$TransitionFinishCallback", false, cl);
                java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
                p.args[callbackIndex] = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{callback}, (proxy, method, args) -> {
                    if (!"onTransitionFinished".equals(method.getName())) return method.invoke(original, args);
                    if (!finished.compareAndSet(false, true)) return null;
                    java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(closes.size());
                    Runnable finish = () -> {
                        if (remaining.decrementAndGet() == 0)
                            XposedHelpers.callMethod(original, "onTransitionFinished", args[0]);
                    };
                    for (SideClose close : closes) close.animate(finish);
                    return null;
                });
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                @SuppressWarnings("unchecked") java.util.List<SideClose> closes =
                        (java.util.List<SideClose>) p.getObjectExtra("onesteplite.closes");
                if (p.getObjectExtra("onesteplite.external") != null) externalChanges.remove();
                if (closes == null) return;
                nativeCloses.remove();
                closeStartTransaction.remove();
                closeFinishTransaction.remove();
                if (p.hasThrowable() || !Boolean.TRUE.equals(p.getResult()))
                    for (SideClose close : closes) close.abandon();
            }
        }));
        installed.addAll(XposedBridge.hookAllMethods(control, "prepareCloseAnimation", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                java.util.List<SideClose> closes = nativeCloses.get();
                if (closes == null || p.hasThrowable() || p.args.length != 2) return;
                for (SideClose close : closes)
                    if (close.controller == p.thisObject && close.surface == p.args[1]) {
                        try { close.prepare(p.args[0], closeFinishTransaction.get()); }
                        catch (Throwable error) { WorkspaceHost.log("side close prepare: " + error); }
                    }
            }
        }));
        if (!ShellCompat.hasMethod(control, "prepareCloseAnimation", 2)) {
            installed.addAll(XposedBridge.hookAllMethods(control, "resetTaskInfo", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    java.util.List<SideClose> closes = nativeCloses.get();
                    if (closes == null || p.hasThrowable()) return;
                    for (SideClose close : closes) if (close.controller == p.thisObject) {
                        try { close.prepare(closeStartTransaction.get(), closeFinishTransaction.get()); }
                        catch (Throwable error) { WorkspaceHost.log("side close OS3 prepare: " + error); }
                    }
                }
            }));
        }
        return bridgeTaskInfo;
    }

    /** Keep every bounds read and write in the logical workspace coordinates. */
    private static void installBoundsGuards(ClassLoader cl, Class<?> view, Class<?> trans,
            Class<?> control, java.util.List<XC_MethodHook.Unhook> installed) {
        installed.add(XposedHelpers.findAndHookMethod(view, "onComputeInternalInsets",
                XposedHelpers.findClass("android.view.ViewTreeObserver$InternalInsetsInfo", cl),
                new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                WorkspaceHost h = WorkspaceHost.currentInstance();
                // TaskView normally punches its app hole out of the Host. OneStepLite
                // owns the entire side strip for tap-to-promote. A moved/retired
                // TaskView must not subtract another cell after our Host listener.
                if (h != null && h.slotRegistry.ownsViewOrController(p.thisObject)) p.setResult(null);
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(view, "getCurrentBoundsOnScreen", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                WorkspaceHost h = WorkspaceHost.currentInstance();
                if (h != null && h.slotRegistry.ownsViewOrController(p.thisObject)) p.setResult(h.slotRegistry.logicalBounds(h.slotRegistry.slotForObject(p.thisObject)));
            }
        }));
        // The new container must already clip a returning task when Shell receives it.
        installed.addAll(XposedBridge.hookAllMethods(control, "surfaceCreated", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                WorkspaceHost h = WorkspaceHost.currentInstance();
                if (h == null || !h.sideRows.hasViewport() || p.args.length != 1) return;
                Slot slot = h.slotRegistry.slotForObject(p.thisObject);
                if (slot != null && slot.sideRow >= 0) h.sideRows.attachViewport(slot, p.args[0]);
            }
        }));
        XC_MethodHook rectGuard = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                WorkspaceHost h = WorkspaceHost.currentInstance();
                if (h != null && h.transitions == p.thisObject && h.slotRegistry.ownsViewOrController(p.args[0]))
                    p.args[1] = h.slotRegistry.logicalBounds(h.slotRegistry.slotForObject(p.args[0]));
            }
        };
        boolean directBounds = ShellCompat.hasMethod(trans, "setTaskBounds", control, Rect.class);
        if (directBounds) {
            installed.add(XposedHelpers.findAndHookMethod(trans, "setTaskBounds", control, Rect.class, rectGuard));
        } else if (ShellCompat.hasMethod(control, "setWindowBounds", Rect.class)) {
            // AOSP14/OS2 routes TaskView bounds through the controller itself.
            installed.add(XposedHelpers.findAndHookMethod(control, "setWindowBounds", Rect.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            WorkspaceHost h = WorkspaceHost.currentInstance();
                            if (h != null && p.args.length == 1 && p.args[0] instanceof Rect
                                    && h.slotRegistry.ownsViewOrController(p.thisObject))
                                p.args[0] = h.slotRegistry.logicalBounds(h.slotRegistry.slotForObject(p.thisObject));
                        }
                    }));
        } else throw new IllegalStateException("no native TaskView bounds owner");
        if (directBounds && ShellCompat.hasMethod(trans, "lambda$setTaskBounds$6", 2)) {
            installed.add(XposedHelpers.findAndHookMethod(trans, "lambda$setTaskBounds$6", control, Rect.class, rectGuard));
        } else if (directBounds) {
            Class<?> consumer = XposedHelpers.findClass("com.android.wm.shell.taskview.TaskViewTransitions$$ExternalSyntheticLambda0", cl);
            installed.add(XposedHelpers.findAndHookMethod(consumer, "run", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    WorkspaceHost h = WorkspaceHost.currentInstance();
                    if (h == null || XposedHelpers.getIntField(p.thisObject, "$r8$classId") != 0
                            || XposedHelpers.getObjectField(p.thisObject, "f$0") != h.transitions) return;
                    Object controller = XposedHelpers.getObjectField(p.thisObject, "f$1");
                    if (h.slotRegistry.ownsViewOrController(controller))
                        XposedHelpers.setObjectField(p.thisObject, "f$2", h.slotRegistry.logicalBounds(h.slotRegistry.slotForObject(controller)));
                }
            }));
        }
    }

    /** Leave external task changes to the platform, and open our own tasks translucent. */
    private static void installExternalChangeGuards(ClassLoader cl, Class<?> trans, Class<?> control,
            java.util.List<XC_MethodHook.Unhook> installed) {
        java.util.Set<XC_MethodHook.Unhook> external = XposedBridge.hookAllMethods(trans,
                "isValidTaskView", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                WorkspaceHost h = WorkspaceHost.currentInstance();
                if (h != null && h.transitions == p.thisObject && p.args.length == 2
                        && p.args[1] == null && leaveExternalChangeToNative(h, p.args[0]))
                    p.setResult(false);
            }
        });
        if (external.isEmpty()) {
            external = XposedBridge.hookAllMethods(trans, "findTaskView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    WorkspaceHost h = WorkspaceHost.currentInstance();
                    java.util.List<?> changes = externalChanges.get();
                    if (changes == null || h == null || h.transitions != p.thisObject
                            || p.args.length != 1 || !h.slotRegistry.ownsViewOrController(p.getResult())) return;
                    for (Object change : changes)
                        if (XposedHelpers.callMethod(change, "getTaskInfo") == p.args[0]
                                && leaveExternalChangeToNative(h, change)) { p.setResult(null); return; }
                }
            });
            if (external.isEmpty()) throw new IllegalStateException("no native task lookup guard");
        }
        installed.addAll(external);
        java.util.Set<XC_MethodHook.Unhook> unfold = XposedBridge.hookAllMethods(trans, "updateBoundsForUnfold", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                WorkspaceHost h = WorkspaceHost.currentInstance();
                if (h == null || h.transitions != p.thisObject || p.args.length < 2
                        || !(p.args[0] instanceof Rect)) return;
                for (Object arg : p.args) {
                    if (arg == null || !arg.getClass().getName().contains("RunningTaskInfo")) continue;
                    Object controller = XposedHelpers.callMethod(p.thisObject, "findTaskView", arg);
                    if (h.slotRegistry.ownsViewOrController(controller)) {
                        for (Slot slot : h.slots) {
                            if (slot.controller == controller && !slot.parked
                                    && h.slotRegistry.controllerOwns(slot, XposedHelpers.getIntField(arg, "taskId"),
                                    XposedHelpers.getObjectField(arg, "token"))) p.args[0] = h.slotRegistry.logicalBounds(slot);
                        }
                    }
                }
            }
        });
        installed.addAll(unfold);
        java.util.Set<XC_MethodHook.Unhook> opening = XposedBridge.hookAllMethods(trans, "prepareOpenAnimation", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                WorkspaceHost h = WorkspaceHost.currentInstance();
                if (h != null && h.transitions == p.thisObject && p.args.length == 7
                        && h.slotRegistry.ownsViewOrController(p.args[0])) {
                    Object info = p.args[4];
                    Object token = XposedHelpers.getObjectField(info, "token");
                    Object wct = p.args[6];
                    XposedHelpers.callMethod(wct, "setForceTranslucent", token, true);
                    XposedHelpers.callMethod(wct, "setAlwaysOnTop", token, true);
                    XposedHelpers.callMethod(wct, "setFocusable", token, true);
                    Slot slot = h.slotRegistry.slotForObject(p.args[0]);
                    XposedHelpers.callMethod(wct, "setBounds", token, h.slotRegistry.logicalBounds(slot));
                    applyTopConfiguration(h, wct, token, slot);
                }
            }
        });
        installed.addAll(opening);
    }


    static boolean shouldClampAnimationBounds(Object info, Object change) {
        WorkspaceHost h = WorkspaceHost.currentInstance();
        if (h == null || info == null || change == null) return false;
        try {
            int transitionType = (Integer) XposedHelpers.callMethod(info, "getType");
            int changeMode = (Integer) XposedHelpers.callMethod(change, "getMode");
            Object task = XposedHelpers.callMethod(change, "getTaskInfo");
            if (task == null) return false;
            Object token = XposedHelpers.getObjectField(task, "token");
            boolean sideTask = token == null || h.slotRegistry.isSideToken(token) || h.taskStates.windowModeOf(task) == 6;
            int startRotation = -1;
            int endRotation = -1;
            try {
                startRotation = (Integer) XposedHelpers.callMethod(change, "getStartRotation");
                endRotation = (Integer) XposedHelpers.callMethod(change, "getEndRotation");
            } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            boolean rotating = startRotation >= 0 && endRotation >= 0
                    && startRotation != endRotation;
            return TransitionAnimationPolicy.shouldClampOpenBounds(
                    WorkspaceWindowLayout.hostSceneActive(), h.promotion != null || h.transferBusy,
                    transitionType, changeMode, sideTask, h.taskStates.windowModeOf(task), rotating);
        } catch (Throwable error) {
            return false;
        }
    }

    static void applyTopConfiguration(WorkspaceHost h, Object wct, Object token, Slot slot) {
        WorkspacePorts.Runtime ports=WorkspacePorts.of(h);
        applyTopConfiguration(ports.slots(),ports.surfaces(),ports.presentation(),wct,token,slot);
    }

    static void applyTopConfiguration(WorkspacePorts.SlotPort slots,
            WorkspacePorts.SurfacePort surfaces, WorkspacePorts.PresentationPort presentation,
            Object wct, Object token, Slot slot) {
        if (slot == null || slot.index <= WorkspaceHost.SLOT_MAIN) return;
        if (slot.index != WorkspaceHost.SLOT_TOP) {
            if(slot.rect!=null && slot.rect[2]>slot.rect[0] && slot.rect[3]>slot.rect[1]
                    && surfaces.screenWidth()>0 && surfaces.screenHeight()>0
                    && surfaces.displayDensityDpi()>0) {
                float scale=Math.min((slot.rect[2]-slot.rect[0])/(float)surfaces.screenWidth(),
                        (slot.rect[3]-slot.rect[1])/(float)surfaces.screenHeight());
                int density=Math.max(72,Math.round(surfaces.displayDensityDpi()*scale));
                XposedHelpers.callMethod(wct,"setDensityDpi",token,density);
            }
            return;
        }
        TopTaskGeometry geometry = surfaces.windowLayout().topGeometry();
        Rect bounds=slots.slotRegistry().logicalBounds(slot, presentation.topPortrait());
        XposedHelpers.callMethod(wct, "setAppBounds", token, bounds);
        // Inherit native density so retained app Views never mix cached px at two DPIs.
        XposedHelpers.callMethod(wct, "setDensityDpi", token, 0);
        int widthDp = Math.max(1, Math.round(bounds.width() * 160f / geometry.densityDpi));
        int heightDp = Math.max(1, Math.round(bounds.height() * 160f / geometry.densityDpi));
        XposedHelpers.callMethod(wct, "setScreenSizeDp", token, widthDp, heightDp);
        XposedHelpers.callMethod(wct, "setSmallestScreenWidthDp", token, Math.min(widthDp, heightDp));
    }

    static boolean leaveExternalChangeToNative(WorkspaceHost h, Object change) {
        if (change == null || (h.scene != WorkspaceHost.ACTIVE
                && !(h.retainedOnExit && (h.scene == WorkspaceHost.SUSPENDING || h.scene == WorkspaceHost.SUSPENDED)))
                || !h.screenOn || h.keyguardLocked || h.transferBusy) return false;
        try {
            int mode = (Integer) XposedHelpers.callMethod(change, "getMode");
            if (mode != 6 && mode != 3) return false;
            Object info = XposedHelpers.callMethod(change, "getTaskInfo");
            if (!h.slotRegistry.ordinaryRoot(info, 1)) return false;
            int taskId = XposedHelpers.getIntField(info, "taskId");
            Object token = XposedHelpers.getObjectField(info, "token");
            for (Slot slot : h.slots) {
                if (slot.index <= 0 || !h.slotRegistry.isActive(slot) || !slot.occupied || slot.parked
                        || slot.frozen || slot.launching || slot.modeAdmissionPending
                        || slot.taskId != taskId || token == null || !token.equals(slot.taskToken)
                        || !h.slotRegistry.controllerOwns(slot, taskId, token)) continue;
                Promotion current = h.promotion;
                if (current != null && (!current.externalLaunch || current.slot != slot
                        || current.sceneEpoch != h.sceneEpoch || current.bindingEpoch != slot.taskEpoch))
                    return false;
                Object queue = XposedHelpers.getObjectField(h.transitions, "mPending");
                if (!(queue instanceof Iterable<?>)) return false;
                for (Object pending : (Iterable<?>) queue)
                    if (XposedHelpers.getObjectField(pending, "mTaskView") == slot.controller) return false;
                Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
                synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
                    Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                            organizer, "mTasks")).get(taskId);
                    Object canonical = appeared == null ? null : XposedHelpers.callMethod(appeared, "getTaskInfo");
                    if (!h.slotRegistry.ordinaryRoot(canonical, 1) || !token.equals(XposedHelpers.getObjectField(canonical, "token"))
                            || XposedHelpers.getIntField(canonical, "taskId") != taskId
                            || XposedHelpers.getIntField(canonical, "userId") != XposedHelpers.getIntField(info, "userId"))
                        return false;
                }
                WorkspaceHost.log("external fullscreen change kept native task=" + taskId + " slot=" + slot.index);
                return true;
            }
        } catch (Throwable unavailable) { WorkspaceHost.log("external native change guard unavailable: " + unavailable); }
        return false;
    }

    static Object getSurfaceField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (!"android.view.SurfaceControl".equals(f.getType().getName())) {
                    continue;
                }
                f.setAccessible(true);
                Object surface = f.get(target);
                return surface != null && Boolean.TRUE.equals(
                        XposedHelpers.callMethod(surface, "isValid")) ? surface : null;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    static boolean applyWct(String what, Object wct) {
        Throwable first = null;
        // 路线 A：框架公开类 WindowOrganizer（SystemUI 里可直接 new，真机已验证）。
        try {
            Class<?> c = Class.forName("android.window.WindowOrganizer");
            Object organizer = c.getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(organizer, "applyTransaction", wct);
            WorkspaceHost.log("wct(" + what + ") via WindowOrganizer");
            return true;
        } catch (Throwable t) {
            first = t;
        }
        // 路线 B：从已捕获的 Shell 对象（h.transitions / controller）里找能 applyTransaction 的字段。
        try {
            Object applier = findWctApplier(wct);
            if (applier != null) {
                WorkspaceHost.log("wct(" + what + ") via " + applier.getClass().getName());
                return true;
            }
        } catch (Throwable t) {
            WorkspaceHost.log("wct fallback failed: " + t);
        }
        WorkspaceHost.log("wct(" + what + ") FAILED: " + first);
        return false;
    }

    static Object findWctApplier(Object wct) {
        Class<?> wctCls;
        try {
            wctCls = Class.forName("android.window.WindowContainerTransaction");
        } catch (Throwable t) {
            return null;
        }
        WorkspaceHost h = WorkspaceHost.currentInstance();
        if (h == null) return null;
        Object[] roots = new Object[WorkspaceHost.SLOT_COUNT+1];
        roots[0]=h.transitions;
        for(int i=0;i<WorkspaceHost.SLOT_COUNT;i++)roots[i+1]=h.slots[i].controller;
        for (Object r : roots) {
            if (r == null) {
                continue;
            }
            for (Class<?> c = r.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(r);
                        if (v == null || v == wct) {
                            continue;
                        }
                        Method m;
                        try {
                            m = v.getClass().getMethod("applyTransaction", wctCls);
                        } catch (NoSuchMethodException e) {
                            continue;
                        }
                        m.invoke(v, wct);
                        return v;
                    } catch (Throwable ignored) {
                        // 该字段不可读或不可用，继续找
                    }
                }
            }
        }
        return null;
    }
}
