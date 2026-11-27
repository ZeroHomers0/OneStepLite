package io.github.zerohomers0.onesteplite;

import static io.github.zerohomers0.onesteplite.HostEntry.*;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import android.view.WindowManager;
import java.util.function.Consumer;

/** Native freeform coexistence, layering and touch-exclusion ownership. */
final class NativeWindowRuntime {
    private NativeWindowRuntime() {}

    static void installNativeFreeformCoexistence(ClassLoader cl) {
        NativeFreeformTouchExclusionHooks.install(cl,
                () -> sZoomTarget,
                () -> sNativeFreeformLayerAnchor,
                () -> sNativeFreeformHostLayers,
                () -> sZoomed,
                HostEntry::log);
        installNativeFreeformPin(cl);
        try {
            Class<?> display = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            XposedBridge.hookAllMethods(display, "assignChildLayers", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject != sDisplayContent || p.args.length != 1) return;
                    try { sModalWindowLayers.reconcile(p.thisObject,p.args[0], sZoomed); }
                    catch (Throwable error) { log("workspace modal layering skipped: " + error); }
                    if (!sZoomed && sNativeFreeformHostLayers.isEmpty()) return;
                    try { layerWorkspaceWithNativeFreeforms(p.thisObject, p.args[0]); }
                    catch (Throwable error) { log("native freeform layering skipped: " + error); }
                }
            });
            log("native freeform inherits main zoom; workspace masks overflow outside main");
        } catch (Throwable error) { log("native freeform coexistence hook unavailable: " + error); }
    }

    static Object sNativeFreeformLayerAnchor;
    static final ModalWindowLayers sModalWindowLayers = new ModalWindowLayers();
    static final java.util.Map<Object, NativeHostLayer> sNativeFreeformHostLayers =
            new java.util.IdentityHashMap<>();
    static void installNativeFreeformPin(ClassLoader cl) {
        NativeFreeformPinHooks.install(cl, NativeWindowRuntime::releaseNativeForegroundPinLayers, HostEntry::log);
    }


    static boolean isNativeForegroundPin(Object task) {
        Object manager = NativeFreeformPinHooks.manager();
        if(manager==null)manager=NativeFreeformTouchExclusionHooks.manager();
        return manager != null && Boolean.TRUE.equals(XposedHelpers.callMethod(manager,
                "getMiuiFreeformIsForegroundPin", XposedHelpers.getIntField(task, "mTaskId")));
    }

    static void releaseNativeForegroundPinLayers(Object manager, int taskId) {
        NativeFreeformPinHooks.setManager(manager);
        ZoomTarget target = sZoomTarget;
        if (!sZoomed || target == null) return;
        synchronized (target.globalLock) {
            if (!sZoomed || target != sZoomTarget || sNativeFreeformHostLayers.isEmpty()) return;
            try {
                // The ROM has just stored mIsForegroundPin, but pin2/3 need not
                // assign child layers. Clear BOTH live and pending dependencies
                // synchronously, before the Binder reply permits Shell hideTask.
                Object transaction = Class.forName("android.view.SurfaceControl$Transaction")
                        .getDeclaredConstructor().newInstance();
                try {
                    layerWorkspaceWithNativeFreeforms(target.display, transaction);
                    XposedHelpers.callMethod(transaction, "apply");
                } finally { XposedHelpers.callMethod(transaction, "close"); }
                log("native foreground pin layers reconciled task=" + taskId);
            } catch (Throwable error) { log("native foreground pin layer release failed: " + error); }
        }
    }

    /** Amend WM's own layer transaction; never reparent or transform an OEM task. */
    static void layerWorkspaceWithNativeFreeforms(Object display, Object transaction) {
        boolean touchExclusionChanged = false;
        Object[] highestFreeform = {null};
        java.util.List<Object> hosts = workspaceOverlayWindows(display);
        Consumer<Object> pickFreeform = task -> {
            if (!sZoomed || (!Integer.valueOf(5).equals(
                    XposedHelpers.callMethod(task, "getWindowingMode"))
                    && !NativeFreeformHandoff.keepNativeTransform(task, false))
                    || XposedHelpers.callMethod(task, "getRootTask") != task
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isVisible"))
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isVisibleRequested"))) return;
            // Foreground pin retains WM visibility while Shell hides its canonical
            // surface. Relative children would otherwise disappear with that task.
            if (isNativeForegroundPin(task)) return;
            Object surface = XposedHelpers.callMethod(task, "getSurfaceControl");
            if (surface != null && Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")))
                highestFreeform[0] = surface;
        };
        XposedHelpers.callMethod(display, "forAllTasks", pickFreeform, false);
        if (sNativeFreeformLayerAnchor != highestFreeform[0]) {
            // A newly excluded leaf does not undo a spec already on its shared ancestor.
            // Re-split that ancestor in this same WM transaction before showing freeform.
            if (sZoomed) ShellCompat.call(display, "reapplyMagnificationSpec", transaction);
            sNativeFreeformLayerAnchor = highestFreeform[0];
            log("native freeform anchor changed present=" + (highestFreeform[0] != null)
                    + " hostWindows=" + hosts.size());
            ZoomTarget target = sZoomTarget;
            if (target != null && target.display == display) target.handler.post(() -> {
                synchronized (target.globalLock) {
                    if (target == sZoomTarget)
                        ZoomTransactions.applyGestureSurfacesLocked(target, sZoomScale, sZoomOffset, sZoomOffsetX);
                }
            });
        }

        java.util.Iterator<java.util.Map.Entry<Object, NativeHostLayer>> entries =
                sNativeFreeformHostLayers.entrySet().iterator();
        while (entries.hasNext()) {
            java.util.Map.Entry<Object, NativeHostLayer> entry = entries.next();
            Object host = entry.getKey();
            NativeHostLayer state = entry.getValue();
            if (XposedHelpers.callMethod(host, "getSurfaceControl") != state.surface
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(state.surface, "isValid"))) {
                entries.remove();
                touchExclusionChanged = true;
                continue;
            }
            if (highestFreeform[0] != null && hosts.contains(host)) continue;
            rememberNativeHostNormalLayer(host, state);
            restoreNativeHostLayer(host, state, transaction);
            Object pending = XposedHelpers.callMethod(display, "getPendingTransaction");
            if (pending != transaction) restoreNativeHostLayer(host, state, pending);
            entries.remove();
            touchExclusionChanged = true;
        }
        if (highestFreeform[0] != null) {
            for (Object host : hosts) {
                Object surface = XposedHelpers.callMethod(host, "getSurfaceControl");
                if (surface == null || !Boolean.TRUE.equals(
                        XposedHelpers.callMethod(surface, "isValid"))) continue;
                NativeHostLayer state = sNativeFreeformHostLayers.get(host);
                if (state == null || state.surface != surface) {
                    state = new NativeHostLayer(surface);
                    sNativeFreeformHostLayers.put(host, state);
                }
                rememberNativeHostNormalLayer(host, state);
                WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                        XposedHelpers.getObjectField(host, "mAttrs");
                // The final visible root in this bottom-up walk is the highest.
                // Host wallpaper outside its main hole masks OEM drag overflow,
                // while the transparent main hole preserves the real float.
                // A root-relative +1 can still sit below its positive-Z client
                // children. Raise only our identified overlay group above that
                // subtree, preserving the order of our own content/transition layers.
                int layer = attrs.type == WindowTypes.STATUS_BAR ? -5 : 1_000_000 + (
                        "Clipboard".contentEquals(attrs.getTitle()) ? 4
                        : "OneStepLiteTransition".contentEquals(attrs.getTitle()) ? 3
                        : "OneStepLiteMirrorLayer".contentEquals(attrs.getTitle()) ? 2 : 1);
                if (state.nativeAnchor != highestFreeform[0]) touchExclusionChanged = true;
                XposedHelpers.callMethod(host, "assignRelativeLayer", transaction,
                        highestFreeform[0], layer, true);
                state.nativeAnchor = highestFreeform[0];
                state.nativeLayer = layer;
            }
        }
        Object touchManager = NativeFreeformTouchExclusionHooks.manager();
        if (touchExclusionChanged && touchManager != null) {
            try {
                XposedHelpers.callMethod(touchManager,
                        "updateMiuiFreeformTouchExcludeRegion", display);
            } catch (Throwable error) { log("native touch exclusion refresh skipped: " + error); }
        }
    }

    /** Exact Host overlays and main status chrome; unrelated system windows retain their native order. */
    static java.util.List<Object> workspaceOverlayWindows(Object display) {
        java.util.List<Object> hosts=new java.util.ArrayList<>();
        Consumer<Object> pickHost = window -> {
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                    XposedHelpers.getObjectField(window, "mAttrs");
            String title = String.valueOf(attrs.getTitle());
            boolean hostOverlay = ("WorkspaceHost".equals(title)
                    || "OneStepLiteTransition".equals(title) || "OneStepLiteMirrorLayer".equals(title)
                    || "Clipboard".equals(title))
                    && attrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            // This status bar has been magnified into the main region. Keep it
            // with main chrome, not above an OEM freeform crossing that region.
            boolean mainStatusBar = sZoomed && attrs.type == WindowTypes.STATUS_BAR && "StatusBar".equals(title);
            if (SYSTEMUI.equals(attrs.packageName) && (hostOverlay || mainStatusBar)
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(window, "isVisible"))) hosts.add(window);
        };
        XposedHelpers.callMethod(display, "forAllWindows", pickHost, false);
        return hosts;
    }

    static void rememberNativeHostNormalLayer(Object host, NativeHostLayer state) {
        int currentLayer = XposedHelpers.getIntField(host, "mLastLayer");
        Object currentRelative = XposedHelpers.getObjectField(host, "mLastRelativeToLayer");
        if (state.nativeAnchor == null || currentRelative != state.nativeAnchor
                || currentLayer != state.nativeLayer) {
            state.normalLayer = currentLayer;
            state.normalRelativeTo = currentRelative;
        }
    }

    static void restoreNativeHostLayer(Object host, NativeHostLayer state, Object transaction) {
        // assignLayer may return early while WM builds transition layers. Use its
        // SurfaceAnimator setter for our own window, preserving any animation leash.
        // Never change the OEM task's parent, layer, visibility or transform.
        if (state.normalRelativeTo == null) {
            XposedHelpers.callMethod(host, "setLayer", transaction, state.normalLayer);
        } else {
            XposedHelpers.callMethod(host, "setRelativeLayer", transaction,
                    state.normalRelativeTo, state.normalLayer);
        }
        XposedHelpers.setIntField(host, "mLastLayer", state.normalLayer);
        XposedHelpers.setObjectField(host, "mLastRelativeToLayer", state.normalRelativeTo);
    }

}
