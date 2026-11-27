package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import de.robv.android.xposed.XposedHelpers;

/** Owns empty-cell backing surfaces and task-surface presentation state for the workspace. */
final class WorkspaceCellSurfaceController {
    private static final int SLOT_MAIN = WorkspaceHost.SLOT_MAIN;
    private static final int SLOT_TOP = WorkspaceHost.SLOT_TOP;

    private final WorkspaceHost host;

    WorkspaceCellSurfaceController(WorkspaceHost host) {
        this.host = host;
    }

    boolean ownsCellPresentation(Slot slot) {
        // Shell owns the borrowed task parents until the whole handoff finishes.
        // clearSlot/bindTask is only a logical replacement during a native swap;
        // an independent hide here would blank the just-committed real task.
        return !slot.promoting && !slot.launching && host.cells.motion() == null
                && !(host.promotion != null && host.promotion.nativeSwap && !host.promotion.cancelled);
    }

    boolean cellPresentationVisible(Slot slot) {
        boolean inViewport = host.sideRows.isScrolling() && slot.sideRow >= 0 ? slot.sidePrepared : presentsSlot(slot);
        return inViewport && !(host.taskCellsCovered && slot.index != SLOT_TOP)
                && slot.occupied && !slot.parked && slot.taskToken != null
                && host.slotRegistry.controllerOwns(slot, slot.taskId, slot.taskToken)
                && (slot.index!=SLOT_TOP || host.topFace);
    }

    void stageCellPresentation(Object tx, Slot slot, boolean visible) {
        Rect crop = host.windowPresentation.workspaceCellCrop(slot);
        boolean showing = visible && host.workspaceAlpha() > 0f && (crop == null || !crop.isEmpty());
        float alpha = showing ? 1f : 0f;
        boolean fading = showing && slot.index == SLOT_TOP && host.topTransition != null && host.topTransition.ownsTopOpacity();
        if (slot.view != null) {
            if (crop != null) slot.view.setClipBounds(crop);
            if (!fading) slot.view.setAlpha(alpha);
        }
        Object surface = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mSurfaceControl");
        if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) return;
        if (crop != null && !crop.isEmpty()) XposedHelpers.callMethod(tx, "setWindowCrop", surface, crop);
        if (!fading) XposedHelpers.callMethod(tx, "setAlpha", surface, alpha);
        // SurfaceView's RenderThread owns its outer parent and can rewrite that
        // parent's alpha. Also gate our settled task leash, which it cannot reset.
        if (ownsCellPresentation(slot) && slot.occupied && !slot.parked && !slot.frozen
                && host.scales.mayWriteSideSurface(slot)
                && host.slotRegistry.controllerOwns(slot, slot.taskId, slot.taskToken)) {
            Object leash = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mTaskLeash");
            if (leash != null && leash != surface
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                // The leash carries the task's logical buffer. Side cells now use their
                // compact pixel bounds while TOP keeps its logical viewport.
                XposedHelpers.callMethod(tx, "setWindowCrop", leash, host.slotRegistry.logicalBounds(slot));
                boolean collecting = showing && slot.index == SLOT_TOP && host.topTransition != null
                        && host.topTransition.ownsTaskOpacity();
                if (!fading && !collecting) XposedHelpers.callMethod(tx, "setAlpha", leash, alpha);
            }
        }
    }

    void updateCellSurfaceVisibility(Slot slot) {
        if (!ownsCellPresentation(slot)) return;
        Object tx = null;
        try {
            tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
            stageCellPresentation(tx, slot, cellPresentationVisible(slot));
            Object surface = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mSurfaceControl");
            if (surface != null && Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")))
                XposedHelpers.callMethod(tx, cellPresentationVisible(slot) && host.workspaceAlpha() > 0f ? "show" : "hide", surface);
            XposedHelpers.callMethod(tx, "apply");
        } catch (Throwable error) { WorkspaceHost.log("cell container visibility: " + error); }
        finally { host.sideRows.closeSurfaceObject(tx, "close"); }
    }

    void prepareCellSurface(Slot slot) {
        Object tx = null;
        try {
            tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
            stageCellPresentation(tx, slot, true);
            XposedHelpers.callMethod(tx, "apply");
        } catch (Throwable error) { WorkspaceHost.log("cell destination preparation: " + error); }
        finally { host.sideRows.closeSurfaceObject(tx, "close"); }
    }

    void ensureCellBackground(Slot slot) {
        if (slot.index == SLOT_TOP || !slot.occupied || slot.parked || (host.sideRows.isScrolling() && slot.sideRow >= 0)) {
            removeCellBackground(slot);
            return;
        }
        if (host.wallpaperBackdrop != null) host.wallpapers.applyWallpaperBackdrops();
        if (host.root == null || slot.index <= SLOT_MAIN || slot.controller == null || slot.rect == null) {
            return;
        }
        Object parent = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mSurfaceControl");
        if (parent == null) return;
        int width = slot.rect[2] - slot.rect[0];
        int height = slot.rect[3] - slot.rect[1];
        if (parent == slot.cellBackgroundParent && width == slot.cellBackgroundWidth
                && height == slot.cellBackgroundHeight) return;
        removeCellBackground(slot);
        // Remember a failed attempt too: no allocation/log loop for the same surface generation.
        slot.cellBackgroundParent = parent;
        slot.cellBackgroundWidth = width;
        slot.cellBackgroundHeight = height;
        Object background = null;
        Object transaction = null;
        try {
            Object builder = Class.forName("android.view.SurfaceControl$Builder")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(builder, "setName", "OneStepLiteCellBacking" + slot.index);
            XposedHelpers.callMethod(builder, "setColorLayer");
            XposedHelpers.callMethod(builder, "setParent", parent);
            background = XposedHelpers.callMethod(builder, "build");
            transaction = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            int page = HyperOsStyle.page(host.ctx);
            XposedHelpers.callMethod(transaction, "setColor", background,
                    new float[]{android.graphics.Color.red(page) / 255f,
                            android.graphics.Color.green(page) / 255f,
                            android.graphics.Color.blue(page) / 255f});
            XposedHelpers.callMethod(transaction, "setWindowCrop", background,
                    new Rect(0, 0, width, height));
            XposedHelpers.callMethod(transaction, "setLayer", background, -1);
            XposedHelpers.callMethod(transaction, "show", background);
            XposedHelpers.callMethod(transaction, "apply");
            slot.cellBackground = background;
            background = null; // The slot owns only the newly created backing, never the parent.
            WorkspaceHost.log("cell backing ready slot=" + slot.index);
        } catch (Throwable failure) {
            WorkspaceHost.log("cell backing failed slot=" + slot.index + ": " + failure);
        } finally {
            host.sideRows.closeSurfaceObject(transaction, "close");
            if (background != null) destroyCellSurface(background);
        }
    }

    boolean presentsSlot(Slot slot) {
        if (slot.index == SLOT_TOP) return true;
        return slot.sideRow >= 0 ? slot.sideShown : slot.index < host.CELL_SLOT_COUNT;
    }

    void removeCellBackground(Slot slot) {
        Object background = slot.cellBackground;
        slot.cellBackground = null;
        slot.cellBackgroundParent = null;
        if (background == null) return;
        destroyCellSurface(background);
    }

    private void destroyCellSurface(Object background) {
        Object transaction = null;
        try {
            transaction = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(transaction, "remove", background);
            XposedHelpers.callMethod(transaction, "apply");
        } catch (Throwable failure) {
            WorkspaceHost.log("cell backing cleanup failed: " + failure);
        } finally {
            host.sideRows.closeSurfaceObject(transaction, "close");
            host.sideRows.closeSurfaceObject(background, "release");
        }
    }
}
