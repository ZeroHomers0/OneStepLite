package io.github.zerohomers0.onesteplite;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Admits top tasks and restores their previous owner on failure. */
final class WorkspaceTopAdmissionController {
    private final WorkspaceHost host;
    private String navigationPackage;
    private int navigationUser = -1;
    private long navigationCacheUntil;
    private boolean navigationApp;

    WorkspaceTopAdmissionController(WorkspaceHost host) {
        this.host = host;
    }

    TopCandidate candidate(Object info, Slot source) {
        LiveTask task=new LiveTask(info);
        TopTaskState.Key key=new TopTaskState.Key(task.id,XposedHelpers.getIntField(info,"userId"),task.token);
        TopDirection directed=topPageDirection(info,key);
        int orientation=topPageOrientation(info,directed);
        boolean landscape=!FullscreenRotationPolicy.requestsPortrait(orientation)
                && (FullscreenRotationPolicy.requestsLandscape(orientation)
                || (directed!=null?directed.landscape():host.taskStates.configurationOrientationOf(info)==2));
        boolean navigation=source==null && !landscape && !host.layout.tabletLandscape
                && isNavigationApp(task.pkg,key.userId);
        boolean tryLandscape=!landscape && !navigation && !host.layout.tabletLandscape
                && FullscreenRotationPolicy.allowsEitherOrientation(orientation);
        return new TopCandidate(task,key,source,landscape,tryLandscape,navigation);
    }

    private boolean isNavigationApp(String pkg,int user) {
        if(pkg==null)return false;
        switch(pkg){
            case "com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map",
                    "com.google.android.apps.maps", "com.waze",
                    "net.osmand", "net.osmand.plus", "app.organicmaps" -> {return true;}
        }
        long now=SystemClock.uptimeMillis();
        if(pkg.equals(navigationPackage) && navigationUser==user && now<navigationCacheUntil)return navigationApp;
        try {
            android.content.pm.PackageManager packages=host.ctx.getPackageManager();
            Intent maps=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS).setPackage(pkg);
            Intent directions=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("google.navigation:q=0,0")).setPackage(pkg);
            navigationApp=packages.resolveActivity(maps,0)!=null
                    || packages.resolveActivity(directions,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)!=null;
        }catch(RuntimeException unavailable){return false;}
        navigationPackage=pkg;
        navigationUser=user;
        navigationCacheUntil=now+60_000L;
        return navigationApp;
    }

    private TopDirection topPageDirection(Object info, TopTaskState.Key key) {
        TopDirection direction=host.topDirections.get(key);
        if(direction==null || direction.page()==null || direction.page().isEmpty()
                || direction.mode()!=host.taskStates.windowModeOf(info))return null;
        try {
            Object page=XposedHelpers.getObjectField(info,"topActivity");
            return page instanceof ComponentName
                    && direction.page().equals(((ComponentName)page).flattenToString())
                    ? direction : null;
        } catch(Throwable unavailable) {return null;}
    }

    private int topPageOrientation(Object info, TopDirection direction) {
        if(direction!=null)return direction.request();
        int requested=host.taskStates.requestedOrientationOf(info);
        if(requested!=-1)return requested;
        try {
            Object activity=XposedHelpers.getObjectField(info,"topActivityInfo");
            return XposedHelpers.getIntField(activity,"screenOrientation");
        } catch(Throwable unavailable) {return Integer.MIN_VALUE;}
    }

    TopCandidate currentTopCandidate() {
        try {
            LiveTask task=host.slotRegistry.findMainTask();
            Object info=task==null?null:host.slotRegistry.rootInfo(task);
            if(!host.slotRegistry.ordinaryRoot(info,1) || !host.slotRegistry.mainTaskVisible(info))return null;
            // A docked video is already presented by the video dock; adopting it would
            // move the same surface twice and leave the dock pointing at a dead parent.
            if(VideoDock.owns(task.id)) {WorkspaceHost.log("upper candidate skipped: docked video task="+task.id);return null;}
            return candidate(info,null);
        } catch(Throwable unavailable) {WorkspaceHost.log("upper current candidate unavailable: "+unavailable);return null;}
    }

    Boolean mainTaskOccupiedBeside(int upperTaskId) {
        try {
            LiveTask mainTask=host.slotRegistry.findMainTask();
            return mainTask!=null && mainTask.id!=upperTaskId;
        } catch(Throwable unavailable) {
            WorkspaceHost.log("upper return cannot confirm main task: "+unavailable);
            return null;
        }
    }

    void claimTopTask(TopCandidate selected, boolean manual) {
        if(!manual || !host.topPresentationHost.topReady() || !host.topFace || host.slots[WorkspaceHost.SLOT_TOP].occupied) {host.topPresentationHost.cancelTopTaskTransition();return;}
        try {
            Object fresh=host.slotRegistry.rootInfo(selected.task);
            if(fresh==null || XposedHelpers.getIntField(fresh,"userId")!=selected.key.userId) {host.topPresentationHost.cancelTopTaskTransition();return;}
            if(selected.source==null && (!host.slotRegistry.ordinaryRoot(fresh,1) || !host.slotRegistry.mainTaskVisible(fresh))) {host.topPresentationHost.cancelTopTaskTransition();return;}
            if(selected.source!=null && (!host.slotRegistry.ordinaryRoot(fresh,6) || !host.slotRegistry.controllerOwns(selected.source,selected.task.id,selected.task.token))) {host.topPresentationHost.cancelTopTaskTransition();return;}
            TopCandidate checked=candidate(fresh,selected.source);
            if(selected.source==null && !host.layout.tabletLandscape
                    && !checked.landscape && !checked.tryLandscape && !checked.navigation) {
                host.topPresentationHost.cancelTopTaskTransition();
                return;
            }
            TopTaskState.Ticket ticket=host.topTaskState.request(checked.key,host.sceneEpoch,true,manual);
            if(ticket==null) {host.topPresentationHost.cancelTopTaskTransition();return;}
            if(selected.source==null)adoptTopTask(checked,ticket,true);
            else host.returnToSystem(selected.source,false,()->{
                if(!host.topTaskState.current(ticket,host.sceneEpoch)) {host.topTaskState.cancel(ticket);host.resumes.finishPendingSuspend();return;}
                host.slotLifecycle.clearSlot(selected.source);
                adoptTopTask(checked,ticket,false);
            },error->{host.topTaskState.cancel(ticket);WorkspaceHost.log("upper task kept: "+error);host.resumes.finishPendingSuspend();});
        } catch(Throwable error) {host.topPresentationHost.cancelTopTaskTransition();WorkspaceHost.log("upper admission stopped: "+error);}
    }

    private void adoptTopTask(TopCandidate selected, TopTaskState.Ticket ticket, boolean fromMain) {
        if(!host.topTaskState.current(ticket,host.sceneEpoch)) {host.topPresentationHost.cancelTopTaskTransition();return;}
        prepareTopViewport(selected,()->adoptTopTaskPrepared(selected,ticket,fromMain),
                error->topAdoptionFailed(selected,ticket,error));
    }

    private void prepareTopViewport(TopCandidate selected,Runnable ready,Consumer<String> failure) {
        final java.util.concurrent.atomic.AtomicBoolean completed=new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable timeout=()->{
            if(completed.compareAndSet(false,true))failure.accept("窗口信息准备超时");
        };
        try {
            android.os.Bundle row=new android.os.Bundle();
            row.putBinder("owner",host.desktopSceneOwner);
            row.putBinder("task",(android.os.IBinder)XposedHelpers.callMethod(selected.task.token,"asBinder"));
            row.putInt("taskId",selected.key.taskId);row.putInt("userId",selected.key.userId);
            row.putParcelable("bounds",host.slotRegistry.logicalBounds(host.slots[WorkspaceHost.SLOT_TOP],!selected.landscape && !selected.tryLandscape));
            host.main.postDelayed(timeout,1500L);
            host.ctx.sendOrderedBroadcast(new Intent(DesktopLaunchBridge.PREPARE_TOP).setPackage("android")
                    .putExtra("state",row),android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    new BroadcastReceiver() {
                        @Override public void onReceive(Context c,Intent intent) {
                            if(!completed.compareAndSet(false,true))return;
                            host.main.removeCallbacks(timeout);
                            if(getResultCode()==-1)ready.run();else failure.accept("窗口信息准备被拒绝");
                        }
                    },host.main,0,null,null);
        } catch(Throwable error) {
            host.main.removeCallbacks(timeout);
            if(completed.compareAndSet(false,true))failure.accept("窗口信息准备失败："+error);
        }
    }

    private void adoptTopTaskPrepared(TopCandidate selected, TopTaskState.Ticket ticket, boolean fromMain) {
        if(!host.topTaskState.current(ticket,host.sceneEpoch)) {host.topPresentationHost.cancelTopTaskTransition();return;}
        boolean previousPortrait=host.topPortrait;
        host.topPortrait=!selected.landscape && !selected.tryLandscape;
        host.adoptExisting(host.slots[WorkspaceHost.SLOT_TOP],selected.task,fromMain,()->{
            if(!host.topTaskState.commit(ticket,host.sceneEpoch)) {host.topPresentationHost.releaseTopTask(false,false,null);return;}
            host.topOriginalSlot=selected.source;host.topFromMain=fromMain;
            host.topPresentationHost.updateTopTaskGeometry();host.scales.applyFullscreenBounds(host.slots[WorkspaceHost.SLOT_TOP]);host.scales.applySlotScale(host.slots[WorkspaceHost.SLOT_TOP]);
            // The strip's surface is shared with the main window during a cross-fade,
            // so its alpha and crop must be re-staged for the new owner explicitly.
            host.cellSurfaces.updateCellSurfaceVisibility(host.slots[WorkspaceHost.SLOT_TOP]);
            host.publishDesktopSideTasks();
            host.root.requestLayout();
            WorkspaceHost.log("upper task admitted task="+selected.key.taskId+" user="+selected.key.userId+" original="+(selected.source==null?"main":selected.source.index));
            restoreTabletCoveredMain(selected.key);
            host.topPresentationHost.revealTopTaskTransition();
            if(selected.tryLandscape)verifyTopLandscape(selected);
            host.topPresentationHost.preferMainAfterTopChange();
            host.resumes.finishPendingSuspend();
        },error->{
            if(host.topTaskState.current(ticket,host.sceneEpoch))host.topPortrait=previousPortrait;
            topAdoptionFailed(selected,ticket,error);
        });
    }

    private void verifyTopLandscape(TopCandidate selected) {
        final Slot upper=host.slots[WorkspaceHost.SLOT_TOP];
        final long binding=upper.taskEpoch, epoch=host.sceneEpoch;
        host.main.postDelayed(()->{
            if(epoch!=host.sceneEpoch || upper.taskEpoch!=binding
                    || !selected.key.equals(host.topTaskState.owner()) || !host.topPresentationHost.topReady() || host.suspendRequested
                    || upper.promoting || !upper.occupied)return;
            try {
                Object info=host.slotRegistry.rootInfo(selected.task);
                if(info!=null) {
                    TopDirection direction=topPageDirection(info,selected.key);
                    int orientation=direction!=null && direction.viewportOrientation()!=0
                            ? direction.viewportOrientation() : host.taskStates.configurationOrientationOf(info);
                    if(orientation==2 && !FullscreenRotationPolicy.requestsPortrait(
                            topPageOrientation(info,direction)))return;
                }
            } catch(Throwable unavailable) {WorkspaceHost.log("upper landscape verification unavailable: "+unavailable);}
            host.topPresentationHost.releaseTopTask(true,true,()->android.widget.Toast.makeText(host.ctx,
                    "当前页面无法在顶栏横屏，已返回主窗",android.widget.Toast.LENGTH_SHORT).show());
        },900L);
    }

    private void topAdoptionFailed(TopCandidate selected,TopTaskState.Ticket ticket,String error) {
            host.topTaskState.cancel(ticket);
            host.topPresentationHost.cancelTopTaskTransition();
            WorkspaceHost.log("upper admission failed; returning native task");
            WorkspaceHost.log("upper adoption failed: "+error);
            Slot origin=selected.source;
            if(origin!=null && !origin.occupied && host.topPresentationHost.topReady() && !host.suspendRequested)
                host.adoptExisting(origin,selected.task,()->{host.resumes.finishPendingSuspend();},failure->{WorkspaceHost.log("upper original slot restore: "+failure);host.resumes.finishPendingSuspend();});
            else host.resumes.finishPendingSuspend();
    }

    private void restoreTabletCoveredMain(TopTaskState.Key collected) {
        final TopCandidate covered=host.tabletCoveredMain;
        boolean returning=collected.equals(host.tabletReturnedTop);
        host.tabletCoveredMain=null;host.tabletReturnedTop=null;
        if(!host.layout.tabletLandscape || !returning || covered==null)return;
        final long epoch=host.sceneEpoch;
        host.executeOnShell(()->{
            if(host.scene!=WorkspaceHost.ACTIVE || host.sceneEpoch!=epoch || host.suspendRequested
                    || !collected.equals(host.topTaskState.owner()))return;
            try {
                Object fresh=host.slotRegistry.rootInfo(covered.task);
                if(!covered.task.matches(fresh) || !host.slotRegistry.ordinaryRoot(fresh,1)
                        || XposedHelpers.getIntField(fresh,"userId")!=covered.key.userId
                        || host.slotRegistry.isSideToken(covered.task.token))return;
                Object wct=Class.forName("android.window.WindowContainerTransaction")
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(wct,"setHidden",covered.task.token,false);
                XposedHelpers.callMethod(wct,"reorder",covered.task.token,true);
                if(WorkspaceNativeHooks.applyWct("tablet-top-covered-main",wct))
                    WorkspaceHost.log("tablet covered main restored task="+covered.key.taskId);
            } catch(Throwable unavailable) {WorkspaceHost.log("tablet covered main no longer available: "+unavailable);}
        });
    }
}
