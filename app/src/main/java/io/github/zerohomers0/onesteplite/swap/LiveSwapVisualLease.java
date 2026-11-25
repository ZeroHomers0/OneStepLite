package io.github.zerohomers0.onesteplite;

import android.view.View;

/** Hide only the selected cell's Host drawings; its SurfaceView stays attached. */
final class LiveSwapVisualLease {
    private static final class AlphaHold {
        int count;
        float alpha;
        AlphaHold(float alpha) { this.alpha = alpha; }
    }
    private static final java.util.IdentityHashMap<View, AlphaHold> held = new java.util.IdentityHashMap<>();
    final View backing, hint;
    final float backingAlpha, hintAlpha;
    final int backingVisibility, hintVisibility;
    boolean restored;
    LiveSwapVisualLease(View backing, View hint) {
        this.backing = backing; this.hint = hint;
        backingAlpha = backing == null ? 1f : backing.getAlpha();
        hintAlpha = hint == null ? 1f : hint.getAlpha();
        backingVisibility = backing == null ? View.GONE : backing.getVisibility();
        hintVisibility = hint == null ? View.GONE : hint.getVisibility();
        acquire(backing);
        acquire(hint);
    }
    private static void acquire(View view) {
        if (view == null) return;
        AlphaHold hold = held.get(view);
        if (hold == null) { hold = new AlphaHold(view.getAlpha()); held.put(view, hold); }
        hold.count++; view.setAlpha(0f);
    }
    static void writeAlpha(View view, float alpha) {
        if (view == null) return;
        AlphaHold hold = held.get(view);
        if (hold != null) hold.alpha = alpha;
        else view.setAlpha(alpha);
    }
    private static void release(View view) {
        if (view == null) return;
        AlphaHold hold = held.get(view);
        if (hold != null && --hold.count == 0) {
            held.remove(view); view.setAlpha(hold.alpha);
        }
    }
    void restore() {
        if (restored) return;
        restored = true;
        // Nested handoffs and late fade callbacks cannot resurrect shared chrome.
        // Restore the latest requested alpha only when the last owner releases it.
        release(backing);
        release(hint);
    }
}
