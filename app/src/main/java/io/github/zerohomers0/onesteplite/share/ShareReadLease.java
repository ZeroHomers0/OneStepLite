package io.github.zerohomers0.onesteplite;

import android.os.Handler;
import android.os.Looper;
import java.io.Closeable;

/** Holds source access until Android transfers activity-owned read grants at launch. */
final class ShareReadLease implements Closeable {
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private final Closeable source;
    private final Runnable expire = this::close;
    private boolean closed;

    ShareReadLease(Closeable source) {
        this.source = source;
        HANDLER.postDelayed(expire, 60_000L);
    }

    boolean isOpen() { return !closed; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        HANDLER.removeCallbacks(expire);
        try { source.close(); }
        catch (Exception failure) { android.util.Log.w(Tags.PROBE, "source drag lease release failed", failure); }
    }
}
