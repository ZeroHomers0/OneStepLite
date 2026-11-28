package io.github.zerohomers0.onesteplite;

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.PowerManager;
import de.robv.android.xposed.XposedHelpers;

/** Capture only the wallpaper layer; never cache an app frame or lock-screen image. */
final class WallpaperSnapshot {
    static boolean canCapture(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager lock = context.getSystemService(KeyguardManager.class);
        return power != null && power.isInteractive() && lock != null && !lock.isKeyguardLocked();
    }
    static Bitmap capture(Context context, int width, int height) {
        if (width <= 0 || height <= 0 || !canCapture(context)) return null;
        Bitmap source = null;
        try {
            Object wm = XposedHelpers.callStaticMethod(Class.forName("android.view.WindowManagerGlobal"),
                    "getWindowManagerService");
            source = (Bitmap) XposedHelpers.callMethod(wm, "screenshotWallpaper");
            if (source != null) {
                Bitmap software = source.getConfig() == Bitmap.Config.HARDWARE
                        ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
                try {
                    if (software != null) {
                        Bitmap ready=fit(software, width, height);
                        if (!canCapture(context)) { ready.recycle(); return null; }
                        return ready;
                    }
                } finally { if (software != null && software != source) software.recycle(); }
            }
        } catch (Throwable unavailable) { /* Static wallpaper fallback below. */ }
        finally { if (source != null) source.recycle(); }
        WallpaperManager manager = WallpaperManager.getInstance(context);
        // Live wallpapers may expose an unrelated old static image through getDrawable().
        if (manager.getWallpaperInfo() != null || !canCapture(context)) return null;
        manager.forgetLoadedWallpaper();
        Drawable wallpaper = manager.getDrawable();
        if (wallpaper == null) return null;
        if (wallpaper.getConstantState() != null)
            wallpaper=wallpaper.getConstantState().newDrawable(context.getResources()).mutate();
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        float scale = Math.max(width / (float) Math.max(1, wallpaper.getIntrinsicWidth()),
                height / (float) Math.max(1, wallpaper.getIntrinsicHeight()));
        int w = Math.round(Math.max(1, wallpaper.getIntrinsicWidth()) * scale);
        int h = Math.round(Math.max(1, wallpaper.getIntrinsicHeight()) * scale);
        wallpaper.setBounds((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2);
        wallpaper.draw(new Canvas(result));
        if (!canCapture(context)) { result.recycle(); return null; }
        return result;
    }
    static Bitmap fit(Bitmap source, int width, int height) {
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        float scale = Math.max(width / (float) source.getWidth(), height / (float) source.getHeight());
        canvas.translate((width-source.getWidth()*scale)/2, (height-source.getHeight()*scale)/2);
        canvas.scale(scale, scale);
        canvas.drawBitmap(source, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        return result;
    }
    private WallpaperSnapshot() {}
}
