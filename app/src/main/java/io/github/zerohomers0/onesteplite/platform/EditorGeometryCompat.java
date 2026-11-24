package io.github.zerohomers0.onesteplite;

import android.os.Bundle;
import de.robv.android.xposed.XposedHelpers;

/** Exports the real display magnification only for the confirmed fullscreen input window. */
final class EditorGeometryCompat {
    private EditorGeometryCompat(){}
    static void describe(Object display,Bundle snapshot){
        if(display==null || snapshot==null)return;
        try{
            Object focus=XposedHelpers.getObjectField(display,"mCurrentFocus");
            Object task=XposedHelpers.callMethod(focus,"getTask");
            if(task==null || !Integer.valueOf(1).equals(XposedHelpers.callMethod(task,"getWindowingMode")))return;
            Object spec=XposedHelpers.getObjectField(display,"mMagnificationSpec");if(spec==null)return;
            float scale=XposedHelpers.getFloatField(spec,"scale"),x=XposedHelpers.getFloatField(spec,"offsetX"),y=XposedHelpers.getFloatField(spec,"offsetY");
            if(!Float.isFinite(scale) || scale<=0 || scale>=1 || !Float.isFinite(x) || !Float.isFinite(y))return;
            snapshot.putFloatArray("geometry_transform",new float[]{scale,x,y});
        }catch(Throwable unavailable){/* No guessed coordinate transform. */}
    }
}
