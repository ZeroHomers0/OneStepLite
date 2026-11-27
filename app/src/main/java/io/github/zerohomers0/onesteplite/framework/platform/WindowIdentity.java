package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.view.WindowManager;
import java.util.Arrays;
import de.robv.android.xposed.XposedHelpers;

/** WM lock only: resolve the registered window's native session and package owner. */
final class WindowIdentity {
    static final String SHARE_RECEIVER_TITLE="OneStepLiteShareReceiver";

    static boolean ownsShareReceiver(Object window) {
        if(window==null)return false;
        try {
            Object display=XposedHelpers.callMethod(window,"getDisplayContent");
            WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)XposedHelpers.getObjectField(window,"mAttrs");
            if(SHARE_RECEIVER_TITLE.contentEquals(attrs.getTitle()))return ownsPicker(window,display);
            return ownsHost(window,display);
        }catch(RuntimeException unavailable){return false;}
    }

    static boolean ownsHost(Object window, Object display) {
        return owns(window, display, 0);
    }

    static boolean ownsChrome(Object window, Object display) {
        return owns(window, display, 1);
    }

    static boolean ownsPicker(Object window, Object display) {
        return owns(window, display, 2);
    }

    /** Host and transition-cover windows may receive a DROP on behalf of a promoted task. */
    static boolean ownsDropRelay(Object window,Object display) {
        return ownsHost(window,display) || ownsTransitionInput(window,display);
    }

    private static boolean ownsTransitionInput(Object window,Object display) {
        if (window == null || display == null) return false;
        try {
            if (XposedHelpers.callMethod(window, "getDisplayContent") != display) return false;
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                    XposedHelpers.getObjectField(window, "mAttrs");
            if (attrs.type != WindowTypes.APPLICATION_OVERLAY
                    || !Packages.SYSTEM_UI.equals(attrs.packageName)
                    || (attrs.flags & WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) != 0
                    || !"TransitionInput".contentEquals(attrs.getTitle())) return false;
            int uid = XposedHelpers.getIntField(window, "mOwnerUid");
            Object session = XposedHelpers.getObjectField(window, "mSession");
            if (uid < 0 || session == null || XposedHelpers.getIntField(session, "mUid") != uid) return false;
            Object service = XposedHelpers.getObjectField(display, "mWmService");
            Context context = (Context) XposedHelpers.getObjectField(service, "mContext");
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return packages != null && Arrays.asList(packages).contains(Packages.SYSTEM_UI);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static boolean owns(Object window, Object display, int scope) {
        if (window == null || display == null) return false;
        try {
            if (XposedHelpers.callMethod(window, "getDisplayContent") != display) return false;
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                    XposedHelpers.getObjectField(window, "mAttrs");
            if (attrs.type != (scope==2 ? WindowTypes.SYSTEM_UI_DIALOG : WindowTypes.APPLICATION_OVERLAY)
                    || !Packages.SYSTEM_UI.equals(attrs.packageName)
                    || (attrs.flags & WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) != 0) return false;
            String title = String.valueOf(attrs.getTitle());
            if (scope==2) {
                if (!"OneStepLiteContentPanel".equals(title)
                        && !"OneStepLitePortalSource".equals(title) && !SHARE_RECEIVER_TITLE.equals(title)) return false;
            } else if (!"WorkspaceHost".equals(title) && (scope!=1
                    || (!"OneStepLiteMirrorLayer".equals(title) && !"OneStepLiteTransition".equals(title)))) return false;
            int uid = XposedHelpers.getIntField(window, "mOwnerUid");
            Object session = XposedHelpers.getObjectField(window, "mSession");
            if (uid < 0 || session == null || XposedHelpers.getIntField(session, "mUid") != uid) return false;
            Object service = XposedHelpers.getObjectField(display, "mWmService");
            Context context = (Context) XposedHelpers.getObjectField(service, "mContext");
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return packages != null && Arrays.asList(packages).contains(Packages.SYSTEM_UI);
        } catch (Throwable unavailable) {
            return false; // An unavailable package/session never grants a privileged lease.
        }
    }
    private WindowIdentity() {}
}
