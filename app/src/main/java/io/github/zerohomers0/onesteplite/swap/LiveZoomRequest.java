package io.github.zerohomers0.onesteplite;

import android.os.Handler;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One live-swap WMS acknowledgement with a bounded timeout and one completion. */
final class LiveZoomRequest implements Runnable {
    final LiveSwapZoom.Zoom expected;
    private final Consumer<LiveSwapZoom.Ack> completed;
    private final Handler main;
    private final BooleanSupplier bound;
    private final Consumer<LiveZoomRequest> retire;
    private boolean delivered;

    LiveZoomRequest(LiveSwapZoom.Zoom expected, Consumer<LiveSwapZoom.Ack> completed,
            Handler main, BooleanSupplier bound, Consumer<LiveZoomRequest> retire) {
        this.expected = expected;
        this.completed = completed;
        this.main = main;
        this.bound = bound;
        this.retire = retire;
    }

    boolean isBound() { return bound.getAsBoolean(); }

    void complete(LiveSwapZoom.Ack ack) {
        if (delivered) return;
        delivered = true;
        main.removeCallbacks(this);
        retire.accept(this);
        completed.accept(ack);
    }

    void failed(String reason) {
        complete(new LiveSwapZoom.Ack(expected.generation, 0L,
                expected.scale, expected.x, expected.y, false, reason));
    }

    @Override public void run() { failed("WMS live zoom commit timed out"); }
}
