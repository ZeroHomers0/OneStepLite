package io.github.zerohomers0.onesteplite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.SurfaceControl;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Physical wallpaper backing below the native wallpaper and real tasks. */
final class WorkspaceBackdrop {
    static final String ACTION = Actions.WORKSPACE_WALLPAPER_PARENT;
    private static final Map<IBinder,ExitFrame> exitFrames=new WeakHashMap<>();
    private static final AtomicLong exitSequence=new AtomicLong();
    private static final class ExitFrame {
        final long sequence;
        final AtomicBoolean committed=new AtomicBoolean();
        ExitFrame(long sequence) {this.sequence=sequence;}
    }

    /** Caller holds WM's lock. Only the live SystemUI Host can acquire this parent. */
    static Bundle capture(Object display, Intent intent) {
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
            // On first entry no magnification spec has been applied yet. Test
            // the native eligibility too, or this supposedly physical wallpaper
            // will shrink with its parent as soon as the main animation begins.
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

    /** Identity in Java is insufficient: wait for WM's restoring transaction to commit. */
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

    static void retire(Context context,Handler handler,IBinder host,BooleanSupplier current,
            Runnable release,Consumer<String> log) {
        AtomicBoolean done=new AtomicBoolean();
        pollExit(context,handler,host,()->!done.get() && current.getAsBoolean(),
                ()->{if(done.compareAndSet(false,true))release.run();},log,exitSequence.incrementAndGet(),0);
    }

    private static void pollExit(Context context,Handler handler,IBinder host,BooleanSupplier current,
            Runnable release,Consumer<String> log,long sequence,int attempt) {
        if(!current.getAsBoolean())return;
        // A stuck native zoom keeps its safety wallpaper until the next entry or
        // Host destruction. Removing it on a timeout would recreate the black rim.
        if(attempt>=120) {log.accept("workspace wallpaper retained: native exit unconfirmed");return;}
        Bundle state=new Bundle();state.putBinder("host",host);state.putBoolean("release",true);state.putLong("sequence",sequence);
        try {
            context.sendOrderedBroadcast(new Intent(ACTION).setPackage("android").putExtra("state",state),
                    android.Manifest.permission.WRITE_SECURE_SETTINGS,new BroadcastReceiver() {
                        @Override public void onReceive(Context ignored,Intent intent) {
                            if(!current.getAsBoolean())return;
                            Bundle result=getResultExtras(false);
                            if(getResultCode()==-1 && result!=null && result.getBoolean("settled",false))release.run();
                            else handler.postDelayed(()->pollExit(context,handler,host,current,release,log,sequence,attempt+1),16L);
                        }
                    },handler,0,null,null);
        } catch(Throwable failure) {log.accept("workspace exit frame unavailable; wallpaper retained: "+failure);}
    }

    static void acquire(Context context, Handler handler, IBinder host, Bitmap wallpaper,
            int width, int height, Consumer<LiveBackdrop> ready, Consumer<String> log) {
        AtomicBoolean done = new AtomicBoolean();
        Runnable timeout = () -> {
            if (done.compareAndSet(false, true)) {log.accept("workspace wallpaper parent timeout"); ready.accept(null);}
        };
        Bundle state = new Bundle(); state.putBinder("host", host);
        handler.postDelayed(timeout, 1000L);
        try {
            context.sendOrderedBroadcast(new Intent(ACTION).setPackage("android").putExtra("state", state),
                    android.Manifest.permission.WRITE_SECURE_SETTINGS, new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    Bundle result = getResultExtras(false);
                    SurfaceControl parent = result == null ? null : result.getParcelable("parent", SurfaceControl.class);
                    SurfaceControl anchor = result == null ? null : result.getParcelable("anchor", SurfaceControl.class);
                    LiveBackdrop backdrop = null;
                    try {
                        if (!done.compareAndSet(false, true)) return;
                        handler.removeCallbacks(timeout);
                        if (getResultCode() != -1 || parent == null || anchor == null)
                            throw new IllegalStateException("native wallpaper parent rejected");
                        backdrop = LiveBackdrop.create(parent, wallpaper, width, height, log);
                        try (SurfaceControl.Transaction tx=new SurfaceControl.Transaction()) {
                            // The native opaque wallpaper scales with the main area.
                            // Keep it above this full-screen blur so Home stays clear,
                            // while the backing still fills the surrounding gaps.
                            XposedHelpers.callMethod(tx,"setRelativeLayer",backdrop.surface,anchor,-1);
                            tx.apply();
                        }
                        log.accept("workspace wallpaper below real task area ready");
                    } catch (Throwable error) {
                        if (backdrop != null) {backdrop.close(); backdrop = null;}
                        log.accept("workspace wallpaper unavailable: " + error);
                    } finally {
                        if (parent != null) parent.release();
                        if (anchor != null) anchor.release();
                    }
                    ready.accept(backdrop);
                }
            }, handler, 0, null, null);
        } catch (Throwable error) {
            handler.removeCallbacks(timeout);
            if (done.compareAndSet(false, true)) {log.accept("workspace wallpaper request: " + error); ready.accept(null);}
        }
    }

    private static Bundle rejected(String reason) {
        XposedBridge.log("OneStepLiteProbe: workspace wallpaper parent rejected gate=" + reason);
        return null;
    }

    private WorkspaceBackdrop() {}
}
