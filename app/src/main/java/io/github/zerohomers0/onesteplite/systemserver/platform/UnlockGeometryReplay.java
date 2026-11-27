package io.github.zerohomers0.onesteplite;

import android.os.Parcel;
import android.view.SurfaceControl;

/** A reusable copy of the authenticated WM geometry, only for this unlock's native finish. */
final class UnlockGeometryReplay {
    private Parcel snapshot;
    private long epoch;
    private float scale, x, y;

    synchronized void capture(SurfaceControl.Transaction source, long scene, float s, float ox, float oy) {
        clear();
        Parcel copy = Parcel.obtain();
        try {
            source.writeToParcel(copy, 0);
            snapshot = copy;
            epoch = scene; scale = s; x = ox; y = oy;
        } catch (RuntimeException | Error failure) {
            copy.recycle();
            throw failure;
        }
    }

    synchronized boolean append(SurfaceControl.Transaction target, long scene, float s, float ox, float oy) {
        if (snapshot == null || scene != epoch || s != scale || ox != x || oy != y) return false;
        snapshot.setDataPosition(0);
        // merge consumes its source. Decode a fresh native transaction for each
        // marked finish, rather than reusing the already-applied acknowledgement.
        try (SurfaceControl.Transaction replay = SurfaceControl.Transaction.CREATOR.createFromParcel(snapshot)) {
            target.merge(replay);
        }
        return true;
    }

    synchronized void clear() {
        if (snapshot != null) snapshot.recycle();
        snapshot = null;
    }
}
