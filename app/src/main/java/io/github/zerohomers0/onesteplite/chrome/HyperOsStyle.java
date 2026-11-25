package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

final class HyperOsStyle {
    static final int CONTROL_ACCENT = 0xff3482ff;
    private static final int DARK_PAGE = -15592682;
    private static final int DARK_TEXT_SECONDARY = -5657163;
    private static final int FALLBACK_ACCENT_DARK = -8800001;
    private static final int FALLBACK_ACCENT_LIGHT = -13664019;
    private static final int LIGHT_TEXT = -15263717;
    private static final int LIGHT_TEXT_SECONDARY = -8946814;
    private static Bitmap shareGrain;

    private HyperOsStyle() {
    }

    static int controlFill(Context context, boolean selected) {
        return selected ? CONTROL_ACCENT : dark(context) ? 0xff363940 : 0xffe5e8ee;
    }

    /** The tile is shared, but each animated face owns its drawable bounds. */
    static synchronized Drawable shareRailBackground(Context context) {
        if(shareGrain==null){
            int[] pixels=new int[64*64];int seed=0x51ac27;
            for(int i=0;i<pixels.length;i++){
                seed=seed*1664525+1013904223;int grain=((seed>>>24)&63)-31;
                pixels[i]=Color.rgb(170+grain,190+grain,38+grain);
            }
            shareGrain=Bitmap.createBitmap(pixels,64,64,Bitmap.Config.ARGB_8888);
            shareGrain.setDensity(Bitmap.DENSITY_NONE);
        }
        android.graphics.drawable.BitmapDrawable background=new android.graphics.drawable.BitmapDrawable(context.getResources(),shareGrain);
        background.setTileModeXY(android.graphics.Shader.TileMode.REPEAT,android.graphics.Shader.TileMode.REPEAT);
        background.setFilterBitmap(false);
        return background;
    }

    /** Shared tonal surface and bounded ripple for workspace and settings actions. */
    static Drawable controlButton(Context context, boolean selected, float radius) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(controlFill(context, selected));
        base.setCornerRadius(dp(context, radius));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(context, radius));
        return new RippleDrawable(ColorStateList.valueOf(selected || dark(context)
                ? 0x24ffffff : 0x12000000), base, mask);
    }

    private static volatile int themeMode;
    static void theme(int mode){themeMode=mode;}

    static boolean dark(Context context) {
        if(themeMode!=0)return themeMode==2;
        return (context.getResources().getConfiguration().uiMode & 48) == 32;
    }

    static int textPrimary(Context context) {
        if (dark(context)) {
            return -657672;
        }
        return LIGHT_TEXT;
    }

    static int textSecondary(Context context) {
        return dark(context) ? DARK_TEXT_SECONDARY : LIGHT_TEXT_SECONDARY;
    }

    /** Choose the higher-contrast black or white ink from the wallpaper under a view. */
    static int wallpaperTextColor(Bitmap bitmap, int screenWidth, int screenHeight,
            int left, int top, int width, int height, int fallback) {
        if (bitmap == null || bitmap.isRecycled() || screenWidth <= 0 || screenHeight <= 0
                || width <= 0 || height <= 0) return fallback;
        try {
            double luminance = 0;
            for (int row = 0; row < 4; row++) for (int column = 0; column < 8; column++) {
                int x = Math.max(0, Math.min(bitmap.getWidth() - 1,
                        (int) ((left + width * (column + .5) / 8) * bitmap.getWidth() / screenWidth)));
                int y = Math.max(0, Math.min(bitmap.getHeight() - 1,
                        (int) ((top + height * (row + .5) / 4) * bitmap.getHeight() / screenHeight)));
                int pixel = bitmap.getPixel(x, y);
                double red = ((pixel >> 16) & 255) / 255.0;
                double green = ((pixel >> 8) & 255) / 255.0;
                double blue = (pixel & 255) / 255.0;
                red = red <= .04045 ? red / 12.92 : Math.pow((red + .055) / 1.055, 2.4);
                green = green <= .04045 ? green / 12.92 : Math.pow((green + .055) / 1.055, 2.4);
                blue = blue <= .04045 ? blue / 12.92 : Math.pow((blue + .055) / 1.055, 2.4);
                luminance += .2126 * red + .7152 * green + .0722 * blue;
            }
            return luminance / 32 < .179 ? 0xffffffff : 0xff000000;
        } catch (RuntimeException unavailable) { return fallback; }
    }

    static int page(Context context) {
        if (dark(context)) {
            return DARK_PAGE;
        }
        return -657672;
    }

    static int stroke(Context context) {
        return dark(context) ? 620756991 : 603979776;
    }

    static int accent(Context context) {
        int identifier = context.getResources().getIdentifier("system_accent1_200", "color", "android");
        if (identifier != 0) {
            try {
                return context.getResources().getColor(identifier, context.getTheme());
            } catch (Throwable th) {
                /* best effort: fall back to the bundled accent below */
            }
        }
        return dark(context) ? FALLBACK_ACCENT_DARK : FALLBACK_ACCENT_LIGHT;
    }

    static Drawable topBackground(Context context) {
        boolean zDark = dark(context);
        int i = zDark ? -15263716 : -394500;
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{blend(i, zDark ? -12371120 : -729880, zDark ? 0.07f : 0.1f), blend(i, accent(context), zDark ? 0.12f : 0.085f), blend(i, zDark ? -14857392 : -2493713, zDark ? 0.06f : 0.1f)});
    }

    static int topWallpaperTint(Context context) {
        return dark(context)?0x99080a10:0x26ffffff;
    }

    static Bitmap blurredWallpaper(Context context, int i, int i2) throws Exception {
        Bitmap bitmapCreateBitmap = WallpaperSnapshot.capture(context, i, i2);
        if (bitmapCreateBitmap == null) return null;
        try {
            return blurredBitmap(bitmapCreateBitmap, i, i2);
        } finally {
            bitmapCreateBitmap.recycle();
        }
    }

    static Bitmap blurredBitmap(Bitmap bitmap, int i, int i2) {
        if (bitmap == null || bitmap.isRecycled() || i <= 0 || i2 <= 0) {
            return null;
        }
        int iMax = Math.max(1, i / 3);
        int iMax2 = Math.max(1, i2 / 3);
        Bitmap bitmapCreateBitmap = Bitmap.createBitmap(iMax, iMax2, Bitmap.Config.ARGB_8888);
        new Canvas(bitmapCreateBitmap).drawBitmap(bitmap, (Rect) null, new RectF(0.0f, 0.0f, iMax, iMax2), new Paint(6));
        int[] iArrBoxBlur = new int[iMax * iMax2];
        bitmapCreateBitmap.getPixels(iArrBoxBlur, 0, iMax, 0, 0, iMax, iMax2);
        int iMax3 = Math.max(4, Math.min(iMax, iMax2) / 38);
        int[] scratch = new int[iArrBoxBlur.length];
        for (int i3 = 0; i3 < 3; i3++) {
            boxBlur(iArrBoxBlur, scratch, iMax, iMax2, iMax3);
            int[] previous = iArrBoxBlur;
            iArrBoxBlur = scratch;
            scratch = previous;
        }
        bitmapCreateBitmap.setPixels(iArrBoxBlur, 0, iMax, 0, 0, iMax, iMax2);
        Bitmap bitmapCreateBitmap2 = Bitmap.createBitmap(i, i2, Bitmap.Config.ARGB_8888);
        new Canvas(bitmapCreateBitmap2).drawBitmap(bitmapCreateBitmap, (Rect) null, new RectF(0.0f, 0.0f, i, i2), new Paint(6));
        bitmapCreateBitmap.recycle();
        return bitmapCreateBitmap2;
    }

    private static void boxBlur(int[] source, int[] target, int width, int height, int radius) {
        // Preserve the legacy truncated windows: the sample count grows and shrinks only as edges enter or leave.
        int[] horizontal = new int[source.length];
        for (int y = 0; y < height; y++) {
            int row = y * width, red = 0, green = 0, blue = 0;
            int count = Math.min(width, radius + 1);
            for (int x = 0; x < count; x++) {
                int pixel = source[row + x];
                red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel);
            }
            for (int x = 0; x < width; x++) {
                horizontal[row + x] = Color.rgb(red / count, green / count, blue / count);
                int removeX = x - radius, addX = x + radius + 1;
                if (removeX >= 0) {
                    int pixel = source[row + removeX];
                    red -= Color.red(pixel); green -= Color.green(pixel); blue -= Color.blue(pixel);
                    count--;
                }
                if (addX < width) {
                    int pixel = source[row + addX];
                    red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel);
                    count++;
                }
            }
        }
        for (int x = 0; x < width; x++) {
            int red = 0, green = 0, blue = 0;
            int count = Math.min(height, radius + 1);
            for (int y = 0; y < count; y++) {
                int pixel = horizontal[y * width + x];
                red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel);
            }
            for (int y = 0; y < height; y++) {
                target[y * width + x] = Color.rgb(red / count, green / count, blue / count);
                int removeY = y - radius, addY = y + radius + 1;
                if (removeY >= 0) {
                    int pixel = horizontal[removeY * width + x];
                    red -= Color.red(pixel); green -= Color.green(pixel); blue -= Color.blue(pixel);
                    count--;
                }
                if (addY < height) {
                    int pixel = horizontal[addY * width + x];
                    red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel);
                    count++;
                }
            }
        }
    }

    static Drawable glassSheet(Context context, float f) {
        GradientDrawable gradientDrawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{dark(context) ? -265934803 : 0xf8edf1f7, dark(context) ? -317187554 : 0xeae2e9f2});
        gradientDrawable.setCornerRadius(dp(context, f));
        gradientDrawable.setStroke(Math.max(1, dp(context, 0.75f)), stroke(context));
        return gradientDrawable;
    }

    static Drawable emptyCell(Context context) {
        int[] iArr;
        boolean zDark = dark(context);
        GradientDrawable.Orientation orientation = GradientDrawable.Orientation.TL_BR;
        if (zDark) {
            iArr = new int[]{-14408149, -15000287};
        } else {
            iArr = new int[]{-526086, -1183500};
        }
        GradientDrawable gradientDrawable = new GradientDrawable(orientation, iArr);
        gradientDrawable.setCornerRadius(0.0f);
        return gradientDrawable;
    }

    static int dp(Context context, float f) {
        return Math.round(f * context.getResources().getDisplayMetrics().density);
    }

    private static int blend(int i, int i2, float f) {
        float fMax = Math.max(0.0f, Math.min(1.0f, f));
        return Color.rgb(Math.round(Color.red(i) + ((Color.red(i2) - Color.red(i)) * fMax)), Math.round(Color.green(i) + ((Color.green(i2) - Color.green(i)) * fMax)), Math.round(Color.blue(i) + ((Color.blue(i2) - Color.blue(i)) * fMax)));
    }
}
