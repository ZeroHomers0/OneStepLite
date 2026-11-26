package io.github.zerohomers0.onesteplite;

import android.graphics.Bitmap;
import android.graphics.Rect;
import de.robv.android.xposed.XposedHelpers;

/** Display capture used by screenshot and preview flows. */
final class ScreenCaptureCompat {
    private ScreenCaptureCompat(){}

    /** Target Android 17 moved capture APIs to *Internal; WMS resolves display 0. */
    static Bitmap capture(ClassLoader cl, int width, int height) throws Throwable {
        return captureRegion(cl,width,height,null);
    }

    /** Capture the composed app children too; PixelCopy of a TaskView omits those layers. */
    static Bitmap captureRegion(ClassLoader cl, int width, int height, Rect crop) throws Throwable {
        return captureRegion(cl,width,height,crop,null);
    }

    /** Exclude only our transition overlay when capturing the destination task. */
    static Bitmap captureRegion(ClassLoader cl, int width, int height, Rect crop, Object exclude) throws Throwable {
        Class<?> builder = XposedHelpers.findClass(
                "android.window.ScreenCaptureInternal$CaptureArgs$Builder", cl);
        Object args = XposedHelpers.newInstance(builder);
        XposedHelpers.callMethod(args, "setFrameScale", 1f);
        XposedHelpers.callMethod(args, "setIncludeSystemOverlays", true);
        if(crop!=null)XposedHelpers.callMethod(args,"setSourceCrop",new Rect(crop));
        if(exclude!=null) {
            Class<?> surface=XposedHelpers.findClass("android.view.SurfaceControl",cl);
            Object layers=java.lang.reflect.Array.newInstance(surface,1);
            java.lang.reflect.Array.set(layers,0,exclude);
            XposedHelpers.callMethod(args,"setExcludeLayers",layers);
        }
        Class<?> capture = XposedHelpers.findClass("android.window.ScreenCaptureInternal", cl);
        Object listener = XposedHelpers.callStaticMethod(capture, "createSyncCaptureListener");
        Class<?> global = XposedHelpers.findClass("android.view.WindowManagerGlobal", cl);
        Object wm = XposedHelpers.callStaticMethod(global, "getWindowManagerService");
        XposedHelpers.callMethod(wm, "captureDisplay", 0,
                XposedHelpers.callMethod(args, "build"), listener);
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
}
