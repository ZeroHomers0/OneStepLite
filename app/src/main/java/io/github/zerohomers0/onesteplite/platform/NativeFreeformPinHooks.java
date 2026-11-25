package io.github.zerohomers0.onesteplite;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Captures OEM foreground-pin state and releases Host layers before Shell hides the task. */
final class NativeFreeformPinHooks {
    private static volatile Object manager;
    private NativeFreeformPinHooks() {}

    static Object manager() { return manager; }
    static void setManager(Object value) { manager=value; }

    static void install(ClassLoader loader, BiConsumer<Object,Integer> release, Consumer<String> log) {
        try {
            Class<?> service=XposedHelpers.findClass("com.android.server.wm.MiuiFreeFormManagerService",loader);
            try {
                manager=XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                        "com.android.server.wm.MiuiFreeFormManagerServiceStub",loader),"getInstance");
            } catch (Throwable notCreatedYet) { /* First pin decision supplies the live instance. */ }
            XposedHelpers.findAndHookMethod(service,"shouldForegroundPin",int.class,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if(p.hasThrowable())return;
                    manager=p.thisObject;
                    if(Boolean.TRUE.equals(p.getResult()))release.accept(p.thisObject,(Integer)p.args[0]);
                }
            });
            log.accept("native foreground pin releases host layers before Shell hides task");
        } catch (Throwable error) {
            log.accept("native foreground pin hook unavailable: "+error);
        }
    }
}
