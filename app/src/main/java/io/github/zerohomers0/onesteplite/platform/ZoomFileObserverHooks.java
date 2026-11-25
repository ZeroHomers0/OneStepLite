package io.github.zerohomers0.onesteplite;

import android.os.FileObserver;
import java.util.function.Consumer;

/** Immediately dispatches Host-written zoom files from SystemUI private storage. */
final class ZoomFileObserverHooks {
    private ZoomFileObserverHooks() {}

    static void install(Consumer<FileObserver> installed,
            Consumer<java.io.File> dispatchZoom, Consumer<String> log) {
        try {
            final java.io.File dir =
                    new java.io.File("/data/user_de/0/com.android.systemui/files");
            if (!dir.isDirectory()) {
                log.accept("zoom FileObserver skipped: no SystemUI files dir");
                return;
            }
            FileObserver observer = new FileObserver(dir,
                    FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.CREATE) {
                @Override public void onEvent(int event, String path) {
                    if (path == null || !path.endsWith("onesteplite-zoom")) return;
                    dispatchZoom.accept(new java.io.File(dir, "onesteplite-zoom"));
                }
            };
            observer.startWatching();
            installed.accept(observer);
            log.accept("zoom FileObserver armed on SystemUI files dir");
        } catch (Throwable error) {
            log.accept("zoom FileObserver failed: " + error);
        }
    }
}
