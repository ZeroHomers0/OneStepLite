package io.github.zerohomers0.onesteplite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One captured WM magnification transaction and its ordered acknowledgement. */
final class ZoomCommit {
    interface Snapshot {
        Object create(Object display) throws Exception;
    }

    final Context context;
    final ZoomTarget target;
    final LiveSwapZoom.Zoom zoom;
    private final BooleanSupplier stateMatches;
    private final Snapshot snapshot;
    private final Consumer<ZoomCommit> retire;
    private final Consumer<String> log;
    long transactionId;
    float actualScale = Float.NaN, actualX = Float.NaN, actualY = Float.NaN;
    boolean complete;
    boolean applied;

    ZoomCommit(Context context, ZoomTarget target, LiveSwapZoom.Zoom zoom,
            BooleanSupplier stateMatches, Snapshot snapshot, Consumer<ZoomCommit> retire,
            Consumer<String> log) {
        this.context = context;
        this.target = target;
        this.zoom = zoom;
        this.stateMatches = stateMatches;
        this.snapshot = snapshot;
        this.retire = retire;
        this.log = log;
    }

    void finish(boolean committed, String error) {
        if (complete) return;
        Object replay = null;
        if (committed) {
            try {
                synchronized (target.globalLock) {
                    if (!stateMatches.getAsBoolean())
                        throw new IllegalStateException("zoom changed before finish snapshot");
                    replay = snapshot.create(target.display);
                }
            } catch (Throwable failure) {
                LiveSwapZoom.closeReplay(replay); replay = null;
                committed = false;
                error = "WMS finish snapshot failed: " + failure.getClass().getSimpleName();
            }
        }
        complete = true;
        retire.accept(this);
        Intent ack = new Intent(LiveSwapZoomProtocol.ACK).setPackage(Packages.SYSTEM_UI);
        ack.putExtra(LiveSwapZoomProtocol.GENERATION, zoom.generation);
        ack.putExtra(LiveSwapZoomProtocol.TRANSACTION, transactionId);
        ack.putExtra(LiveSwapZoomProtocol.SCALE, Float.floatToIntBits(actualScale));
        ack.putExtra(LiveSwapZoomProtocol.X, Float.floatToIntBits(actualX));
        ack.putExtra(LiveSwapZoomProtocol.Y, Float.floatToIntBits(actualY));
        ack.putExtra(LiveSwapZoomProtocol.COMMITTED, committed);
        if (replay != null) ack.putExtra(LiveSwapZoomProtocol.FINISH_REPLAY, (android.os.Parcelable) replay);
        if (error != null) ack.putExtra(LiveSwapZoomProtocol.ERROR, error);
        final Object sentReplay = replay;
        try {
            // We run inside system_server: AMS can retain the Intent locally and parcel
            // it later. Keep native operations alive until actual receiver dispatch ends.
            context.sendOrderedBroadcast(ack, LiveSwapZoomProtocol.PERMISSION,
                    new BroadcastReceiver() {
                        @Override public void onReceive(Context c, Intent result) {
                            LiveSwapZoom.closeReplay(sentReplay);
                        }
                    }, target.handler, 0, null, null);
        } catch (Throwable failure) {
            LiveSwapZoom.closeReplay(sentReplay);
            log.accept("live zoom acknowledgement failed: " + failure);
        }
    }
}
