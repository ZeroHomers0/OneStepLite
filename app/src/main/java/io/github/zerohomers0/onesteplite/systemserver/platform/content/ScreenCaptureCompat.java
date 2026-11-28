package io.github.zerohomers0.onesteplite;

import android.graphics.Bitmap;
import android.graphics.Rect;
import de.robv.android.xposed.XposedHelpers;

/** Display capture used by screenshot and preview flows. */
final class ScreenCaptureCompat {
    private ScreenCaptureCompat(){}

    /** Android 14-16 expose ScreenCapture; Android 17 moved the same API to ScreenCaptureInternal. */
    static Bitmap capture(ClassLoader cl, int width, int height) throws Throwable {
        return captureRegion(cl,width,height,null);
    }

    /** Capture the composed app children too; PixelCopy of a TaskView omits those layers. */
    static Bitmap captureRegion(ClassLoader cl, int width, int height, Rect crop) throws Throwable {
        return captureRegion(cl,width,height,crop,null);
    }

    /** Exclude only our transition overlay when capturing the destination task. */
    static Bitmap captureRegion(ClassLoader cl, int width, int height, Rect crop, Object exclude) throws Throwable {
        Class<?> capture = captureClass(cl);
        Class<?> builder = XposedHelpers.findClass(capture.getName() + "$CaptureArgs$Builder", cl);
        Object args = XposedHelpers.newInstance(builder);
        optional(args, "setFrameScale", 1f);
        optional(args, "setIncludeSystemOverlays", true);
        if(crop!=null)XposedHelpers.callMethod(args,"setSourceCrop",new Rect(crop));
        if(exclude!=null) {
            Class<?> surface=XposedHelpers.findClass("android.view.SurfaceControl",cl);
            Object layers=java.lang.reflect.Array.newInstance(surface,1);
            java.lang.reflect.Array.set(layers,0,exclude);
            optional(args, "setExcludeLayers", layers);
        }
        Object listener = XposedHelpers.callStaticMethod(capture, "createSyncCaptureListener");
        Class<?> global = XposedHelpers.findClass("android.view.WindowManagerGlobal", cl);
        Object wm = XposedHelpers.callStaticMethod(global, "getWindowManagerService");
        captureDisplay(wm, XposedHelpers.callMethod(args, "build"), listener);
        Object result = XposedHelpers.callMethod(listener, "getBuffer");
        if (result == null) throw new IllegalStateException("display capture unavailable");
        Object hardware = XposedHelpers.callMethod(result, "getHardwareBuffer");
        try {
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(result, "containsSecureLayers")))
                throw new IllegalStateException("secure scene has no preview");
            Bitmap bitmap = (Bitmap) XposedHelpers.callMethod(result, "asBitmap");
            if (bitmap == null) throw new IllegalStateException("capture has no bitmap");
            // Copy once: the overlay must not retain a hardware buffer or a whole screen.
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            bitmap.recycle();
            int expectedWidth=crop==null?width:crop.width(),expectedHeight=crop==null?height:crop.height();
            if (copy == null || copy.getWidth() != expectedWidth || copy.getHeight() != expectedHeight) {
                if(copy!=null)copy.recycle();
                throw new IllegalStateException("capture geometry changed");
            }
            return copy;
        } finally {
            if (hardware != null) XposedHelpers.callMethod(hardware, "close");
        }
    }

    private static Class<?> captureClass(ClassLoader cl) {
        try {
            return XposedHelpers.findClass("android.window.ScreenCaptureInternal", cl);
        } catch (Throwable newerOnly) {
            return XposedHelpers.findClass("android.window.ScreenCapture", cl);
        }
    }

    private static void captureDisplay(Object wm, Object args, Object listener) {
        if (hasMethod(wm.getClass(), "captureDisplay", 3)) {
            XposedHelpers.callMethod(wm, "captureDisplay", 0, args, listener);
        } else {
            XposedHelpers.callMethod(wm, "captureDisplay", args, listener);
        }
    }

    private static void optional(Object target, String method, Object... args) {
        if (hasMethod(target.getClass(), method, args.length)) {
            XposedHelpers.callMethod(target, method, args);
        }
        // Older ROMs legitimately omit newer capture options.
    }

    private static boolean hasMethod(Class<?> type, String name, int parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            for (java.lang.reflect.Method method : current.getDeclaredMethods())
                if (method.getName().equals(name) && method.getParameterCount() == parameters) return true;
        for (Class<?> implemented : type.getInterfaces())
            if (hasMethod(implemented, name, parameters)) return true;
        return false;
    }
}
