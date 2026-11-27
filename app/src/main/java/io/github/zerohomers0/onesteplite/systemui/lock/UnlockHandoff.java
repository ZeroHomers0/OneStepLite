package io.github.zerohomers0.onesteplite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import java.util.function.Consumer;

/** One authenticated unlock: WM commit, existing Host frame, then native hide. */
final class UnlockHandoff {
    static final String PHYSICAL_KEYGUARD = "retainedPhysicalKeyguard";
    static final String COMPLETED="retainedRevealCompleted";
    static final String WORKSPACE="retainedWorkspaceGeometry";
    static final String RELEASE = Actions.UNLOCK_HANDOFF_RELEASE;
    private static long nextGeneration = System.currentTimeMillis();
    private final Context context;
    private final Handler main;
    private final LiveSwapZoom.Zoom zoom;
    private final Consumer<LiveSwapZoom.Ack> ready;
    private final Runnable nativeHide;
    private BroadcastReceiver receiver;
    private boolean completed, delivered, workspacePresented;

    UnlockHandoff(Context context, Handler main, float scale, float x, float y,
            Consumer<LiveSwapZoom.Ack> ready, Runnable nativeHide) {
        this.context = context; this.main = main; this.ready = ready; this.nativeHide = nativeHide;
        zoom = new LiveSwapZoom.Zoom(++nextGeneration, scale, x, y);
    }

    void start() {
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (completed || delivered || intent == null
                        || intent.getLongExtra(LiveSwapZoomProtocol.GENERATION, -1) != zoom.generation) return;
                Object replay = null;
                try {
                    replay = intent.getParcelableExtra(LiveSwapZoomProtocol.FINISH_REPLAY,
                            android.view.SurfaceControl.Transaction.class);
                    LiveSwapZoom.Ack ack = new LiveSwapZoom.Ack(zoom.generation,
                            intent.getLongExtra(LiveSwapZoomProtocol.TRANSACTION, 0),
                            Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.SCALE, 0)),
                            Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.X, 0)),
                            Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.Y, 0)),
                            intent.getBooleanExtra(LiveSwapZoomProtocol.COMMITTED, false),
                            intent.getStringExtra(LiveSwapZoomProtocol.ERROR), replay);
                    if (!ack.confirmsFinish(zoom)) { finish(); return; }
                    delivered = true;
                    ready.accept(ack);
                } catch (Throwable error) { finish(); }
                finally { LiveSwapZoom.closeReplay(replay); }
            }
        };
        try {
            context.registerReceiver(receiver, new IntentFilter(LiveSwapZoomProtocol.ACK),
                    LiveSwapZoomProtocol.PERMISSION, main, Context.RECEIVER_EXPORTED);
            Intent request = new Intent(LiveSwapZoomProtocol.APPLY).setPackage("android");
            request.putExtra(LiveSwapZoomProtocol.GENERATION, zoom.generation);
            request.putExtra(LiveSwapZoomProtocol.SCALE, Float.floatToIntBits(zoom.scale));
            request.putExtra(LiveSwapZoomProtocol.X, Float.floatToIntBits(zoom.x));
            request.putExtra(LiveSwapZoomProtocol.Y, Float.floatToIntBits(zoom.y));
            request.putExtra(LiveSwapZoomProtocol.ISSUED, System.currentTimeMillis());
            request.putExtra(PHYSICAL_KEYGUARD, true);
            request.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            context.sendBroadcast(request, LiveSwapZoomProtocol.PERMISSION);
            main.postDelayed(this::finish, LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS);
        } catch (Throwable error) { finish(); }
    }

    boolean active() { return !completed; }

    void finish() {
        complete(true);
    }

    void presented() {
        workspacePresented=true;
        finish();
    }

    void cancel() { complete(false); }

    private void complete(boolean hide) {
        if (completed) return;
        completed = true;
        if (receiver != null) {
            try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
        }
        try { if (hide) nativeHide.run(); }
        finally {
            // Native hide changes the real shade's window before this release is dispatched.
            Intent release = new Intent(RELEASE).setPackage("android");
            release.putExtra(LiveSwapZoomProtocol.GENERATION, zoom.generation);
            release.putExtra(COMPLETED,hide);
            release.putExtra(WORKSPACE,hide && workspacePresented && zoom.scale<1f);
            release.putExtra(LiveSwapZoomProtocol.SCALE,Float.floatToIntBits(zoom.scale));
            release.putExtra(LiveSwapZoomProtocol.X,Float.floatToIntBits(zoom.x));
            release.putExtra(LiveSwapZoomProtocol.Y,Float.floatToIntBits(zoom.y));
            try { context.sendBroadcast(release, LiveSwapZoomProtocol.PERMISSION); }
            catch (RuntimeException ignored) {}
        }
    }
}
