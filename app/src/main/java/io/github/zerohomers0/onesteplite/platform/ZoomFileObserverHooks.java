package io.github.zerohomers0.onesteplite;

import android.os.FileObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Immediately dispatches Host-written zoom files from SystemUI and legacy system storage. */
final class ZoomFileObserverHooks {
    private static final String NAME="onesteplite-zoom";
    private ZoomFileObserverHooks() {}

    /** Returns whether the legacy /data/system directory is actively observed. */
    static boolean install(Consumer<FileObserver[]> installed,
            Consumer<java.io.File> dispatchZoom, Consumer<String> log) {
        List<FileObserver> observers=new ArrayList<>();
        boolean system=observe(new java.io.File("/data/system"),"system",observers,dispatchZoom,log);
        observe(new java.io.File("/data/user_de/0/com.android.systemui/files"),"SystemUI",
                observers,dispatchZoom,log);
        if(!observers.isEmpty())installed.accept(observers.toArray(new FileObserver[0]));
        return system;
    }

    private static boolean observe(java.io.File dir,String label,List<FileObserver> observers,
            Consumer<java.io.File> dispatchZoom,Consumer<String> log) {
        if(!dir.isDirectory()) {
            log.accept("zoom FileObserver skipped: no "+label+" dir");
            return false;
        }
        try {
            FileObserver observer=new FileObserver(dir,
                    FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.CREATE) {
                @Override public void onEvent(int event,String path) {
                    if(path==null || !path.endsWith(NAME))return;
                    dispatchZoom.accept(new java.io.File(dir,NAME));
                }
            };
            observer.startWatching();
            observers.add(observer);
            log.accept("zoom FileObserver armed on "+label+" dir");
            return true;
        }catch(Throwable error) {
            log.accept("zoom FileObserver failed on "+label+" dir: "+error);
            return false;
        }
    }
}
