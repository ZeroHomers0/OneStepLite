package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Owns experimental mirror surfaces, their overlay and timed source restoration. */
final class MirrorExperiment {
    interface Host {
        int resolveTask(int requested) throws Throwable;
        Object leash(int task) throws Throwable;
        View root();
        int width();
        int height();
        boolean direct(Object leash,Rect destination);
        void restoreDirect(Object leash);
    }
    private final Context ctx;
    private final Handler main;
    private final Host host;
    private final Consumer<String> logger;
    private final Runnable timeout=this::stopMirrorPoc;
    private Object mirrorPocSurface,mirrorPocLeash;
    private int mirrorPocHideMode;
    private FrameLayout mirrorLayerRoot;
    private WindowManager mirrorLayerWm;
    private WindowManager.LayoutParams mirrorLayerParams;

    MirrorExperiment(Context context,Handler handler,Host host,Consumer<String> logger) {
        ctx=context;main=handler;this.host=host;this.logger=logger;
    }
    private void log(String text) { logger.accept(text); }
    void createMirrorLayer() {
        if (mirrorLayerRoot != null) return;
        try {
            mirrorLayerRoot = new FrameLayout(ctx);
            mirrorLayerRoot.setBackgroundColor(0x00000000);
            mirrorLayerWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            mirrorLayerParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            mirrorLayerParams.gravity = Gravity.TOP | Gravity.START;
            mirrorLayerParams.setTitle("OneStepLiteMirrorLayer");
            HostWindowPolicy.configure(mirrorLayerParams);
            try { mirrorLayerParams.setFitInsetsTypes(0); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            try {
                mirrorLayerParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            mirrorLayerWm.addView(mirrorLayerRoot, mirrorLayerParams);
            log("mirror layer window added");
        } catch (Throwable error) {
            log("mirror layer window failed: " + error);
            mirrorLayerRoot = null;
        }
    }

    void setMirrorLayerVisible(boolean visible) {
        if (mirrorLayerRoot == null) return;
        try {
            if (mirrorLayerRoot.getVisibility() != (visible ? View.VISIBLE : View.GONE)) {
                mirrorLayerRoot.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
    }

    private Object mirrorLayerSurface() {
        try {
            if (mirrorLayerRoot == null) return null;
            Object viewRoot = XposedHelpers.callMethod(mirrorLayerRoot, "getViewRootImpl");
            return viewRoot == null ? null
                    : XposedHelpers.callMethod(viewRoot, "getSurfaceControl");
        } catch (Throwable error) {
            log("mirror layer surface unavailable: " + error);
            return null;
        }
    }

    void startMirrorPoc(int requestedTask, Rect dest, int holdMs, int hideMode) {
        try {
            stopMirrorPoc();
            requestedTask = host.resolveTask(requestedTask);
            if (requestedTask < 0) return;
            Object leash = host.leash(requestedTask);
            if (leash == null) return;
            if (hideMode == 5) {
                showDirectCarrier(requestedTask, leash, dest, holdMs);
                return;
            }
            if (!showMirrorSurface(requestedTask, leash, dest, hideMode)) return;
            main.postDelayed(timeout, holdMs);
        } catch (Throwable error) {
            log("mirror POC failed: " + error);
            XposedBridge.log(error);
            stopMirrorPoc();
        }
    }

    private void showDirectCarrier(int task, Object leash, Rect dest, int holdMs) throws Throwable {
        if (!host.direct(leash, dest)) return;
        mirrorPocLeash = leash;
        mirrorPocHideMode = 5;
        log("direct carrier shown task=" + task + " dest=" + dest);
        main.postDelayed(timeout, holdMs);
    }

    private boolean showMirrorSurface(int task, Object leash, Rect dest, int hideMode) throws Throwable {
        Object parent = mirrorLayerSurface();
        if (parent == null) {
            Object viewRoot = XposedHelpers.callMethod(host.root(), "getViewRootImpl");
            parent = viewRoot == null ? null
                    : XposedHelpers.callMethod(viewRoot, "getSurfaceControl");
        }
        if (parent == null) {
            log("mirror POC: host window SurfaceControl unavailable");
            return false;
        }
        Class<?> scClass = Class.forName("android.view.SurfaceControl");
        Object mirror = XposedHelpers.callStaticMethod(scClass, "mirrorSurface", leash);
        float sx = dest.width() / (float) host.width();
        float sy = dest.height() / (float) host.height();
        Object tx = TransactionCompat.newSurfaceTransaction();
        try {
            hideMirrorSource(tx, leash, hideMode);
            XposedHelpers.callMethod(tx, "reparent", mirror, parent);
            XposedHelpers.callMethod(tx, "setLayer", mirror, 1000000);
            XposedHelpers.callMethod(tx, "setPosition", mirror,
                    (float) dest.left, (float) dest.top);
            XposedHelpers.callMethod(tx, "setMatrix", mirror, sx, 0f, 0f, sy);
            XposedHelpers.callMethod(tx, "setAlpha", mirror, 1f);
            XposedHelpers.callMethod(tx, "show", mirror);
            XposedHelpers.callMethod(tx, "apply");
        } finally {
            try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
        }
        mirrorPocSurface = mirror;
        mirrorPocLeash = leash;
        mirrorPocHideMode = hideMode;
        log("mirror POC shown task=" + task + " dest=" + dest
                + " scale=" + sx + "x" + sy + " hideMode=" + hideMode
                + " leash=" + leash + " parent=" + parent);
        return true;
    }

    private void hideMirrorSource(Object tx, Object leash, int mode) {
        try {
            switch (mode) {
                case 1:
                    XposedHelpers.callMethod(tx, "setAlpha", leash, 0f);
                    break;
                case 2:
                    XposedHelpers.callMethod(tx, "setPosition", leash, -4000f, -4000f);
                    break;
                case 3:
                    XposedHelpers.callMethod(tx, "setCrop", leash, new Rect(0, 0, 0, 0));
                    break;
                case 4:
                    XposedHelpers.callMethod(tx, "hide", leash);
                    break;
                default:
                    break;
            }
        } catch (Throwable error) {
            log("mirror hide mode " + mode + " failed: " + error);
        }
    }

    private void restoreMirrorSource(Object tx, Object leash, int mode) {
        try {
            switch (mode) {
                case 1:
                    XposedHelpers.callMethod(tx, "setAlpha", leash, 1f);
                    break;
                case 2:
                    XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f);
                    break;
                case 3:
                    XposedHelpers.callMethod(tx, "setCrop",
                            new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                    android.graphics.Rect.class}, leash, null);
                    break;
                case 4:
                    XposedHelpers.callMethod(tx, "show", leash);
                    break;
                default:
                    break;
            }
        } catch (Throwable error) {
            log("mirror restore mode " + mode + " failed: " + error);
        }
    }

    void stopMirrorPoc() {
        main.removeCallbacks(timeout);
        Object mirror = mirrorPocSurface;
        Object leash = mirrorPocLeash;
        int hideMode = mirrorPocHideMode;
        mirrorPocSurface = null;
        mirrorPocLeash = null;
        mirrorPocHideMode = 0;
        if (hideMode == 5) {
            if (leash != null && Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                host.restoreDirect(leash);
            }
            return;
        }
        if (mirror == null) return;
        try {
            Object tx = TransactionCompat.newSurfaceTransaction();
            try {
                if (hideMode != 0 && leash != null
                        && Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                    restoreMirrorSource(tx, leash, hideMode);
                }
                XposedHelpers.callMethod(tx, "remove", mirror);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            }
            XposedHelpers.callMethod(mirror, "release");
            log("mirror POC removed; restoredHideMode=" + hideMode);
        } catch (Throwable error) {
            log("mirror POC cleanup failed: " + error);
        }
    }
}
