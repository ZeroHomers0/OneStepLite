package io.github.zerohomers0.onesteplite;

import android.os.IBinder;
import java.util.Objects;

/** One SystemUI host identity; process death is the lease expiry signal. */
final class ZoomHostLease {
    interface Lost { void onLost(); }

    private final Lost lost;
    private IBinder owner;
    private IBinder.DeathRecipient recipient;

    ZoomHostLease(Lost lost) { this.lost = Objects.requireNonNull(lost); }

    synchronized boolean accept(IBinder next) {
        if (next == null || !next.isBinderAlive()) return false;
        if (next == owner) return true;
        unlinkLocked();
        owner = next;
        recipient = () -> {
            synchronized (ZoomHostLease.this) {
                if (owner != next) return;
                owner = null; recipient = null;
            }
            lost.onLost();
        };
        try {
            next.linkToDeath(recipient, 0);
            return true;
        } catch (Throwable unavailable) {
            owner = null; recipient = null;
            return false;
        }
    }

    synchronized boolean alive() { return owner != null && owner.isBinderAlive(); }

    synchronized void clear() { unlinkLocked(); }

    private void unlinkLocked() {
        if (owner != null && recipient != null) {
            try { owner.unlinkToDeath(recipient, 0); } catch (Throwable ignored) { }
        }
        owner = null; recipient = null;
    }
}
