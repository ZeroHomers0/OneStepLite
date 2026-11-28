package io.github.zerohomers0.onesteplite;

import android.content.Context;
import java.util.function.Consumer;

/** Publishes one zoom request through independent broadcast/settings and atomic-file channels. */
final class ZoomTransport {
    private final Context ctx;
    private final Consumer<String> logger;
    private final android.os.IBinder hostLease = new android.os.Binder();
    private String lastBroadcastZoom, lastFileZoom;
    private volatile boolean leaseAcked, leaseRequestPending;
    private long leaseRequestStartedAt, nextLeaseCheck;
    ZoomTransport(Context context,Consumer<String> logger) { ctx=context;this.logger=logger; }
    private void log(String text) { if (RoutineLog.allow(text)) logger.accept(text); }
    boolean leaseAcked() { return leaseAcked; }

    private void requestLease(boolean force) {
        long now = android.os.SystemClock.uptimeMillis();
        if (leaseRequestPending) {
            if (now - leaseRequestStartedAt < 5000L) return;
            leaseRequestPending = false;
        }
        if (leaseAcked && !force) return;
        leaseRequestPending = true;
        leaseRequestStartedAt = now;
        android.os.Bundle lease = new android.os.Bundle();
        lease.putBinder("host", hostLease);
        android.content.Intent intent = new android.content.Intent(Actions.ZOOM_LEASE)
                .putExtra(WorkspaceContract.EXTRA_LEASE, lease)
                .addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        android.content.BroadcastReceiver result = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, android.content.Intent value) {
                leaseRequestPending = false;
                leaseRequestStartedAt = 0L;
                leaseAcked = getResultCode() == android.app.Activity.RESULT_OK;
                nextLeaseCheck = android.os.SystemClock.uptimeMillis() + 300000L;
                log("zoom host lease " + (leaseAcked ? "acknowledged" : "not acknowledged"));
            }
        };
        try {
            ctx.sendOrderedBroadcast(intent, android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    result, new android.os.Handler(android.os.Looper.getMainLooper()),
                    android.app.Activity.RESULT_CANCELED, null, null);
        } catch (Throwable failure) {
            leaseRequestPending = false;
            leaseRequestStartedAt = 0L;
            log("zoom host lease request failed: " + failure);
        }
    }

    /** Heartbeats renew only the file lease; Settings/DB and broadcast stay event-driven. */
    boolean renew(String command,long issuedAt) {
        requestLease(false);
        if (command == null || !command.equals(lastFileZoom)) return false;
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), ZoomProtocol.FILE_NAME);
            if (!f.isFile() || !f.setLastModified(issuedAt)) return false;
            log("zoom lease renewed: " + command);
            return true;
        } catch (Throwable failure) {
            log("zoom lease renewal failed: " + failure);
            return false;
        }
    }

    /** Revalidate a previously acknowledged lease after a possible system_server restart. */
    void maintainLease() {
        if (!leaseAcked) { requestLease(false); return; }
        if (android.os.SystemClock.uptimeMillis() >= nextLeaseCheck) requestLease(true);
    }
    // Legacy system_server fallback reads only this numeric zoom tuple.
    @android.annotation.SuppressLint("SetWorldReadable")
    void publish(String command,long issuedAt) {
        requestLease(false);
        // Independent transport: a failed private-file write must not suppress zoom/restore.
        broadcastZoom(command, issuedAt);
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), ZoomProtocol.FILE_NAME);
            // Identical contents only need a fresh lease timestamp, not another fsync.
            if (!command.trim().startsWith("anim") && command.equals(lastFileZoom) && f.isFile() && f.setLastModified(issuedAt)) return;
            android.util.AtomicFile file = new android.util.AtomicFile(f);
            java.io.FileOutputStream stream = null;
            try {
                stream = file.startWrite();
                stream.write(command.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                file.finishWrite(stream);
                lastFileZoom = command;
                f.setLastModified(issuedAt);
                // system_server 需要读这个文件来应用单手模式缩放；AtomicFile 默认 0600，必须放开读权限。
                if (!f.setReadable(true, false)) {
                    log("writeZoom: setReadable(false) returned false");
                }
            } catch (java.io.IOException failure) {
                if (stream != null) file.failWrite(stream);
                throw failure;
            }
            log("zoom trigger written: " + command);
        } catch (Throwable t) {
            log("writeZoom failed: " + t);
        }
    }

    private void broadcastZoom(String command, long issuedAt) {
        try {
            String encoded = ZoomProtocol.encode(command, issuedAt);
            String steady = ZoomProtocol.steady(command);
            boolean changed = !command.equals(lastBroadcastZoom);
            try {
                // A heartbeat renews the timestamp too; rereading Settings must not renew it.
                if (!android.provider.Settings.Global.putString(ctx.getContentResolver(), ZoomProtocol.SETTINGS_KEY, encoded)) {
                    log("zoom settings write returned false");
                }
            } catch (Throwable settingsError) {
                log("zoom settings write failed: " + settingsError);
            }
            String[] parts = steady.split("\\s+");
            float scale = Float.parseFloat(parts[0]);
            float offsetY = Float.parseFloat(parts[1]);
            float offsetX = Float.parseFloat(parts[2]);
            android.content.Intent intent = new android.content.Intent(Actions.ZOOM);
            intent.putExtra(WorkspaceContract.EXTRA_PROTOCOL_VERSION, ProtocolVersion.CURRENT);
            intent.putExtra(WorkspaceContract.EXTRA_ZOOM_REQUEST, encoded);
            intent.putExtra(WorkspaceContract.EXTRA_SCALE, scale);
            intent.putExtra(WorkspaceContract.EXTRA_OFFSET_Y, offsetY);
            intent.putExtra(WorkspaceContract.EXTRA_OFFSET_X, offsetX);
            intent.addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            ctx.sendBroadcast(intent);
            if (changed) log("zoom broadcast sent: " + command);
            lastBroadcastZoom = command;
        } catch (Throwable error) {
            log("zoom broadcast failed: " + error);
        }
    }
}
