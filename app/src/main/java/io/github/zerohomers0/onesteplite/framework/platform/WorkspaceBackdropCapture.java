package io.github.zerohomers0.onesteplite;

import android.os.Bundle;
import android.os.IBinder;
import android.view.SurfaceControl;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** system_server half of the authenticated physical-wallpaper parent handshake. */
final class WorkspaceBackdropCapture {
    private static final Map<IBinder,ExitFrame> exitFrames=new WeakHashMap<>();
    private static final AtomicLong exitSequence=new AtomicLong();
    private static final class ExitFrame {
        final long sequence;
        final AtomicBoolean committed=new AtomicBoolean();
        ExitFrame(long sequence) {this.sequence=sequence;}
    }

    static Bundle capture(Object display,android.content.Intent intent) {
        Bundle request = intent.getBundleExtra("state");
        IBinder token = request == null ? null : request.getBinder("host");
        if (token == null || display == null) return rejected("host token/display missing");
        Object service = XposedHelpers.getObjectField(display, "mWmService");
        Object host = ((Map<?, ?>) XposedHelpers.getObjectField(service, "mWindowMap")).get(token);
        if (host == null) return rejected("host window not registered");
        if (!WindowIdentity.ownsHost(host, display))
            return rejected("host display/owner changed");
        Object attrs = XposedHelpers.getObjectField(host, "mAttrs");
        if (!"WorkspaceHost".contentEquals((CharSequence) XposedHelpers.callMethod(attrs, "getTitle"))
                || !Packages.SYSTEM_UI.equals(XposedHelpers.getObjectField(attrs, "packageName"))) return rejected("host identity mismatch");
        if(request.getBoolean("release",false))return exitFrame(display,token,request.getLong("sequence",-1L));
        synchronized(exitFrames) {exitFrames.remove(token);}
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(host, "isVisible")))
            return rejected("host surface not visible yet");
        Object controller=XposedHelpers.getObjectField(display,"mWallpaperController");
        Object tokens=XposedHelpers.getObjectField(controller,"mWallpaperTokens");
        if(!(tokens instanceof List<?>))return rejected("wallpaper token list unavailable");
        Object parent=null,anchor=null;
        for(Object candidate:(List<?>)tokens) {
            if(candidate==null || XposedHelpers.callMethod(candidate,"getDisplayContent")!=display)continue;
            Object anchorNode=candidate;
            Object candidateParent=XposedHelpers.callMethod(candidate,"getParent");
            for(Object node=candidateParent;node!=null;node=XposedHelpers.callMethod(node,"getParent")) {
                String name=(String)XposedHelpers.callMethod(node,"getName");
                boolean willMagnify=(name==null || !name.contains("OneHanded:"))
                        && Boolean.TRUE.equals(XposedHelpers.callMethod(node,"shouldMagnify"));
                if(XposedHelpers.getObjectField(node,"mLastMagnificationSpec")!=null || willMagnify) {
                    anchorNode=node;candidateParent=XposedHelpers.callMethod(node,"getParent");
                }
            }
            SurfaceControl candidateAnchor=(SurfaceControl)XposedHelpers.callMethod(anchorNode,"getSurfaceControl");
            SurfaceControl candidateContainer=candidateParent==null?null
                    :(SurfaceControl)XposedHelpers.callMethod(candidateParent,"getSurfaceControl");
            if(candidateAnchor==null || candidateContainer==null
                    || !candidateAnchor.isValid() || !candidateContainer.isValid())continue;
            parent=candidateContainer;anchor=candidateAnchor;break;
        }
        if (parent == null || anchor == null) return rejected("physical wallpaper anchor unavailable");
        Bundle reply = new Bundle();
        reply.putParcelable("parent", (SurfaceControl)parent);
        reply.putParcelable("anchor", (SurfaceControl)anchor);
        return reply;
    }

    private static Bundle exitFrame(Object display,IBinder token,long sequence) {
        if(sequence<0L)return rejected("exit sequence missing");
        Object spec=XposedHelpers.getObjectField(display,"mMagnificationSpec");
        if(spec!=null && (Math.abs(((Number)XposedHelpers.getObjectField(spec,"scale")).floatValue()-1f)>.0001f
                || Math.abs(((Number)XposedHelpers.getObjectField(spec,"offsetX")).floatValue())>.1f
                || Math.abs(((Number)XposedHelpers.getObjectField(spec,"offsetY")).floatValue())>.1f)) {
            synchronized(exitFrames) {exitFrames.remove(token);}
            return rejected("native zoom still active");
        }
        synchronized(exitFrames) {
            ExitFrame frame=exitFrames.get(token);
            if(frame!=null && frame.sequence>sequence)return rejected("exit sequence retired");
            if(frame==null || frame.sequence!=sequence) {
                frame=new ExitFrame(sequence);exitFrames.put(token,frame);
                AtomicBoolean signal=frame.committed;
                SurfaceControl.Transaction transaction=(SurfaceControl.Transaction)XposedHelpers.callMethod(display,"getPendingTransaction");
                transaction.addTransactionCommittedListener(Runnable::run,()->signal.set(true));
                XposedHelpers.callMethod(display,"scheduleAnimation");
            }
            Bundle result=new Bundle();result.putBoolean("settled",frame.committed.get());return result;
        }
    }

    private static Bundle rejected(String reason) {
        XposedBridge.log("OneStepLiteProbe: workspace wallpaper parent rejected gate=" + reason);
        return null;
    }

    private WorkspaceBackdropCapture() {}
}