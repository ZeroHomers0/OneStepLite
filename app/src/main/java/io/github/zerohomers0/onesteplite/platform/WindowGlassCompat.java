package io.github.zerohomers0.onesteplite;

import android.graphics.PixelFormat;
import android.view.Window;

/** Enables blur only on our floating PhoneWindow, never on a source application's window. */
final class WindowGlassCompat {
    private WindowGlassCompat(){}
    @android.annotation.SuppressLint("BlockedPrivateApi") // LSPosed SystemUI compatibility; opaque fallback if access is denied.
    static boolean enable(Window window) {
        try {
            if(!window.isFloating())return false;
            Class<?> phone=Class.forName("com.android.internal.policy.PhoneWindow");
            if(!phone.isInstance(window))return false;
            java.lang.reflect.Field translucent=phone.getDeclaredField("mIsTranslucent");
            translucent.setAccessible(true);translucent.setBoolean(window,true);
            window.setFormat(PixelFormat.TRANSLUCENT);
            return Boolean.TRUE.equals(phone.getMethod("isTranslucent").invoke(window));
        }catch(ReflectiveOperationException | RuntimeException unavailable){return false;}
    }
}
