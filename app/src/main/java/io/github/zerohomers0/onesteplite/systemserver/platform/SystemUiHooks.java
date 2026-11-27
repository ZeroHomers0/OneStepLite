package io.github.zerohomers0.onesteplite;

import android.content.Context;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** SystemUI service lifecycle capture without owning startup scheduling. */
final class SystemUiHooks {
    private SystemUiHooks() {}

    static void install(ClassLoader loader, Consumer<Context> onCreated, Consumer<String> log) {
        try {
            Class<?> service = XposedHelpers.findClass("com.android.systemui.SystemUIService", loader);
            XposedBridge.hookAllMethods(service, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Context)
                        onCreated.accept((Context) param.thisObject);
                }
            });
            log.accept("hooked SystemUIService#onCreate");
        } catch (Throwable error) {
            log.accept("could not hook SystemUIService: " + error);
        }
    }
}
