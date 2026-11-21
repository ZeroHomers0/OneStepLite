package io.github.zerohomers0.onesteplite;

/** Decides which windows the magnification tree must force into the zoom path. */
final class InputMagnificationPolicy {
    private static final int PRIVATE_FLAG_EXCLUDE_FROM_MAGNIFICATION = 0x08000000;

    private InputMagnificationPolicy() {}

    static boolean shouldForceMagnification(int windowType, int privateFlags) {
        if (windowType == WindowTypes.MAGNIFICATION_OVERLAY || windowType == WindowTypes.BUBBLE_LAYER
                || windowType == WindowTypes.BRIGHTNESS_LAYER) return false;
        if (windowType == 2011 || windowType == 2012 || windowType == WindowTypes.GESTURE_NAVIGATION) return true;
        return (privateFlags & PRIVATE_FLAG_EXCLUDE_FROM_MAGNIFICATION) != 0;
    }

    static boolean shouldForceMagnification(int windowType, int privateFlags,
            String packageName, CharSequence title) {
        if (shouldExcludeFromMagnification(windowType, packageName, title)) return false;
        // OS4's real permission capsule, dot, RemoteViews and touch area share
        // this window. Admit the whole native window, without changing its state.
        if (windowType == WindowTypes.SYSTEM_UI_DIALOG && Packages.SYSTEM_UI.equals(packageName)
                && title != null && "FlaresView".contentEquals(title)) return true;
        // The independent pin icon window must share WM's visual and input
        // transform. Other bubble-layer gesture windows keep their native policy.
        if (windowType == WindowTypes.BUBBLE_LAYER && Packages.SYSTEM_UI.equals(packageName)
                && title != null && "MiuiBubbles".contentEquals(title)) return true;
        return shouldForceMagnification(windowType, privateFlags);
    }

    static boolean shouldExcludeFromMagnification(int windowType,
            String packageName, CharSequence title) {
        if (!Packages.SYSTEM_UI.equals(packageName) || title == null) return false;
        if (windowType == 2018)
            return "gxzw_icon".contentEquals(title) || "gxzw_touch".contentEquals(title);
        if (windowType == 2015) return "gxzw_anim".contentEquals(title);
        if (windowType == 2009) return "OneStepLiteNativeBiometricPrompt".contentEquals(title);
        return windowType == 2021 && "hbm_overlay".contentEquals(title);
    }
}
