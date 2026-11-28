package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.os.UserHandle;
import de.robv.android.xposed.XposedHelpers;

/** Resolves a user-scoped Context without leaking framework reflection into workspace code. */
final class UserContextCompat {
    private UserContextCompat() {}

    static Context createAsUser(Context base, int user) {
        Object handle = XposedHelpers.callStaticMethod(UserHandle.class, "of", user);
        return (Context) XposedHelpers.callMethod(base, "createContextAsUser", handle, 0);
    }
}