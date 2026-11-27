package io.github.zerohomers0.onesteplite;

import static io.github.zerohomers0.onesteplite.ZoomRuntime.*;

import static io.github.zerohomers0.onesteplite.ZoomRuntime.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import static io.github.zerohomers0.onesteplite.HostEntry.*;
import de.robv.android.xposed.XposedHelpers;

final class ZoomBridgeReceiver extends BroadcastReceiver {
    private final Context context;
    private final Object service;
    ZoomBridgeReceiver(Context context,Object service){this.context=context;this.service=service;}
    @Override public void onReceive(Context ignored,Intent intent){
        if(intent==null)return;
        String action=intent.getAction();
        if(Intent.ACTION_SCREEN_OFF.equals(action)){onZoomScreenState(false);return;}
        if(Intent.ACTION_SCREEN_ON.equals(action)){onZoomScreenState(true);return;}
        if(RetainedWindow.ACTION.equals(action))handleRetained(intent);
        else if(UnlockHandoff.RELEASE.equals(action))handleUnlockRelease(intent);
        else if(BottomReplayInput.ACTION.equals(action))BottomReplayInput.setMode(intent.getIntExtra("mode",0));
        else if(WorkspaceBackdrop.ACTION.equals(action))handleBackdrop(intent);
        else if(SwapOverlayBridge.ACTION.equals(action))handleSwapOverlay(intent);
        else if(LiveSwapZoomProtocol.APPLY.equals(action))receiveLiveZoom(context,intent);
        else if(InputTrace.ACTION.equals(action))handleInputTrace(intent);
        else if(DesktopLaunchBridge.PREPARE_TOP.equals(action))handleDesktopPrepare(intent);
        else if(DesktopLaunchBridge.ACTION.equals(action))handleDesktopUpdate(intent);
        else if(Actions.ZOOM_LEASE.equals(action))handleLease(intent);
        else if(Actions.TRANSFER_TOUCH.equals(action))
            SideTouchTransfer.transfer(intent,()->sZoomTarget,()->sZoomScale,HostEntry::log);
        else handleZoom(intent);
    }
    private void handleLease(Intent intent){
        Bundle lease=intent.getBundleExtra("lease");
        IBinder token=lease==null?null:lease.getBinder("host");
        setResultCode(acceptZoomLease(token)?android.app.Activity.RESULT_OK:android.app.Activity.RESULT_CANCELED);
    }
    private void handleRetained(Intent intent){
        ZoomTarget target=sZoomTarget;android.os.Bundle reply=null;
        if(target!=null)synchronized(target.globalLock){
            if(target==sZoomTarget && !EntryGuard.canOpen(context))reply=sRetainedWindow.retain(target.display,intent);
        }
        setResultExtras(reply);setResultCode(reply==null?0:-1);
    }
    private void handleUnlockRelease(Intent intent){
        ZoomTarget target=sZoomTarget;
        long generation=intent.getLongExtra(LiveSwapZoomProtocol.GENERATION,0);
        boolean completed=intent.getBooleanExtra(UnlockHandoff.COMPLETED,true);
        boolean workspace=intent.getBooleanExtra(UnlockHandoff.WORKSPACE,false);
        float retainedScale=Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.SCALE,0));
        float retainedX=Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.X,0));
        float retainedY=Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.Y,0));
        if(workspace && (!Float.isFinite(retainedScale) || retainedScale<=0 || retainedScale>=1
                || !Float.isFinite(retainedX) || !Float.isFinite(retainedY)))return;
        if(target==null)return;
        target.handler.post(()->{synchronized(target.globalLock){
            if(target!=sZoomTarget || generation==0 || generation!=sUnlockPhysicalShadeGeneration)return;
            sUnlockPhysicalShadeGeneration=0;
            try{
                Object tx=XposedHelpers.callMethod(target.display,"getPendingTransaction");
                boolean authenticated=!sKeyguardZoom.sleeping()&&EntryGuard.canOpen(context);
                sRetainedWindow.finishAuthenticatedRelease(target.display,tx,authenticated,completed,workspace,()->{
                    target.owner.applyDisplayZoomLocked(target,1f,0f,0f,null,true);
                    reapplyFinalMagnification(target.display,tx);
                },()->{
                    String retained=retainedScale+" "+retainedY+" "+retainedX;
                    if(!sZoomChannels.supersedes(sLiveZoomIssued,retained))
                        target.owner.applyDisplayZoomLocked(target,retainedScale,retainedY,retainedX);
                    reapplyFinalMagnification(target.display,tx);
                });
                reapplyFinalMagnification(target.display,tx);
                XposedHelpers.callMethod(tx,"apply");
            }catch(Throwable error){log("unlock shade release unavailable: "+error);}
        }});
    }
    private void handleBackdrop(Intent intent){
        ZoomTarget target=sZoomTarget;
        if(target==null || target.display!=sDisplayContent)return;
        try{synchronized(target.globalLock){
            android.os.Bundle reply=WorkspaceBackdrop.capture(target.display,intent);
            if(reply!=null){setResultExtras(reply);setResultCode(-1);}
        }}catch(Throwable error){log("workspace wallpaper parent rejected: "+error);}
    }
    private void handleSwapOverlay(Intent intent){
        try{
            if(!sZoomed || sZoomTarget==null || sDisplayContent==null)return;
            synchronized(sZoomTarget.globalLock){
                setResultExtras(SwapOverlayBridge.capture(sDisplayContent,intent));setResultCode(-1);
            }
        }catch(Throwable unavailable){log("live toolbox native capture refused: "+unavailable);}
    }
    private void handleInputTrace(Intent intent){
        if(intent.getBooleanExtra("stopInputTrace",false)){InputTrace.stop();return;}
        ZoomTarget target=sZoomTarget;if(target==null)return;
        target.handler.post(()->{
            if(target!=sZoomTarget)return;
            InputTrace.arm(context,true,target.display.getClass().getClassLoader());
            synchronized(target.globalLock){InputTrace.captureDisplay(target.display);}
        });
    }
    private void handleDesktopPrepare(Intent intent){
        boolean prepared=false;
        if(sZoomed && sFullscreenRotationActive && sDisplayContent!=null)
            synchronized(XposedHelpers.getObjectField(service,"mGlobalLock")){
                prepared=DesktopLaunchBridge.prepareTop(service,intent);
            }
        setResultCode(prepared?-1:0);
    }
    private void handleDesktopUpdate(Intent intent){
        if(!DesktopLaunchBridge.update(intent))return;
        Recents.registryChanged(service);DesktopLaunchBridge.refreshTopInput(service);
        try{
            if(sZoomed)synchronized(XposedHelpers.getObjectField(service,"mGlobalLock")){
                TopInsets.refresh(sDisplayContent,DesktopLaunchBridge::ownsTopTask);
                ZoomTarget target=sZoomTarget;
                if(sFullscreenRotationActive && target!=null && target.display==sDisplayContent)
                    target.owner.refreshTaskRotationLocked(sDisplayContent);
                XposedHelpers.callMethod(service,"updateFocusedWindowLocked",0,true);
            }
        }catch(Throwable unavailable){log("workspace lease focus refresh skipped: "+unavailable);}
    }
    private void handleZoom(Intent intent){
        try{synchronized(sZoomChannels){
            long now=System.currentTimeMillis();String encoded=intent.getStringExtra("request");
            if(encoded!=null)sZoomChannels.offerEncoded(encoded,now);
            else sZoomChannels.offer(intent.getFloatExtra("scale",1f)+" "
                    +intent.getFloatExtra("offsetY",0f)+" "+intent.getFloatExtra("offsetX",0f),now,now);
            dispatchCurrentZoom();
        }}catch(Throwable error){log("zoom broadcast submit failed: "+error);}
    }
}
