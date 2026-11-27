package io.github.zerohomers0.onesteplite;

import android.content.res.Configuration;
import android.graphics.Rect;
import de.robv.android.xposed.XposedHelpers;

/** Restore only an owned landscape token's local viewport after mode6 resolves its portrait parent. */
final class SideConfiguration {
    static boolean apply(Object activity,Object task,Configuration local){
        if(activity==null||local==null||local.orientation!=Configuration.ORIENTATION_LANDSCAPE
                ||!SideRoleChange.isSide(task))return false;
        Rect carrier=(Rect)XposedHelpers.callMethod(task,"getBounds");
        Object localWindow=XposedHelpers.getObjectField(local,"windowConfiguration");
        Rect viewport=(Rect)XposedHelpers.callMethod(localWindow,"getBounds");
        if(carrier==null||viewport==null||carrier.width()>=carrier.height()
                ||viewport.width()!=carrier.height()||viewport.height()!=carrier.width()
                ||local.screenWidthDp<=local.screenHeightDp||local.screenHeightDp<=0||local.densityDpi<=0)return false;
        Configuration resolved=(Configuration)XposedHelpers.callMethod(activity,"getResolvedOverrideConfiguration");
        Object window=XposedHelpers.getObjectField(resolved,"windowConfiguration");
        Rect app=(Rect)XposedHelpers.callMethod(window,"getAppBounds");
        boolean nativeLandscape=app!=null&&app.width()>app.height()&&app.height()>0
                &&app.width()<=viewport.width()&&app.height()<=viewport.height();
        // Task bounds stay portrait for the physical carrier. Only its already
        // fixed-rotated Activity receives landscape resource/window metrics.
        if(!nativeLandscape){
            Rect localApp=(Rect)XposedHelpers.callMethod(localWindow,"getAppBounds");
            if(localApp==null||localApp.width()<=localApp.height()||localApp.height()<=0)return false;
            app=new Rect(localApp);
            XposedHelpers.callMethod(window,"setBounds",new Rect(viewport));
            XposedHelpers.callMethod(window,"setAppBounds",app);
            XposedHelpers.callMethod(window,"setMaxBounds",new Rect(viewport));
        }
        resolved.orientation=local.orientation;resolved.densityDpi=local.densityDpi;
        resolved.screenLayout=local.screenLayout;resolved.smallestScreenWidthDp=local.smallestScreenWidthDp;
        resolved.screenWidthDp=Math.round(app.width()*160f/local.densityDpi);
        resolved.screenHeightDp=Math.round(app.height()*160f/local.densityDpi);
        return true;
    }
}
