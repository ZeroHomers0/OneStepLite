package io.github.zerohomers0.onesteplite;

import static io.github.zerohomers0.onesteplite.HostEntry.*;
import static io.github.zerohomers0.onesteplite.ZoomSettingsRuntime.startZoomSettingsWatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import de.robv.android.xposed.XposedHelpers;

/** Arms the authenticated WMS receiver and serializes correlated live zoom requests. */
final class SystemServerZoomBridge {
    private SystemServerZoomBridge() {}

    /** 文件通道在部分机型被 SELinux/权限挡住；再开一条 SystemUI -> system_server 的广播直投。 */
    static void arm(Object service) {
        try {
            final Context context = (Context) XposedHelpers.getObjectField(service, "mContext");
            sWmsContext = context;
            VideoDock.install(service,context,service.getClass().getClassLoader());
            startZoomSettingsWatcher();
            if (sZoomBridgeArmed) return;
            android.os.HandlerThread thread = new android.os.HandlerThread("onesteplite-zoom-bridge");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            sZoomBridgeHandler = handler;
            sRetainedWindow.setHandler(handler);
            BroadcastReceiver receiver = new ZoomBridgeReceiver(context,service);
            IntentFilter filter = new IntentFilter(Actions.ZOOM);
            filter.addAction(Actions.ZOOM_LEASE);
            filter.addAction(LiveSwapZoomProtocol.APPLY);
            filter.addAction(UnlockHandoff.RELEASE);
            filter.addAction(RetainedWindow.ACTION);
            filter.addAction(SwapOverlayBridge.ACTION);
            filter.addAction(Actions.TRANSFER_TOUCH);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(InputTrace.ACTION);
            filter.addAction(BottomReplayInput.ACTION);
            filter.addAction(DesktopLaunchBridge.ACTION);
            filter.addAction(DesktopLaunchBridge.PREPARE_TOP);
            filter.addAction(Actions.WORKSPACE_WALLPAPER_PARENT);
            // SystemUI / shell can send; ordinary apps cannot change display magnification.
            // minSdk is 34, so the exported-receiver flag is always available.
            context.registerReceiver(receiver, filter, android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    handler, Context.RECEIVER_EXPORTED);
            sZoomBridgeArmed = true;
            BottomReplayInput.install(context, service.getClass().getClassLoader(), handler);
            log("zoom broadcast bridge armed");
            new WorkspaceSettingsClient(context,handler,options->sDragShareEnabled=options.dragShareEnabled);
        } catch (Throwable error) {
            log("zoom broadcast bridge failed: " + error);
        }
    }

    static void receiveLive(Context context, Intent intent) {
        long generation = intent.getLongExtra(LiveSwapZoomProtocol.GENERATION, 0L);
        long issued = intent.getLongExtra(LiveSwapZoomProtocol.ISSUED, 0L);
        float scale = Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.SCALE, 0));
        float x = Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.X, 0));
        float y = Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.Y, 0));
        ZoomTarget target = sZoomTarget;
        if (target == null || !LiveSwapZoomProtocol.valid(generation, scale, x, y,
                issued, System.currentTimeMillis())) return;
        LiveSwapZoom.Zoom zoom = new LiveSwapZoom.Zoom(generation, scale, x, y);
        target.handler.post(() -> {
            ZoomCommit commit = new ZoomCommit(context, target, zoom,
                    () -> target == sZoomTarget && zoom.sameTuple(sZoomScale, sZoomOffsetX, sZoomOffset),
                    HostEntry::createMagnificationFinishReplay,
                    value -> { if (sLiveZoomCommit == value) sLiveZoomCommit = null; },
                    message -> log(message));
            try {
                synchronized (target.globalLock) {
                    if (target != sZoomTarget || issued < sLiveZoomIssued) {
                        commit.finish(false, "stale zoom target or lease"); return;
                    }
                    synchronized (sZoomChannels) {
                        long now = System.currentTimeMillis();
                        if (!LiveSwapZoomProtocol.valid(generation, scale, x, y, issued, now)) {
                            commit.finish(false, "expired zoom lease"); return;
                        }
                        String command = scale + " " + y + " " + x;
                        sZoomChannels.offer(command, issued, now);
                        ZoomChannelState.Request current = sZoomChannels.current(now);
                        if (current.modified > issued && !command.equals(current.command)) {
                            commit.finish(false, "superseded zoom lease"); return;
                        }
                    }
                    if (sLiveZoomCommit != null) sLiveZoomCommit.finish(false, "superseded generation");
                    if (intent.getBooleanExtra(UnlockHandoff.PHYSICAL_KEYGUARD, false)) {
                        if (!sKeyguardZoom.onAuthenticatedReveal(EntryGuard.canOpen(context))) {
                            commit.finish(false, "unlock admission changed"); return;
                        }
                        sUnlockPhysicalShadeGeneration = generation;
                        sUnlockPhysicalShadeUntil = android.os.SystemClock.uptimeMillis()
                                + LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS;
                    }
                    sLiveZoomIssued = issued;
                    sLiveZoomCommit = commit;
                    target.dispatch.takeOver(target, scale, y, x);
                    target.owner.cancelZoomAnimation(target);
                    // Logical WM parents already have their final roles, while the real
                    // leashes still animate under Host. Applying this traversal's clear
                    // operations now exposes an identity/fullscreen task for one frame.
                    // Commit the WM spec/input state; the exact visual traversal is already
                    // regenerated by ZoomCommit.finish and merged with Shell's reparent.
                    Object staged = TransactionCompat.newSurfaceTransaction();
                    if (!sLiveSwapZoomSurfaces.begin(target.display, staged)) {
                        LiveSwapZoom.closeReplay(staged);
                        throw new IllegalStateException("live zoom surface scope already active");
                    }
                    try { target.owner.applyDisplayZoomLocked(target, scale, y, x, commit, false); }
                    finally {
                        sLiveSwapZoomSurfaces.finish(target.display, staged);
                        LiveSwapZoom.closeReplay(staged);
                    }
                }
                target.handler.postDelayed(() -> commit.finish(false, "WMS transaction commit timeout"),
                        LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS - 100L);
            } catch (Throwable failure) {
                target.dispatch.invalidateLastAttempt();
                commit.finish(false, "WMS zoom apply failed: " + failure.getClass().getSimpleName());
            }
        });
    }
}
