package io.github.zerohomers0.onesteplite;

import android.content.Context;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Tablet MiType auto-floating policy; ordinary phone IME behavior stays native. */
final class MiTypeHooks {
    private MiTypeHooks() {}

    static void install(ClassLoader loader, String marker, Consumer<String> log) {
        log.accept("=== attached to com.xiaomi.type " + marker);
        try {
            Class<?> service = XposedHelpers.findClass("com.mi.ime.MiInputMethodService", loader);
            XposedHelpers.findAndHookMethod(service, "handleSpacePressureAutoFloating",
                    android.view.inputmethod.EditorInfo.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Context)) return;
                    Context context = (Context) param.thisObject;
                    try {
                        if (!DeviceProfile.isTablet(context)) return;
                        long now = System.currentTimeMillis();
                        String encoded = android.provider.Settings.Global.getString(
                                context.getContentResolver(), ZoomProtocol.SETTINGS_KEY);
                        if (!ImePolicy.suppressAutomaticFloating(encoded,
                                now - android.os.SystemClock.elapsedRealtime(), now)) return;
                    } catch (Throwable unavailable) {
                        return;
                    }
                    param.setResult(null);
                    log.accept("MiType automatic landscape floating suppressed");
                }
            });
            log.accept("MiType auto-floating decision hook installed");
        } catch (Throwable error) {
            log.accept("MiType auto-floating hook unavailable: " + error);
        }
    }
}
