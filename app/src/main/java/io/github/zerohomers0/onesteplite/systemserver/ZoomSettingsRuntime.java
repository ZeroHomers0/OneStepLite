package io.github.zerohomers0.onesteplite;

import android.os.Handler;
import android.os.Looper;
import static io.github.zerohomers0.onesteplite.HostEntry.*;
import static io.github.zerohomers0.onesteplite.ZoomRuntime.dispatchCurrentZoom;

/** Settings.Global wakeup and command decoding for zoom. */
final class ZoomSettingsRuntime {
    private ZoomSettingsRuntime() {}

    static volatile String sLastSettingsZoom;
    static boolean sZoomSettingsWatcherArmed;
    static android.database.ContentObserver sZoomSettingsObserver;

    static synchronized void startZoomSettingsWatcher() {
        if (sZoomSettingsWatcherArmed) return;
        android.content.Context context = sWmsContext;
        if (context == null) return;
        android.database.ContentObserver observer = new android.database.ContentObserver(
                new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) { readZoomSetting(context); }
        };
        try {
            context.getContentResolver().registerContentObserver(
                    android.provider.Settings.Global.getUriFor("onesteplite_zoom"), false, observer);
            sZoomSettingsObserver = observer;
            sZoomSettingsWatcherArmed = true;
            readZoomSetting(context);
            log("zoom settings observer armed");
        } catch (Throwable error) {
            sZoomSettingsObserver = null;
            log("zoom settings observer failed: " + error);
        }
    }

    static void readZoomSetting(android.content.Context context) {
        try {
            String value = android.provider.Settings.Global.getString(
                    context.getContentResolver(), "onesteplite_zoom");
            synchronized (sZoomChannels) {
                long now = System.currentTimeMillis();
                boolean accepted = sZoomChannels.offerEncoded(value, now);
                if (accepted && !java.util.Objects.equals(value, sLastSettingsZoom))
                    log("zoom settings received: " + value);
                sLastSettingsZoom = value;
                dispatchCurrentZoom();
            }
        } catch (Throwable error) { log("zoom settings read failed: " + error); }
    }
}
