package io.github.zerohomers0.onesteplite;

import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.view.Display;
import android.os.UserManager;

/** Admission only. Never use this guard to prevent cleanup or returning tasks. */
final class EntryGuard {
    private EntryGuard() {}

    static boolean canOpen(Context context) {
        if (context == null) return false;
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            UserManager user = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (power == null || keyguard == null || user == null) return false;
            DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
            // Missing DisplayManager is tolerated for legacy/test contexts; a present
            // display must be fully on before admitting a new workspace entry.
            boolean displayOn = display == null || display.getState() == Display.STATE_ON;
            return allows(power.isInteractive(), keyguard.isKeyguardLocked(),
                    keyguard.isDeviceLocked(), user.isUserUnlocked(), displayOn);
        } catch (Throwable unavailable) {
            // A missing service during early boot never grants permission to open over keyguard.
            return false;
        }
    }

    static boolean allows(boolean interactive, boolean keyguardLocked,
            boolean deviceLocked, boolean userUnlocked) {
        return allows(interactive, keyguardLocked, deviceLocked, userUnlocked, true);
    }

    static boolean allows(boolean interactive, boolean keyguardLocked,
            boolean deviceLocked, boolean userUnlocked, boolean displayOn) {
        return interactive && displayOn && !keyguardLocked && !deviceLocked && userUnlocked;
    }
}
