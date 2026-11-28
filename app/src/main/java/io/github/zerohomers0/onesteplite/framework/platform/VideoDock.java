package io.github.zerohomers0.onesteplite;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.SurfaceControl;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Dock an existing native PiP/video overlay surface. Never resize or move an application task. */
final class VideoDock {
    static final String HOST=Actions.VIDEO_HOST, STATE=Actions.VIDEO_STATE,
            COMMAND=Actions.VIDEO_COMMAND;
    private static final String PERMISSION=android.Manifest.permission.WRITE_SECURE_SETTINGS;
    private static VideoDock instance;
    private final Object service,lock;
    private final Context context;
    private final Handler handler;
    private final Set<Object> inputHandles=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Object,Object> suppressedSurfaces=new IdentityHashMap<>();
    // WM owns these containers. Releasing the dock must not keep a removed task or window alive.
    private final Set<Object> dismissed=Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private volatile List<MediaController> players=Collections.emptyList();
    private volatile Map<String,Integer> playStates=Collections.emptyMap();
    private final java.util.concurrent.ConcurrentHashMap<String,Boolean> videoPackages=new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> querying=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private IBinder owner;
    private final java.util.LinkedHashSet<IBinder> retiredOwners=new java.util.LinkedHashSet<>();
    private IBinder.DeathRecipient ownerDeath;
    private SurfaceControl host,dock;
    private Source source;
    private int width,height;
    private long sequence=-1,sourceId;
    private volatile boolean active;
    private boolean preparingFrame;
    private boolean fill;
    private int hostUid=-1;
    private final Binder releaseToken=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            if(code!=IBinder.FIRST_CALL_TRANSACTION)return super.onTransact(code,data,reply,flags);
            data.enforceInterface("io.github.zerohomers0.onesteplite.VideoRelease");IBinder binding=data.readStrongBinder();
            synchronized(lock){
                if(Binder.getCallingUid()!=hostUid)throw new SecurityException("video release caller");
                if(owner==null||!owner.equals(binding)) {
                    if(!retiredOwners.contains(binding))throw new SecurityException("video release owner");
                    Object tx=call(display(),"getPendingTransaction");if(tx!=null)XposedHelpers.callMethod(tx,"apply");
                    if(reply!=null)reply.writeNoException();return true;
                }
                active=false;handler.post(() -> updateMediaMonitoring());if(!restore())throw new IllegalStateException("native video return pending");Object display=display();Object tx=call(display,"getPendingTransaction");
                if(tx!=null)XposedHelpers.callMethod(tx,"apply");publish();
            }
            if(reply!=null)reply.writeNoException();return true;
        }
    };
    private String published="";
    private static final class Source {
        final Object container;final SurfaceControl surface;final boolean pip;final String pkg;
        final int layer;
        Source(Object c,SurfaceControl s,boolean p,String name){container=c;surface=s;pip=p;pkg=name;layer=integer(c,"mLastLayer",0);}
    }
    /** True while this dock owns the given task, so the top face never adopts a docked video. */
    static boolean owns(int taskId) {
        VideoDock dock=instance;
        if(dock==null || taskId<0)return false;
        synchronized(dock.lock){
            Source current=dock.source;
            return current!=null && current.pip && integer(current.container,"mTaskId",-1)==taskId;
        }
    }
    static synchronized void install(Object service,Context context,ClassLoader loader) {
        if(instance!=null)return;
        try {instance=new VideoDock(service,context,loader);}
        catch(Throwable error){log("video dock unavailable: "+error);}
    }
    private VideoDock(Object service,Context context,ClassLoader loader) {
        this.service=service;this.context=context;lock=field(service,"mGlobalLock");
        HandlerThread thread=new HandlerThread("OneStepLite-video-dock");thread.start();handler=new Handler(thread.getLooper());
        IntentFilter filter=new IntentFilter(HOST);filter.addAction(COMMAND);
        context.registerReceiver(new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
            if(!trusted(context,intent))return;
            if(HOST.equals(intent.getAction()))updateHost(intent);else command(intent);
        }},filter,PERMISSION,handler,Context.RECEIVER_EXPORTED);
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.server.wm.RootWindowContainer",loader),"prepareSurfaces",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(!p.hasThrowable())try{synchronized(lock){if(!active&&source==null&&dock==null&&suppressedSurfaces.isEmpty())return;preparingFrame=true;try{prepare();}finally{preparingFrame=false;}}}catch(Throwable error){log("video surface pass: "+error);}
            }
        });
        // Suppress native hit regions only while their video is docked. Input flags are
        // copied into the transaction and restored in Java immediately afterwards.
        try {java.util.Set<XC_MethodHook.Unhook> inputHooks=XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.server.wm.InputMonitor",loader),"setInputWindowInfoIfNeeded",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(p.hasThrowable()||p.args.length<3)return;
                synchronized(lock){Object handle=handle(p.args[2]);if(inputHandles.contains(handle)){suppress(p.args[0],p.args[1],handle);suppressedSurfaces.put(handle,p.args[1]);}}
            }
        });
        if(inputHooks.isEmpty())throw new IllegalStateException("native input metadata hook missing");
        java.util.Set<XC_MethodHook.Unhook> consumerHooks=XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.server.wm.InputConsumerImpl",loader),"show",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(p.hasThrowable()||p.args.length==0)return;
                synchronized(lock){if(source==null||!source.pip||!"pip_input_consumer".equals(field(p.thisObject,"mName")))return;
                    Object surface=field(p.thisObject,"mInputSurface");if(surface==null)surface=field(p.thisObject,"mSurfaceControl");
                    Object handle=field(p.thisObject,"mWindowHandle");suppress(p.args[0],surface,handle);if(handle!=null&&surface!=null)suppressedSurfaces.put(handle,surface);}
            }
        });if(consumerHooks.isEmpty())throw new IllegalStateException("native PiP input consumer hook missing");}catch(Throwable unavailable){log("video input isolation unavailable: "+unavailable);inputReady=false;}
        handler.post(poll);
    }
    private boolean inputReady=true;
    private MediaSessionManager mediaManager;
    private boolean mediaListenerRegistered, playbackCallbacksReady, mediaMonitoring, playbackStatesKnown;
    private long mediaPollDelay=2000L;
    private int stableMediaPolls;
    private final Map<android.media.session.MediaSession.Token,MediaController> observedPlayers = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::replaceMediaSessions;
    private boolean playbackRefreshQueued;
    private final Runnable playbackRefresh = () -> {
        playbackRefreshQueued = false;
        if (mediaMonitoring && active && owner != null && owner.isBinderAlive()) refreshPlaybackStates();
    };
    private final MediaController.Callback playbackChanged = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) {
            if (!mediaMonitoring || !active || playbackRefreshQueued) return;
            playbackRefreshQueued = true;
            handler.postDelayed(playbackRefresh, 40L);
        }
        @Override public void onSessionDestroyed() { refreshMediaSessions(); }
    };
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active || owner == null || !owner.isBinderAlive()) { stopMediaMonitoring(); return; }
            WorkspaceRuntimeCounters.MEDIA_WATCHDOG_POLLS.incrementAndGet();
            refreshMediaSessions();
            synchronized (lock) {
                handler.removeCallbacks(this);
                if (active && owner != null && owner.isBinderAlive()) {
                    long delay;
                    if (mediaListenerRegistered && playbackCallbacksReady) {
                        mediaPollDelay=2000L;
                        stableMediaPolls++;
                        delay=stableMediaPolls>=4 ? 60000L : 30000L;
                    } else {
                        stableMediaPolls=0;
                        delay=mediaPollDelay;
                        mediaPollDelay=Math.min(10000L,mediaPollDelay*2L);
                    }
                    handler.postDelayed(this,delay);
                }
            }
        }
    };
    private void updateMediaMonitoring() {
        if (!active || owner == null || !owner.isBinderAlive()) { stopMediaMonitoring(); return; }
        if (mediaMonitoring) return;
        mediaMonitoring = true;
        playbackStatesKnown=false;
        stableMediaPolls=0;
        try {
            if (mediaManager == null) mediaManager = context.getSystemService(MediaSessionManager.class);
            if (mediaManager != null && !mediaListenerRegistered) {
                mediaManager.addOnActiveSessionsChangedListener(sessionsChanged, null, handler);
                mediaListenerRegistered = true;
            }
        } catch (Throwable unavailable) { log("video media callbacks unavailable: " + unavailable); }
        handler.removeCallbacks(poll);
        handler.post(poll);
    }
    private void refreshMediaSessions() {
        if (!active) return;
        try {
            if (mediaManager == null) mediaManager = context.getSystemService(MediaSessionManager.class);
            replaceMediaSessions(mediaManager == null ? Collections.emptyList() : mediaManager.getActiveSessions(null));
        } catch (Throwable error) { playbackCallbacksReady = false; log("video refresh: " + error); }
    }
    private void replaceMediaSessions(List<MediaController> controllers) {
        if (!active) return;
        Map<android.media.session.MediaSession.Token,MediaController> next = new HashMap<>();
        if (controllers != null) for (MediaController player : controllers) next.put(player.getSessionToken(), player);
        for (android.media.session.MediaSession.Token token : new ArrayList<>(observedPlayers.keySet())) {
            if (next.containsKey(token)) continue;
            try { observedPlayers.get(token).unregisterCallback(playbackChanged); }
            catch (Throwable error) { log("video callback removal: " + error); }
            observedPlayers.remove(token);
        }
        playbackCallbacksReady = true;
        for (Map.Entry<android.media.session.MediaSession.Token,MediaController> entry : next.entrySet()) {
            if (observedPlayers.containsKey(entry.getKey())) continue;
            try {
                entry.getValue().registerCallback(playbackChanged, handler);
                observedPlayers.put(entry.getKey(), entry.getValue());
            } catch (Throwable unavailable) { playbackCallbacksReady = false; }
        }
        players = new ArrayList<>(next.values());
        refreshPlaybackStates();
    }
    private void refreshPlaybackStates() {
        if (playbackRefreshQueued) {
            handler.removeCallbacks(playbackRefresh);
            playbackRefreshQueued = false;
        }
        if (!active) return;
        Map<String,Integer> states = new HashMap<>();
        for (MediaController player : players) {
            try {
                PlaybackState state = player.getPlaybackState();
                if (state != null) states.put(player.getPackageName(), state.getState());
            } catch (Throwable gone) { /* Session-list callback or the watchdog reconciles destroyed players. */ }
        }
        WorkspaceRuntimeCounters.MEDIA_STATE_REFRESHES.incrementAndGet();
        if (!PlaybackStateRefreshPolicy.shouldPrepare(playbackStatesKnown, playStates, states)) return;
        playbackStatesKnown = true;
        WorkspaceRuntimeCounters.MEDIA_STATE_CHANGES.incrementAndGet();
        playStates = states;
        synchronized (lock) { prepare(); }
    }
    private void stopMediaMonitoring() {
        mediaMonitoring = false;
        playbackStatesKnown=false;
        mediaPollDelay=2000L;
        stableMediaPolls=0;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(playbackRefresh);
        playbackRefreshQueued = false;
        if (mediaManager != null && mediaListenerRegistered) {
            try { mediaManager.removeOnActiveSessionsChangedListener(sessionsChanged); }
            catch (Throwable error) { log("video session listener removal: " + error); }
        }
        mediaListenerRegistered = false;
        for (MediaController player : observedPlayers.values()) {
            try { player.unregisterCallback(playbackChanged); }
            catch (Throwable error) { log("video playback listener removal: " + error); }
        }
        observedPlayers.clear();players = Collections.emptyList();playStates = Collections.emptyMap();
    }
    private void updateHost(Intent intent) {
        synchronized(lock){
            IBinder next=intent.getExtras()==null?null:intent.getExtras().getBinder("owner");
            long nextSequence=intent.getLongExtra("sequence",-1);
            if(next==null||!next.isBinderAlive()||nextSequence<0||(next==owner&&nextSequence<=sequence))return;
            if(next!=owner){
                if(!restore())return;if(owner!=null&&ownerDeath!=null)owner.unlinkToDeath(ownerDeath,0);
                if(owner!=null) {
                    retiredOwners.add(owner);
                    while(retiredOwners.size()>64)retiredOwners.remove(retiredOwners.iterator().next());
                }
                owner=next;sequence=-1;dismissed.clear();final IBinder binding=next;
                ownerDeath=()->handler.post(()->{synchronized(lock){if(owner==binding){active=false;handler.post(() -> updateMediaMonitoring());restore();owner=null;publish();}}});
                try {owner.linkToDeath(ownerDeath,0);}catch(RemoteException gone){owner=null;return;}
            }
            hostUid=intent.getParcelableExtra("identity",PendingIntent.class).getCreatorUid();
            sequence=nextSequence;host=intent.getParcelableExtra("surface",SurfaceControl.class);
            width=intent.getIntExtra("width",0);height=intent.getIntExtra("height",0);
            active=intent.getBooleanExtra("active",false)&&width>1&&height>1;
            fill=intent.getBooleanExtra("fill",false);
            prepare();
        }
        updateMediaMonitoring();
    }
    private Object display(){Object root=field(service,"mRoot");return call(root,"getDisplayContent",0);}
    private void prepare() {
        Object display=display();if(display==null)return;
        if(!active||owner==null||!owner.isBinderAlive()||host==null||!host.isValid()
                ||!inputReady||!EntryGuard.canOpen(context)) {restore();publish();return;}
        if(source!=null&&!eligible(source.container,source.pip)&&!restore()){publish();return;}
        if(source==null){
            Source found=find(display);
            if(found!=null)try{source=found;sourceId++;dock=newDock(host);inputHandles.clear();refreshInputHandles();requestInput(display);}
            catch(Throwable failure){restore();publish();return;}
        }
        if(source!=null){
            try {
                Rect bounds=bounds(source.container,source.pip);
                if(bounds==null||bounds.isEmpty()||!source.surface.isValid())throw new IllegalStateException("video removed");
                Object tx=call(display,"getPendingTransaction");
                Point position=field(source.container,"mLastSurfacePosition") instanceof Point?new Point((Point)field(source.container,"mLastSurfacePosition")):new Point();
                if(!(field(source.container,"mLastSurfacePosition") instanceof Point))call(source.container,"getRelativePosition",position);
                if(!source.pip){WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)field(source.container,"mAttrs");
                    Object rawInsets=field(attrs,"surfaceInsets");
                    if(rawInsets instanceof Rect){Point inset=new Point();call(source.container,"transformSurfaceInsetsPosition",inset,rawInsets);position.offset(inset.x,inset.y);}}
                float scale=fill?Math.max(width/(float)bounds.width(),height/(float)bounds.height())
                        :Math.min(width/(float)bounds.width(),height/(float)bounds.height());
                XposedHelpers.callMethod(tx,"setWindowCrop",host,new Rect(0,0,width,height));
                XposedHelpers.callMethod(tx,"reparent",dock,host);XposedHelpers.callMethod(tx,"reparent",source.surface,dock);
                XposedHelpers.callMethod(tx,"setLayer",dock,1);XposedHelpers.callMethod(tx,"setLayer",source.surface,0);
                XposedHelpers.callMethod(tx,"setMatrix",dock,scale,0f,0f,scale);
                XposedHelpers.callMethod(tx,"setPosition",dock,(width-bounds.width()*scale)/2-position.x*scale,(height-bounds.height()*scale)/2-position.y*scale);
                XposedHelpers.callMethod(tx,"setWindowCrop",dock,new Rect(position.x,position.y,position.x+bounds.width(),position.y+bounds.height()));
                XposedHelpers.callMethod(tx,"show",dock);refreshInputHandles();if(!preparingFrame)call(display,"scheduleAnimation");
            }catch(Throwable failed){log("video returned to native owner: "+failed);restore();}
        }
        publish();
    }
    private Source find(Object display) {
        final Source[] found={null};
        call(display,"forAllTasks",(Consumer<Object>)task->{
            if(found[0]==null&&eligible(task,true))found[0]=candidate(task,true);
        },true);
        if(found[0]==null)call(display,"forAllWindows",(Consumer<Object>)window->{
            if(found[0]==null&&eligible(window,false))found[0]=candidate(window,false);
        },true);
        return found[0];
    }
    private Source candidate(Object container,boolean pip) {
        SurfaceControl surface=(SurfaceControl)call(container,"getSurfaceControl");
        if(surface==null||!surface.isValid()||dismissed.contains(container))return null;
        List<Object> windows=windows(container,pip);if(windows.isEmpty())return null;
        String pkg=null;
        for(Object w:windows){WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)field(w,"mAttrs");
            if(attrs==null||(attrs.flags&WindowManager.LayoutParams.FLAG_SECURE)!=0)return null;
            if(pkg==null)pkg=attrs.packageName;}
        if(!videoPackage(pkg))return null;
        return new Source(container,surface,pip,pkg);
    }
    private boolean eligible(Object container,boolean pip) {
        if(container==null)return false;
        boolean visible=Boolean.TRUE.equals(call(container,"isVisible"))||Boolean.TRUE.equals(call(container,"isVisibleRequested"));
        if(!visible){dismissed.remove(container);return false;}
        if(pip){if(!Integer.valueOf(2).equals(call(container,"getWindowingMode"))){dismissed.remove(container);return false;}}
        else {Object raw=field(container,"mAttrs");if(!(raw instanceof WindowManager.LayoutParams)
                ||((WindowManager.LayoutParams)raw).type!=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)return false;}
        if(Boolean.TRUE.equals(call(container,"inTransition")))return false;
        Object leash=call(container,"getAnimationLeash");if(leash instanceof SurfaceControl&&((SurfaceControl)leash).isValid())return false;
        Rect bounds=bounds(container,pip);return bounds!=null&&bounds.width()>1&&bounds.height()>1;
    }
    private boolean videoPackage(String pkg) {
        if(pkg==null||pkg.equals(Packages.SYSTEM_UI)||pkg.equals("android"))return false;
        for(MediaController player:players)if(pkg.equals(player.getPackageName()))return true;
        if(pkg.equals("tv.danmaku.bili")||pkg.equals("com.google.android.youtube")||pkg.equals("com.phoenix.read"))return true;
        Boolean cached=videoPackages.get(pkg);if(cached!=null)return cached;
        if(querying.add(pkg))handler.post(()->{
            boolean video=false;try{video=context.getPackageManager().getApplicationInfo(pkg,0).category==ApplicationInfo.CATEGORY_VIDEO;}catch(Exception ignored){}
            if(videoPackages.size()>=128)videoPackages.clear();
            videoPackages.put(pkg,video);querying.remove(pkg);
        });
        return false;
    }
    private static List<Object> windows(Object container,boolean pip){
        if(!pip)return Collections.singletonList(container);
        List<Object> result=new ArrayList<>();call(container,"forAllWindows",(Consumer<Object>)w->result.add(w),true);return result;
    }
    private void refreshInputHandles(){inputHandles.clear();if(source!=null)for(Object w:windows(source.container,source.pip)){
        Object h=handle(field(w,"mInputWindowHandle"));if(h!=null)inputHandles.add(h);
    }}
    private static Object handle(Object wrapper){Object raw=field(wrapper,"mHandle");return raw==null?wrapper:raw;}
    private static void suppress(Object tx,Object surface,Object handle){
        if(tx==null||surface==null||handle==null)return;
        Object raw=field(handle,"inputConfig");if(!(raw instanceof Integer))return;int original=(Integer)raw;
        try{XposedHelpers.setIntField(handle,"inputConfig",original|8);XposedHelpers.callMethod(tx,"setInputWindowInfo",surface,handle);}
        finally{XposedHelpers.setIntField(handle,"inputConfig",original);}
    }
    private boolean restore(){
        // Keep ownership until native reparenting is queued. A transient missing
        // parent must not remove the dock and destroy its still-owned video.
        Source previous=source;
        Object display=display(),tx=call(display,"getPendingTransaction");
        try {
            if(previous!=null&&previous.surface.isValid()){
                Object parent=call(previous.container,"getParentSurfaceControl");
                if(parent==null)parent=call(call(previous.container,"getParent"),"getSurfaceControl");
                if(tx==null||!(parent instanceof SurfaceControl)||!((SurfaceControl)parent).isValid())return retainPendingReturn(tx);
                XposedHelpers.callMethod(tx,"reparent",previous.surface,parent);
                XposedHelpers.callMethod(tx,"setLayer",previous.surface,integer(previous.container,"mLastLayer",previous.layer));
            }
            // WM wrappers may consider the restored Java flags unchanged. Explicitly
            // submit their native metadata so returned PiP/floating windows regain input.
            for(Map.Entry<Object,Object> entry:suppressedSurfaces.entrySet()){
                if(entry.getValue() instanceof SurfaceControl&&((SurfaceControl)entry.getValue()).isValid()){
                    if(tx==null)return retainPendingReturn(null);
                    XposedHelpers.callMethod(tx,"setInputWindowInfo",entry.getValue(),entry.getKey());
                }
            }
            if(dock!=null&&dock.isValid()){
                if(tx==null)return retainPendingReturn(null);
                XposedHelpers.callMethod(tx,"remove",dock);
            }
        }catch(Throwable failure){log("video native return pending: "+failure);return retainPendingReturn(tx);}
        handler.removeCallbacks(returnRetry);
        source=null;inputHandles.clear();suppressedSurfaces.clear();
        if(dock!=null){dock.release();dock=null;}
        if(previous!=null){requestInput(display);call(display,"scheduleAnimation");}
        return true;
    }
    private final Runnable returnRetry=this::retryReturn;
    private void retryReturn() {
        try {
            synchronized(lock) {
                if(source==null)return;
                if(restore()) {Object tx=call(display(),"getPendingTransaction");if(tx!=null)XposedHelpers.callMethod(tx,"apply");publish();}
            }
        } catch(Throwable failure) {
            log("video return retry pending: "+failure);
            handler.removeCallbacks(returnRetry);handler.postDelayed(returnRetry,1200L);
        }
    }
    private boolean retainPendingReturn(Object tx) {
        active=false;handler.post(() -> updateMediaMonitoring());
        // The server owns dock. Isolate it before the remote host can die even
        // when its original WM parent is temporarily unavailable.
        try {
            if(tx!=null && dock!=null && dock.isValid()) {XposedHelpers.callMethod(tx,"reparent",dock,null);XposedHelpers.callMethod(tx,"apply");}
        } catch(Throwable failure) {log("video return isolation pending: "+failure);}
        handler.removeCallbacks(returnRetry);handler.postDelayed(returnRetry,1200L);
        return false;
    }
    private void requestInput(Object display){Object monitor=call(display,"getInputMonitor");if(monitor==null)monitor=field(display,"mInputMonitor");
        call(monitor,"setUpdateInputWindowsNeededLw");call(monitor,"updateInputWindowsLw",true);}
    private void command(Intent intent){
        String cmd=intent.getStringExtra("command");MediaController player=null;
        synchronized(lock){
            IBinder token=intent.getExtras()==null?null:intent.getExtras().getBinder("owner");
            if(owner==null||!owner.equals(token)||source==null||intent.getLongExtra("source",-1)!=sourceId)return;
            if("release".equals(cmd)){dismissed.add(source.container);restore();publish();return;}
            if("fill".equals(cmd)||"fit".equals(cmd)){fill="fill".equals(cmd);prepare();return;}
            for(MediaController candidate:players)if(source.pkg.equals(candidate.getPackageName())){player=candidate;break;}
        }
        if(player!=null&&"toggle".equals(cmd))try{
            PlaybackState state=player.getPlaybackState();
            if(state!=null&&state.getState()==PlaybackState.STATE_PLAYING)player.getTransportControls().pause();else player.getTransportControls().play();
        }catch(Throwable failure){log("video playback command unavailable: "+failure);}
    }
    private void publish(){
        boolean embedded=source!=null;String pkg=embedded?source.pkg:"";boolean playing=false,controllable=false;
        Integer playback=playStates.get(pkg);controllable=playback!=null;playing=playback!=null&&playback==PlaybackState.STATE_PLAYING;
        String signature=System.identityHashCode(owner)+":"+sourceId+":"+embedded+":"+playing+":"+controllable;
        if(signature.equals(published))return;published=signature;
        Intent state=new Intent(STATE).setPackage(Packages.SYSTEM_UI).putExtra("embedded",embedded).putExtra("source",sourceId)
                .putExtra("package",pkg).putExtra("playing",playing).putExtra("controllable",controllable);
        Bundle identity=new Bundle();identity.putBinder("owner",owner);identity.putBinder("release",releaseToken);state.putExtra("identity",identity);
        handler.post(()->context.sendBroadcast(state,PERMISSION));
    }
    private static boolean trusted(Context context,Intent intent){
        PendingIntent proof=intent.getParcelableExtra("identity",PendingIntent.class);
        if(proof==null||!Packages.SYSTEM_UI.equals(proof.getCreatorPackage()))return false;
        try{return proof.getCreatorUid()==context.getPackageManager().getApplicationInfo(Packages.SYSTEM_UI,0).uid;}
        catch(Exception absent){return false;}
    }
    private static SurfaceControl newDock(SurfaceControl parent){
        try{Object builder=Class.forName("android.view.SurfaceControl$Builder").getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(builder,"setName","OneStepLite-video-only");XposedHelpers.callMethod(builder,"setContainerLayer");XposedHelpers.callMethod(builder,"setParent",parent);
            return (SurfaceControl)XposedHelpers.callMethod(builder,"build");
        }catch(Throwable failure){throw new IllegalStateException(failure);}
    }
    private static Rect bounds(Object container,boolean pip){Object value=call(container,pip?"getBounds":"getFrame");return value instanceof Rect?new Rect((Rect)value):null;}
    private static int integer(Object object,String name,int fallback){Object value=field(object,name);return value instanceof Number?((Number)value).intValue():fallback;}
    private static Object field(Object o,String n){try{return o==null?null:XposedHelpers.getObjectField(o,n);}catch(Throwable absent){return null;}}
    private static Object call(Object o,String n,Object...args){try{return o==null?null:XposedHelpers.callMethod(o,n,args);}catch(Throwable absent){return null;}}
    private static void log(String s){XposedBridge.log(Tags.PROBE + ": " + s);}
}
