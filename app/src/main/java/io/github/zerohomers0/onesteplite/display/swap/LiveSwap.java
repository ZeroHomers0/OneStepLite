package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import java.util.IdentityHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** One WCT swaps two existing ordinary tasks in the current, fixed main/side layout. */
final class LiveSwap {
    private LiveSwap() {}
    private static final IdentityHashMap<Object, Active> ACTIVE = new IdentityHashMap<>();
    private static final class Active {
        final LiveSwapSession.ShellBackend backend;
        LiveSwapSession session;
        Active(LiveSwapSession.ShellBackend backend) { this.backend = backend; }
    }
    /** May be called from Host main; all ownership work remains on the existing Shell executor. */
    static void cancel(Object controller) {
        LiveCollect.cancel(controller);
        LiveSwap64.cancel(controller);
        Active active;
        synchronized (ACTIVE) { active = ACTIVE.get(controller); }
        if (active != null) active.backend.execute(() -> active.session.cancel());
    }
    static boolean startFlip(ClassLoader loader, Object taskViews, Object controller, LiveTaskSwapDriver.Flip request,
            BooleanSupplier current, Consumer<LiveSwapGeometry.Frame[]> input,
            Consumer<Result> done, Consumer<String> log) {
        synchronized (ACTIVE) {
            if (ACTIVE.containsKey(controller)) { log.accept("live flip preflight refused: legacy controller active"); return false; }
        }
        return LiveSwap64.start(loader, taskViews, controller, request, null, current, input, done, log);
    }
    static boolean startHome(ClassLoader loader, Object taskViews, Object controller, LiveTaskSwapDriver.Home request,
            BooleanSupplier current, Consumer<LiveSwapGeometry.Frame[]> input,
            Consumer<Result> done, Consumer<String> log) {
        synchronized (ACTIVE) {
            if (ACTIVE.containsKey(controller)) { log.accept("live Home preflight refused: legacy controller active"); return false; }
        }
        return LiveSwap64.start(loader, taskViews, controller, null, request, current, input, done, log);
    }
    static final class Result {
        final boolean succeeded, sideOwned, rolesConfirmed;
        final Throwable error;
        Result(boolean succeeded, boolean sideOwned, Throwable error) {
            this(succeeded, sideOwned, true, error);
        }
        Result(boolean succeeded, boolean sideOwned, boolean rolesConfirmed, Throwable error) {
            this.succeeded = succeeded; this.sideOwned = sideOwned; this.error = error;
            this.rolesConfirmed = rolesConfirmed;
        }
    }

    /**
     * Runs on Shell main. Returning false means no transition was submitted; callers retain the
     * layout/tasks and report the failure. Once accepted, completed is the only completion path.
     * input excludes the two moving physical rectangles from Host's touch region; real app input
     * continues through SurfaceFlinger's window transforms. Editor raw is unchanged and pending QA.
     */
    static boolean start(ClassLoader loader, Object taskViews, Object controller,
            LiveSwapGeometry.Task first, LiveSwapGeometry.Task second, int userId, Rect logical,
            float mainScale, float mainX, float mainY, float sideScale, Object cellBackground,
            BooleanSupplier sceneCurrent, Consumer<LiveSwapGeometry.Frame[]> input,
            Consumer<Result> completed, Consumer<String> log) {
        Object shell = XposedHelpers.getObjectField(taskViews, "mTransitions");
        Object executor = XposedHelpers.getObjectField(shell, "mMainExecutor");
        if (ShellCompat.hasMethod(executor.getClass(), "assertCurrentThread", 0))
            XposedHelpers.callMethod(executor, "assertCurrentThread");
        synchronized (ACTIVE) { if (ACTIVE.containsKey(controller)) return false; }
        if (mainScale <= 0 || mainScale >= 1 || sideScale <= 0 || sideScale >= mainScale
                || !Float.isFinite(mainScale) || !Float.isFinite(mainX) || !Float.isFinite(mainY)
                || !Float.isFinite(sideScale) || !sceneCurrent.getAsBoolean()) return false;
        LiveTaskPair pair = null;
        Object cleanup = null;
        try {
            Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
            Object area = ShellCompat.rootArea(organizer);
            Object areaInfo = ShellCompat.call(area, "getDisplayAreaInfo", 0);
            Object parent = ShellCompat.call(area, "getDisplayAreaLeash", 0);
            Object side = XposedHelpers.getObjectField(controller, "mSurfaceControl");
            if (areaInfo == null || XposedHelpers.getIntField(areaInfo, "featureId") != 1
                    || XposedHelpers.getIntField(areaInfo, "displayId") != 0) return false;
            pair = new LiveTaskPair(organizer, taskViews, controller, first.id, first.token,
                    second.id, second.token, userId, parent, side, logical, log);
            if (!pair.preflight()) return false;
            LiveTaskPair ownedPair = pair;
            LiveSwapSession.ShellBackend backend = new LiveSwapSession.ShellBackend(shell, loader);
            Active active = new Active(backend);
            LiveSwapGeometry.Affine parentToScreen = new LiveSwapGeometry.Affine(mainScale, 0, 0,
                    mainScale, mainX, mainY);
            Object wct = pair.windowTransaction(null);
            pair.arm(loader);
            LiveSwapSession session = new LiveSwapSession(backend, new PairScene(ownedPair,shell,backend,sceneCurrent,input,completed,active,controller,sideScale,cellBackground,parentToScreen), first, second, 300L);
            active.session = session;
            synchronized (ACTIVE) { ACTIVE.put(controller, active); }
            if (session.start(wct)) return true;
            synchronized (ACTIVE) { if (ACTIVE.get(controller) == active) ACTIVE.remove(controller); }
            cleanup = backend.newTransaction();
            pair.recover(cleanup, sideScale); backend.apply(cleanup); backend.close(cleanup); cleanup = null;
            return false;
        } catch (Throwable error) {
            log.accept("live swap refused/failed before submission: " + error);
            if (pair != null) {
                try {
                    if (cleanup == null) cleanup = TransactionCompat.newSurfaceTransaction();
                    pair.recover(cleanup, sideScale); XposedHelpers.callMethod(cleanup, "apply");
                } catch (Throwable recoveryError) { log.accept("live pair refusal cleanup: " + recoveryError); }
            }
            return false;
        } finally {
            if (cleanup != null) XposedHelpers.callMethod(cleanup, "close");
        }
    }


    private static final class PairScene implements LiveSwapSession.Scene {
        private final LiveTaskPair pair;private final Object shell;private final LiveSwapSession.ShellBackend backend;
        private final BooleanSupplier sceneCurrent;private final Consumer<LiveSwapGeometry.Frame[]> input;
        private final Consumer<Result> completed;private final Active active;private final Object controller;
        private final float sideScale;private final Object cellBackground;private final LiveSwapGeometry.Affine parentToScreen;
        private Throwable cleanupError;private boolean waitingCancel,delivered;
        PairScene(LiveTaskPair pair,Object shell,LiveSwapSession.ShellBackend backend,BooleanSupplier sceneCurrent,
                Consumer<LiveSwapGeometry.Frame[]> input,Consumer<Result> completed,Active active,Object controller,
                float sideScale,Object cellBackground,LiveSwapGeometry.Affine parentToScreen){
            this.pair=pair;this.shell=shell;this.backend=backend;this.sceneCurrent=sceneCurrent;this.input=input;
            this.completed=completed;this.active=active;this.controller=controller;this.sideScale=sideScale;this.cellBackground=cellBackground;this.parentToScreen=parentToScreen;
        }

                private void background(Object tx, boolean visible) {
                    if (cellBackground != null && Boolean.TRUE.equals(XposedHelpers.callMethod(cellBackground, "isValid")))
                        XposedHelpers.callMethod(tx, visible ? "show" : "hide", cellBackground);
                }
                @Override public boolean mayStart() { return sceneCurrent.getAsBoolean(); }
                @Override public LiveSwapSession.Ready resolve(Object info) {
                    return sceneCurrent.getAsBoolean() ? pair.resolve(info, parentToScreen) : null;
                }
                @Override public void prepare(Object start, Object finish, LiveSwapSession.Ready ready) {
                    if (!sceneCurrent.getAsBoolean()) throw new IllegalStateException("live scene changed before start");
                    try { pair.prepare(start, finish); }
                    catch (Exception error) { throw new IllegalStateException("live pair claim failed", error); }
                    background(start, false);
                }
                @Override public void input(Object transaction, LiveSwapGeometry.Frame[] frame) {
                    if (!sceneCurrent.getAsBoolean()) throw new IllegalStateException("live scene changed mid-frame");
                    input.accept(frame);
                }
                @Override public void finish(Object transaction, boolean failed) {
                    if (waitingCancel && !pair.originalRoles())
                        throw new IllegalStateException("pending inverse roles remain unconfirmed");
                    if (failed) pair.recover(transaction, sideScale);
                    else pair.finish(transaction, sideScale);
                    background(transaction, true);
                }
                @Override public Object finishWindowTransaction() { return pair.finishWindowTransaction(); }
                @Override public void requestWaitingCancel() {
                    waitingCancel = true;
                    try {
                        XposedHelpers.callMethod(XposedHelpers.getObjectField(shell, "mOrganizer"),
                                "applyTransaction", pair.reverseWindowTransaction());
                    } catch (Exception error) { throw new IllegalStateException("pending reverse WCT failed", error); }
                }
                @Override public boolean waitingCancelReady() { return pair.originalRoles(); }
                @Override public void consumed(Object transaction, boolean aborted) {
                    pair.recover(transaction, sideScale);
                    background(transaction, true);
                }
                @Override public void complete(SwapSessionState state, Throwable error) {
                    Runnable deliver = () -> {
                        if (delivered) return;
                        delivered = true;
                        synchronized (ACTIVE) { if (ACTIVE.get(controller) == active) ACTIVE.remove(controller); }
                        boolean owned = cleanupError == null && pair.sideOwned();
                        Throwable resultError = error != null ? error : cleanupError;
                        completed.accept(new Result(state == SwapSessionState.FINISHED
                                && owned && resultError == null, owned,
                                owned || pair.originalRoles(), resultError));
                    };
                    if (waitingCancel && !pair.originalRoles()) {
                        // Do not infer roles from an old canonical object after the reverse IPC.
                        pair.releaseOriginalLeaseWhenSeen();
                        synchronized (ACTIVE) { if (ACTIVE.get(controller) == active) ACTIVE.remove(controller); }
                        completed.accept(new Result(false, false, false, error));
                        return;
                    }
                    if (state == SwapSessionState.DECLINED || state == SwapSessionState.FAILED) {
                        Object tx = backend.newTransaction();
                        try {
                            pair.recover(tx, sideScale); background(tx, true);
                            boolean[] committed = {false};
                            backend.committed(tx, () -> { committed[0] = true; deliver.run(); });
                            backend.apply(tx);
                            backend.await(() -> committed[0], 1500L, acknowledged -> {
                                if (!acknowledged && !delivered) {
                                    cleanupError = new IllegalStateException("recovery transaction commit not acknowledged");
                                    deliver.run();
                                }
                            });
                        }
                        catch (Throwable failure) { cleanupError = failure; deliver.run(); }
                        finally { backend.close(tx); }
                        return;
                    }
                    deliver.run();
                }

    }
}
