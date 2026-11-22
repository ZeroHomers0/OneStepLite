package io.github.zerohomers0.onesteplite;

import android.content.Context;
import java.util.function.Consumer;

/** Publishes one zoom request through independent broadcast/settings and atomic-file channels. */
final class ZoomTransport {
    private final Context ctx;
    private final Consumer<String> logger;
    private String lastBroadcastZoom, lastFileZoom;
    ZoomTransport(Context context,Consumer<String> logger) { ctx=context;this.logger=logger; }
    private void log(String text) { if (RoutineLog.allow(text)) logger.accept(text); }
    // Legacy system_server fallback reads only this numeric zoom tuple.
    @android.annotation.SuppressLint("SetWorldReadable")
    void publish(String command,long issuedAt) {
        // Independent transport: a failed private-file write must not suppress zoom/restore.
        broadcastZoom(command, issuedAt);
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "onesteplite-zoom");
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
            String encoded = ZoomChannelState.encode(command, issuedAt);
            String steady = command.trim();
            if (steady.startsWith("anim")) {
                String[] parts = steady.split("\\s+");
                if (parts.length < 4) return;
                steady = parts[1] + " " + parts[2] + " " + parts[3];
            }
            boolean changed = !command.equals(lastBroadcastZoom);
            try {
                // A heartbeat renews the timestamp too; rereading Settings must not renew it.
                if (!android.provider.Settings.Global.putString(ctx.getContentResolver(), "onesteplite_zoom", encoded)) {
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
            intent.putExtra("request", encoded);
            intent.putExtra("scale", scale);
            intent.putExtra("offsetY", offsetY);
            intent.putExtra("offsetX", offsetX);
            intent.addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            ctx.sendBroadcast(intent);
            if (changed) log("zoom broadcast sent: " + command);
            lastBroadcastZoom = command;
        } catch (Throwable error) {
            log("zoom broadcast failed: " + error);
        }
    }
}
