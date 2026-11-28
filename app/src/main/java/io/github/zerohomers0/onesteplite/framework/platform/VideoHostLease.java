package io.github.zerohomers0.onesteplite;

import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.view.SurfaceControl;
import java.util.ArrayList;
import de.robv.android.xposed.XposedHelpers;

/** Owns only our container; a pending native child must outlive its SurfaceView parent. */
final class VideoHostLease {
    private static final ArrayList<VideoHostLease> pending = new ArrayList<>();
    final Binder owner = new Binder();
    SurfaceControl surface;
    IBinder releaseToken;
    boolean published;
    private int attempts;
    private Runnable recovered;

    void create(SurfaceControl parent) {
        if(surface!=null && surface.isValid())return;
        SurfaceControl.Builder builder = new SurfaceControl.Builder().setName("OneStepLiteVideoHost").setParent(parent);
        XposedHelpers.callMethod(builder, "setContainerLayer");
        surface = builder.build();
        try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
            tx.setAlpha(surface, 0f);
            XposedHelpers.callMethod(tx, "show", surface);
            tx.apply();
        }
    }

    boolean returnChild() {
        if (!published) return true;
        if (releaseToken == null) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("io.github.zerohomers0.onesteplite.VideoRelease");
            data.writeStrongBinder(owner);
            if (!releaseToken.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) return false;
            reply.readException();
            published = false;
            return true;
        } catch (Throwable failure) {
            android.util.Log.w(Tags.PROBE, "video native return pending", failure);
            return false;
        } finally { reply.recycle(); data.recycle(); }
    }

    void retire(Handler handler) {
        retire(handler,null);
    }

    void retire(Handler handler, Runnable recovered) {
        if (surface == null) return;
        if (returnChild()) {
            pending.remove(this);destroy();
            if(recovered!=null)handler.postDelayed(recovered,0L);
            return;
        }
        this.recovered=recovered;
        // Detach our container, never the framework's SurfaceView. Keeping a Java
        // reference alone would not survive the framework removing its parent.
        try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
            tx.setAlpha(surface, 0f).reparent(surface, null).apply();
        } catch (RuntimeException failure) {
            android.util.Log.w(Tags.PROBE, "video host isolation pending", failure);
        }
        if (!pending.contains(this)) pending.add(this);
        retry(handler);
    }

    private void retry(Handler handler) {
        if (attempts++ >= 3) return;
        handler.postDelayed(() -> {
            if (!pending.contains(this)) return;
            if (returnChild()) {
                pending.remove(this); destroy();
                Runnable done=recovered;recovered=null;if(done!=null)done.run();
            }
            else retry(handler);
        }, 200L * attempts);
    }

    static void receiveRetired(Bundle binding, Handler handler) {
        for (VideoHostLease lease : new ArrayList<>(pending)) {
            if (!lease.owner.equals(binding.getBinder("owner"))) continue;
            lease.releaseToken = binding.getBinder("release");
            lease.attempts = 0;
            lease.retry(handler);
            return;
        }
    }

    private void destroy() {
        if (surface == null) return;
        try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
            if (surface.isValid()) { XposedHelpers.callMethod(tx, "remove", surface); tx.apply(); }
            surface.release();
            surface = null;
        } catch (RuntimeException failure) {
            android.util.Log.w(Tags.PROBE, "video host cleanup unavailable", failure);
        }
    }
}
