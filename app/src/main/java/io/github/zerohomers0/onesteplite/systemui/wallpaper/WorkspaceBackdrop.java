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
    private static final AtomicLong exitSequence=new AtomicLong();

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
        if(attempt>=120) {
            log.accept("workspace wallpaper retirement delayed: native exit unconfirmed");
            handler.postDelayed(()->{if(current.getAsBoolean())release.run();},30_000L);
            return;
        }
        Bundle state=new Bundle();state.putBinder("host",host);state.putBoolean("release",true);state.putLong("sequence",sequence);
        try {
            context.sendOrderedBroadcast(new Intent(ACTION).setPackage("android").putExtra("state",state),
                    android.Manifest.permission.WRITE_SECURE_SETTINGS,new BroadcastReceiver() {
                        @Override public void onReceive(Context ignored,Intent intent) {
                            if(!current.getAsBoolean())return;
                            Bundle result=getResultExtras(false);
                            if(getResultCode()==-1 && result!=null && result.getBoolean("settled",false))release.run();
                            else handler.postDelayed(()->pollExit(context,handler,host,current,release,log,sequence,attempt+1),
                                    Math.min(64L,16L<<Math.min(attempt,2)));
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
                        backdrop = LiveBackdrop.createThemed(parent, wallpaper, width, height,
                                HyperOsStyle.topWallpaperTint(context), log);
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
