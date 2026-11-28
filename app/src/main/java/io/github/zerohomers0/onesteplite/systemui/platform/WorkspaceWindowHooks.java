package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Installs main-pane animation bounds and recents wallpaper composition hooks. */
final class WorkspaceWindowHooks {
    private final Consumer<String> logger;
    WorkspaceWindowHooks(Consumer<String> logger) { this.logger=logger; }
    private void log(String text) { logger.accept(text); }
    void hookTransitionBounds(ClassLoader cl) {
        // Native windows now share the main parent coordinate system.
        // Physical/main conversion and pre-return position suppression are unnecessary.
        try {
            Class<?> util = XposedHelpers.findClass(
                    "com.android.wm.shell.common.transition.MiuiTransitionHandlerUtil", cl);
            XposedBridge.hookAllMethods(util, "getAnimBounds", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    Rect main = WorkspaceWindowLayout.activeMainBounds();
                    if (!(result instanceof Rect) || main == null || param.args == null
                            || param.args.length < 2
                            || !WorkspaceNativeHooks.shouldClampAnimationBounds(
                                    param.args[0], param.args[1])) return;
                    Rect original = (Rect) result;
                    Rect clipped = TransitionBounds.clamp(original, main);
                    if (clipped == original || clipped.equals(original)) return;
                    param.setResult(clipped);
                    log("transition animation clipped " + original + " -> " + clipped
                            + " main=" + main);
                }
            });
            log("MIUI transition animation bounds clamped to active main pane");
        } catch (Throwable error) {
            log("transition animation clamp unavailable: " + error);
        }

    }

    void hookRecentsWallpaper(ClassLoader cl) {
        try {
            Class<?> util = XposedHelpers.findClass("com.android.wm.shell.shared.TransitionUtil", cl);
            XposedBridge.hookAllMethods(util, "newTarget", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.getResult() == null) return;
                    int offset = RecentsWallpaper.argumentOffset(p.args);
                    if (offset < 0) return;
                    try {
                        if (RecentsWallpaper.stage(p.args[2 + offset], p.args[0],
                                XposedHelpers.getObjectField(p.getResult(), "leash"), p.args[3 + offset],
                                WorkspaceWindowLayout.activeMainTransform(), WorkspaceWindowLayout.activeMainBounds(),
                                (java.util.Map) p.args[4 + offset]))
                            log("Recents wallpaper capture fill staged behind workspace wallpaper");
                    } catch (Throwable error) {
                        log("Recents wallpaper capture unchanged: " + error);
                    }
                }
            });
        } catch (Throwable error) { log("Recents wallpaper hook unavailable: " + error); }
    }
}
