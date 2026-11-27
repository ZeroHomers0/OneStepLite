package io.github.zerohomers0.onesteplite;

import android.view.WindowManager;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Only our visible picker temporarily sits above the native screenshot preview. */
final class ModalWindowLayers {
    private final Map<Object, Layer> held = new IdentityHashMap<>();

    void reconcile(Object display, Object transaction) {
        reconcile(display, transaction, true);
    }

    void reconcile(Object display, Object transaction, boolean workspaceActive) {
        if (!workspaceActive) {
            restoreHeld(display, transaction);
            return;
        }
        Object[] modal={null}, screenshot={null};
        Consumer<Object> scan=window->{
            WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)XposedHelpers.getObjectField(window,"mAttrs");
            if (attrs==null) return;
            if (attrs.type==WindowTypes.SYSTEM_UI_DIALOG && Packages.SYSTEM_UI.equals(attrs.packageName)) {
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(window,"isVisible"))
                        && WindowIdentity.ownsPicker(window,display)) modal[0]=window;
            } else if (attrs.type==WindowTypes.SCREENSHOT && "com.miui.screenshot".equals(attrs.packageName)
                    && "ScreenshotAnimation".contentEquals(attrs.getTitle())
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(window,"isVisible"))) screenshot[0]=window;
        };
        XposedHelpers.callMethod(display,"forAllWindows",scan,false);
        Object anchor=screenshot[0]==null?null:XposedHelpers.callMethod(screenshot[0],"getSurfaceControl");
        if (!valid(anchor)) anchor=null;
        var iterator=held.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry=iterator.next();Object window=entry.getKey();Layer layer=entry.getValue();
            Object ownerDisplay=XposedHelpers.callMethod(window,"getDisplayContent");
            if(ownerDisplay==null){iterator.remove();continue;}
            if(ownerDisplay!=display)continue;
            if (XposedHelpers.callMethod(window,"getSurfaceControl")!=layer.surface || !valid(layer.surface)) {
                iterator.remove();continue;
            }
            remember(window,layer);
            if (window==modal[0] && anchor!=null) continue;
            restore(window,layer,transaction);iterator.remove();
        }
        if (modal[0]==null || anchor==null) return;
        Object surface=XposedHelpers.callMethod(modal[0],"getSurfaceControl");
        if (!valid(surface)) return;
        Layer layer=held.computeIfAbsent(modal[0],ignored->new Layer(surface));
        remember(modal[0],layer);
        XposedHelpers.callMethod(modal[0],"assignRelativeLayer",transaction,anchor,1,true);
        layer.anchor=anchor;
    }

    /** Inactive workspaces need no window scan; only leases from an earlier session remain. */
    private void restoreHeld(Object display, Object transaction) {
        if (held.isEmpty()) return;
        var iterator=held.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry=iterator.next();Object window=entry.getKey();Layer layer=entry.getValue();
            Object ownerDisplay=XposedHelpers.callMethod(window,"getDisplayContent");
            if(ownerDisplay==null){iterator.remove();continue;}
            if(ownerDisplay!=display)continue;
            if (XposedHelpers.callMethod(window,"getSurfaceControl")!=layer.surface || !valid(layer.surface)) {
                iterator.remove();continue;
            }
            remember(window,layer);
            restore(window,layer,transaction);iterator.remove();
        }
    }

    private static boolean valid(Object surface) {
        return surface!=null && Boolean.TRUE.equals(XposedHelpers.callMethod(surface,"isValid"));
    }

    private static void remember(Object window, Layer state) {
        int layer=XposedHelpers.getIntField(window,"mLastLayer");
        Object relative=XposedHelpers.getObjectField(window,"mLastRelativeToLayer");
        if (state.anchor==null || relative!=state.anchor || layer!=1) {
            state.normalLayer=layer;state.normalRelative=relative;
        }
    }

    private static void restore(Object window, Layer state, Object transaction) {
        if (state.normalRelative==null) XposedHelpers.callMethod(window,"setLayer",transaction,state.normalLayer);
        else XposedHelpers.callMethod(window,"setRelativeLayer",transaction,state.normalRelative,state.normalLayer);
        XposedHelpers.setIntField(window,"mLastLayer",state.normalLayer);
        XposedHelpers.setObjectField(window,"mLastRelativeToLayer",state.normalRelative);
    }

    private static final class Layer {
        final Object surface;
        Object anchor,normalRelative;
        int normalLayer;
        Layer(Object surface) {this.surface=surface;}
    }
}
