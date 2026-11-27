package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import android.graphics.Region;
import android.view.View;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import de.robv.android.xposed.XposedHelpers;

/** Owns chrome clipping, backing bounds and the host touchable region. */
final class WorkspaceChromeController {
    private static final int SLOT_TOP = WorkspaceHost.coordinatorSlotTop();
    private static final int SLOT_COUNT = WorkspaceHost.coordinatorSlotCount();

    private final WorkspaceHost host;

    WorkspaceChromeController(WorkspaceHost host) {
        this.host = host;
    }

    void updateChromeClip() {
        if (host.layout == null || host.screenW <= 0 || host.screenH <= 0) return;
        // Every form factor shares the moving main exclusion. The top strip and the
        // side rail read different progress curves, so each gets its own hole: the
        // bar is uncovered first, the cells follow the receding edge.
        boolean moving = host.workspaceAlpha() < 1f || host.mainSideAnimator() != null;
        // While the chrome rides the main edge it is already translated clear of
        // the window, so a horizontal cut would fight the offset and clip the
        // cells to nothing. Only the swap animation still needs the cut.
        boolean riding = moving && host.mainSideAnimator() == null && !host.layout.tabletLandscape;
        android.graphics.RectF top = moving && !riding ? host.workspaceTopHole() : null;
        android.graphics.RectF side = moving && !riding ? host.workspaceSideHole() : null;
        // Placeholders never ride: they stay anchored to the wallpaper, so they
        // keep a geometric cut even while the cells are translated. An unclipped
        // page-colour backing washed the whole rail white on entry.
        android.graphics.RectF backing = moving ? (riding ? host.workspaceMainHole() : side) : null;
        if (host.topBar != null) host.topBar.setWorkspaceMainHole(host.layout.tabletLandscape ? null : top);
        if (host.topLiveClip != null) {
            // The moving main is cut out in dispatchDraw. Keep the left/right
            // exposed corner instead of cutting this entire strip horizontally.
            host.topLiveClip.setClipBounds(new Rect(0, 0, host.screenW,
                    host.layout.tabletLandscape ? host.screenH : host.layout.topStrip));
            host.topLiveClip.invalidate();
        }
        for (int i = 1; i < SLOT_COUNT; i++) {
            int[] bounds = sideBackingBounds(i);
            if (bounds != null) clipChromeBesideMain(host.sideBackingViews[i], bounds, backing);
            Slot slot = host.slots[i];
            if (slot != null && slot.index != SLOT_TOP && slot.hint != null && slot.rect != null)
                clipChromeBesideMain(slot.hint,
                        new int[]{slot.rect[0], slot.rect[1],
                                slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1]}, side);
        }
    }

    private void clipChromeBesideMain(View view, int[] bounds, android.graphics.RectF main) {
        if (view == null || bounds == null || bounds[2] <= 0 || bounds[3] <= 0) return;
        int left = 0, right = bounds[2];
        boolean overlaps = main != null
                && main.bottom > bounds[1] && main.top < bounds[1] + bounds[3];
        if (overlaps) {
            if (host.layout.mainOnRight) right = Math.max(0, Math.min(bounds[2], Math.round(main.left) - bounds[0]));
            else left = Math.max(0, Math.min(bounds[2], Math.round(main.right) - bounds[0]));
        }
        view.setClipBounds(new Rect(left, 0, right, bounds[3]));
    }

    private int[] sideBackingBounds(int index) {
        if (host.layout == null || index <= 0 || index >= host.CELL_SLOT_COUNT) return null;
        if (host.layout.tabletLandscape) {
            int[] cell = host.layout.cellBounds(index - 1);
            return new int[]{cell[0], cell[1], cell[2] - cell[0], cell[3] - cell[1]};
        }
        return new int[]{host.layout.mainOnRight ? 0 : host.layout.mainWidth, sideSegmentTop(index),
                host.layout.sideWidth, sideSegmentHeight(index)};
    }

    void updateWorkspaceBacking(View view, android.graphics.RectF hole) {
        if (view == null) return;
        // Keep the editable cutout wallpaper visible throughout the real-task zoom.
        // The native wallpaper parent can gain its first composited buffer a frame
        // or more after acquisition; hiding this fallback exposed a black surround.
        boolean wallpaper = view.getBackground() instanceof WallpaperCropDrawable;
        // A cold Host still has plain page-color placeholders. They cannot cut
        // out the full-size main task, so keep them hidden until entry fades in.
        // Route the alpha through the lease: a cell handed to a live task hides
        // its placeholder by leasing it, and writing the View directly here
        // resurrected the page-color backing for a frame during that handoff.
        LiveSwapVisualLease.writeAlpha(view, hole != null && wallpaper ? 1f : host.workspaceAlpha());
        if (wallpaper)
            ((WallpaperCropDrawable) view.getBackground()).setMainHole(hole);
    }

    int sideSegmentTop(int slotIndex) {
        if(host.layout!=null && host.layout.tabletLandscape)return host.layout.cellBounds(slotIndex-1)[1];
        if (slotIndex <= 1 || host.layout == null) return host.layout == null ? 0 : host.layout.topStrip;
        int[] previous = host.layout.cellBounds(slotIndex - 2);
        return previous[3];
    }

    int sideSegmentHeight(int slotIndex) {
        if (host.layout == null) return 1;
        if(host.layout.tabletLandscape) {
            int[] b=host.layout.cellBounds(slotIndex-1);return b[3]-b[1];
        }
        int top = sideSegmentTop(slotIndex);
        int bottom = slotIndex >= host.CELL_SLOT_COUNT - 1 ? host.screenH : host.layout.cellBounds(slotIndex - 1)[3];
        return Math.max(1, bottom - top);
    }

    void installHostTouchRegion() {
        Class<?> listenerClass = XposedHelpers.findClass(
                "android.view.ViewTreeObserver$OnComputeInternalInsetsListener", host.ctx.getClassLoader());
        Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        return "WorkspaceHostTouchRegion";
                    }
                    if (!"onComputeInternalInsets".equals(method.getName()) || host.root == null) {
                        return null;
                    }
                    Object info = args[0];
                    XposedHelpers.callMethod(info, "setTouchableInsets", 3); // TOUCHABLE_INSETS_REGION
                    Region touchable = (Region) XposedHelpers.getObjectField(info, "touchableRegion");
                    touchable.setEmpty();
                    if (host.keyguardLocked || !host.screenOn) return null;
                    if (host.cellMovePreview != null) {
                        touchable.set(0, 0, host.screenW, host.screenH);
                    } else if (host.liveSwapInputBlocked) {
                        touchable.set(0, 0, host.screenW, host.screenH);
                    } else {
                        touchable.set(0, 0, host.screenW, host.layout.topStrip);
                        if(host.layout.tabletLandscape) {
                            int[] b=host.layout.interactiveBounds();
                            touchable.op(new Rect(b[0],b[1],b[2],b[3]),Region.Op.UNION);
                        } else touchable.op(new Rect(host.layout.cellX, host.layout.topStrip,
                                        host.layout.cellX + host.layout.cellWidth, host.screenH), Region.Op.UNION);
                        Slot upper=host.slots[SLOT_TOP];
                        if(host.topFace
                                && upper.occupied && !upper.parked
                                && !upper.promoting && upper.rect!=null)
                            touchable.op(new Rect(upper.rect[0],upper.rect[1],upper.rect[2],upper.rect[3]),Region.Op.DIFFERENCE);
                    }
                    return null;
                });
        XposedHelpers.callMethod(host.root.getViewTreeObserver(),
                "addOnComputeInternalInsetsListener", listener);
        WorkspaceHost.log("host input: top/side only; system main passes through");
    }

    void updateTouchRegion(Slot slot) {
        try {
            if (slot.view == null) {
                return;
            }
            Method m = slot.view.getClass().getMethod("setObscuredTouchRegion", Region.class);
            // Only side cells exist. Their own scaled contents still use targeted injection.
            Region arg = null;
            if (host.liveSwapInputBlocked || (slot.sideRow >= 0 && !slot.sideShown)) {
                arg = new Region(0, 0, host.screenW, host.screenH);
            } else if (slot.index != SLOT_TOP && slot.rect != null) {
                int[] rect = slot.rect;
                arg = new Region(rect[0], slot.sideRow >= 0 ? Math.max(host.layout.topStrip, rect[1]) : rect[1],
                        rect[2], slot.sideRow >= 0 ? Math.min(host.screenH, rect[3]) : rect[3]);
            }
            m.invoke(slot.view, arg);
        } catch (Throwable t) {
            WorkspaceHost.log("updateTouchRegion slot=" + slot.index + " failed: " + t);
        }
    }
}
