package io.github.zerohomers0.onesteplite;

import android.content.ComponentName;
import de.robv.android.xposed.XposedHelpers;

/** Immutable task identity captured from a native task or one Host slot. */
final class LiveTask {
    final int id;
    final Object token;
    final String pkg;

    LiveTask(Object info) {
        id = XposedHelpers.getIntField(info, "taskId");
        token = XposedHelpers.getObjectField(info, "token");
        ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
        pkg = base == null ? null : base.getPackageName();
    }

    LiveTask(Slot slot) {
        this.id = slot.taskId;
        this.token = slot.taskToken;
        this.pkg = slot.pkg;
    }

    LiveTask(int id, Object token, String pkg) {
        this.id = id;
        this.token = token;
        this.pkg = pkg;
    }

    boolean matches(Object info) {
        return info != null && id == XposedHelpers.getIntField(info, "taskId")
                && token != null && token.equals(XposedHelpers.getObjectField(info, "token"));
    }
}
