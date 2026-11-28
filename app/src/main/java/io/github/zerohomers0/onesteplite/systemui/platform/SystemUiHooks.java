package io.github.zerohomers0.onesteplite;

import android.content.Context;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;

/** SystemUI service lifecycle capture without owning startup scheduling. */
final class SystemUiHooks {
    private SystemUiHooks() {}

    static void install(ClassLoader loader, Consumer<Context> onCreated, Consumer<String> log) {
        HookGuard.install("SystemUI service lifecycle", loader, log, hooks -> {
            Class<?> service = hooks.findClass("com.android.systemui.SystemUIService");
            hooks.hookAllMethods(service, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Context)
                        onCreated.accept((Context) param.thisObject);
                }
            });
        });
    }
}