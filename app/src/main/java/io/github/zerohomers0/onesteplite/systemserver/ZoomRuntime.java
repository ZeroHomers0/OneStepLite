package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.os.IBinder;
import static io.github.zerohomers0.onesteplite.HostEntry.*;

/** Zoom watchdog, command dispatch and Binder lease lifecycle owned outside the Xposed entry. */
final class ZoomRuntime {
    private ZoomRuntime() {}

    static void startZoomFileWatcher(){
        try{
            Context context=(Context)sSystemContext;
            android.os.PowerManager power=context==null?null:context.getSystemService(android.os.PowerManager.class);
            if(power!=null)onZoomScreenState(power.isInteractive());
            armZoomWatch(0L);
            log("ZOOM lease watcher armed; transforms only on locked WMS handler");
        }catch(Throwable failure){log("ZOOM watcher failed: "+failure);}
    }

    static void onZoomScreenState(boolean on){
        synchronized(sZoomWatchLock){
            if(sZoomWatchScreenOn==on)return;
            sZoomWatchScreenOn=on;
            sZoomWatchGeneration++;
            if(sZoomWatch!=null)sZoomWatch.cancel(false);
            sZoomWatch=null;
            if(on)armZoomWatch(0L);
        }
    }

    private static void armZoomWatch(long delay){
        synchronized(sZoomWatchLock){
            if(sZoomWatch!=null||!sZoomWatchScreenOn)return;
            long generation=sZoomWatchGeneration;
            sZoomWatch=BackgroundExecutor.schedule(()->pollZoomFiles(generation),delay);
        }
    }

    private static void accelerateZoomWatch(){
        synchronized(sZoomWatchLock){
            if(!sZoomWatchScreenOn)return;
            if(sZoomWatch!=null){
                if(sZoomWatch.getDelay(java.util.concurrent.TimeUnit.MILLISECONDS)<=ZoomCommand.FAST_WATCH_MS)return;
                sZoomWatch.cancel(false);
                sZoomWatch=null;
                sZoomWatchGeneration++;
            }
            armZoomWatch(ZoomCommand.FAST_WATCH_MS);
        }
    }

    private static void pollZoomFiles(long generation){
        synchronized(sZoomWatchLock){
            if(generation!=sZoomWatchGeneration || !sZoomWatchScreenOn)return;
            sZoomWatch=null;
        }
        try{
            java.io.File systemFile=new java.io.File("/data/system/"+ZoomProtocol.FILE_NAME);
            java.io.File uiFile=new java.io.File("/data/user_de/0/com.android.systemui/files/"+ZoomProtocol.FILE_NAME);
            java.io.File file=systemFile;
            if(uiFile.exists()&&(!systemFile.exists()||uiFile.lastModified()>systemFile.lastModified()))file=uiFile;
            readAndDispatchZoom(file);
        }catch(Throwable ignored){
            // FileObserver/settings/broadcast paths remain authoritative; this is a bounded fallback.
        }finally{
            long delay=nextZoomWatchDelay();
            synchronized(sZoomWatchLock){
                if(generation==sZoomWatchGeneration)armZoomWatch(delay);
            }
        }
    }

    private static long nextZoomWatchDelay(){
        if(!sZoomed && sZoomScale==1f && sZoomAnimator==null)return sZoomObserversArmed?60_000L:10_000L;
        if(sZoomHostLease.alive())return 60_000L;
        long now=System.currentTimeMillis();
        ZoomChannelState.Request request=sZoomChannels.current(now);
        float[] geometry=ZoomCommand.read(request.command,request.modified,sZoomWatcherStarted,now,false);
        boolean steady=sZoomObserversArmed && geometry[0]<1f && matchesAppliedZoom(geometry);
        return ZoomCommand.activeWatchDelay(request.modified,now,steady);
    }

    private static boolean matchesAppliedZoom(float[] geometry){
        return sZoomTarget!=null && sZoomAnimator==null
                && sZoomScale==geometry[0] && sZoomOffset==geometry[1] && sZoomOffsetX==geometry[2];
    }

    static boolean acceptZoomLease(IBinder lease) {
        boolean accepted = sZoomHostLease.accept(lease);
        if (accepted) log("zoom host binder lease accepted");
        return accepted;
    }

    static void onZoomHostLost() {
        log("zoom host binder died; restoring steady state");
        long now = System.currentTimeMillis();
        synchronized (sZoomChannels) {
            sZoomChannels.offer("1 0 0", now, now);
            dispatchCurrentZoom();
        }
    }

    static void readAndDispatchZoom(java.io.File f) {
        String cur = "";
        long modified = 0L;
        Throwable readError = null;
        try {
            if (f != null) {
                modified = f.lastModified();
                if (f.exists()) {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.FileReader(f));
                    cur = br.readLine();
                    br.close();
                }
            }
        } catch (Throwable error) {
            readError = error;
            if (sZoomReadError == null || !sZoomReadError.equals(String.valueOf(error))) {
                sZoomReadError = String.valueOf(error);
                log("zoom read error: " + error);
            }
            cur = "";
        }
        if (cur == null) cur = "";
        if (!cur.equals(sZoomLastSeen)) {
            sZoomLastSeen = cur;
            log("zoom cmd seen: '" + cur + "' modified=" + modified
                    + " target=" + (sZoomTarget != null) + " readError=" + readError
                    + " canRead=" + (f != null && f.canRead())
                    + " perms=" + (f != null && f.exists() ? Long.toOctalString(f.length()) : "-"));
        }
        long now = System.currentTimeMillis();
        synchronized (sZoomChannels) {
            sZoomChannels.offer(cur, modified, now);
            dispatchCurrentZoom();
        }
    }

    static void dispatchCurrentZoom() {
        synchronized (sZoomChannels) {
            long now = System.currentTimeMillis();
            boolean hostLeaseAlive = sZoomHostLease.alive();
            ZoomChannelState.Request selected = sZoomChannels.current(now, hostLeaseAlive);
            ZoomTarget target = sZoomTarget;
            if (target == null) {
                if (!selected.command.equals(sZoomLastTargetNull)) {
                    sZoomLastTargetNull = selected.command;
                    log("zoom request retained until display ready: " + selected.command);
                }
                return;
            }
            ZoomAnimCommand.Plan anim = ZoomAnimCommand.parse(selected.command,
                    selected.modified, sZoomWatcherStarted, now);
            if (anim != null) {
                accelerateZoomWatch();
                target.submitAnim(anim, selected.animationKey);
            } else {
                float[] request = ZoomCommand.read(selected.command,
                        selected.modified, sZoomWatcherStarted, now, hostLeaseAlive);
                if((request[0]<1f || sZoomed || sZoomAnimator!=null) && !matchesAppliedZoom(request))
                    accelerateZoomWatch();
                if (!selected.command.equals(sZoomLastDispatched)) {
                    sZoomLastDispatched = selected.command;
                    log("zoom dispatch " + java.util.Arrays.toString(request)
                            + " from leased request '" + selected.command + "'");
                }
                target.dispatch.submit(target, request[0], request[1], request[2]);
            }
        }
    }

    static void setHostZoomed(boolean zoomed) {
        sHostZoomed = zoomed;
    }

    static void installZoomFileObserver() {
        sZoomObserversArmed=ZoomFileObserverHooks.install(
                observers -> sZoomFileObservers=observers,
                ZoomRuntime::readAndDispatchZoom, HostEntry::log);
    }
}
