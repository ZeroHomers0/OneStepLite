package io.github.zerohomers0.onesteplite;

import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import de.robv.android.xposed.XposedHelpers;

/** Coordinates top-task controls, surface transitions, and presentation geometry. */
final class WorkspaceTopPresentationController {
    private final WorkspaceHost host;
    private final WideVideoPolicy wideVideoPolicy = new WideVideoPolicy();
    private long wideVideoSequence, wideVideoEpoch=-1;

    WorkspaceTopPresentationController(WorkspaceHost host) {
        this.host = host;
    }

    void createTopTaskControls() {
        Slot upper=host.slots[WorkspaceHost.SLOT_TOP];upper.hint=new View(host.ctx);
        updateTopTaskGeometry();
        // The upper TaskView was added after the controls; its empty surface hole would
        // otherwise blank them until the controls are explicitly raised.
        if(!host.topFace && host.topBar!=null)host.topBar.bringToFront();
    }

    void configureTabletTools() {
        if (host.root==null || host.topBar==null) return;
        if (host.tabletTools!=null) {host.root.removeView(host.tabletTools);host.tabletTools=null;}
        if (!host.layout.tabletLandscape) {
            host.tabletToolsOpen=false;
            host.topBar.clearTabletHeader();
            host.topBar.setVisibility(View.VISIBLE);
            return;
        }
        host.topFace=true;
        host.tabletTools=host.topBar.createTabletHeader(host.layout.toolHeight,
                ()->setTabletToolsOpen(!host.tabletToolsOpen));
        host.topBar.setTabletToolsExpanded(host.tabletToolsOpen,false);
        host.tabletTools.setAlpha(host.workspaceAlpha);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,host.layout.toolHeight);
        p.topMargin=0;
        host.root.addView(host.tabletTools,p);
        updateTopTaskGeometry();
    }

    void setTabletToolsOpen(boolean open) {
        if (!host.layout.tabletLandscape || host.topBar==null || host.tabletTools==null) return;
        host.tabletToolsOpen=open;
        if (!open) {
            host.topBar.closeContentUi();

        }
        host.topBar.setTabletToolsExpanded(open,true);
        if (open) {host.topLiveClip.bringToFront();host.topBar.bringToFront();}
        host.tabletTools.bringToFront();
        host.root.requestLayout();
    }

    void setTaskCellsCovered(boolean covered) {
        if (host.taskCellsCovered == covered) return;
        host.taskCellsCovered = covered;
        for (Slot slot : host.slots) if (slot.index > WorkspaceHost.SLOT_MAIN && slot.index != WorkspaceHost.SLOT_TOP) host.slotLifecycle.updateHint(slot);
        if (host.root != null) { host.root.requestLayout(); host.root.invalidate(); }
    }

    void sendMainToTop() {
        if(!topReady() || host.topTransition!=null)return;
        if(!host.chrome.mainCanFillTop()) {
            // Portrait apps cannot fill the strip; say so instead of doing nothing.
            android.widget.Toast.makeText(host.ctx,"当前应用是竖屏，无法放到顶栏",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if(host.topFace) {captureMainIntoTop(); return;}
        // The strip keeps its
        // clock and rail while the landscape task fades in on top of them.
        enterTopFaceForFade();
        captureMainIntoTop();
    }

    void enterTopFaceForFade() {
        host.topFace=true;
        if(host.topPresentation!=null)host.topPresentation.releaseVideo();
        updateTopTaskGeometry();
        // updateTopTaskGeometry renders the settled task face, which hides the bar;
        // the fade needs it visible underneath the incoming task.
        if(host.topBar!=null) {
            host.topBar.setPivotY(0f);
            host.topBar.setScaleY(1f);
            host.topBar.setTranslationY(0f);
            host.topBar.setVisibility(View.VISIBLE);
        }
        host.root.requestLayout();
    }

    void captureMainIntoTop() {
        if(!topReady() || !host.topFace || host.slots[WorkspaceHost.SLOT_TOP].occupied || host.topTaskState.busy() || host.topTransition!=null)return;
        TopCandidate current=host.topAdmission.currentTopCandidate();
        if(current==null) {
            android.widget.Toast.makeText(host.ctx,"先在主屏打开应用，再点此格收进来",android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if(!startTopCollectionTransition(current,()->host.topAdmission.claimTopTask(current,true)))host.topAdmission.claimTopTask(current,true);
    }

    boolean startTopCollectionTransition(TopCandidate selected,Runnable transfer) {
        Slot upper=host.slots[WorkspaceHost.SLOT_TOP];
        if(host.root==null || host.topTransition!=null || upper.view==null || upper.controller==null)return false;
        try {
            Object organizer=XposedHelpers.getObjectField(upper.controller,"mTaskOrganizer");
            Object lock=XposedHelpers.getObjectField(organizer,"mLock");
            Object source;
            synchronized(lock) {
                Object appeared=((android.util.SparseArray<?>)XposedHelpers.getObjectField(organizer,"mTasks")).get(selected.task.id);
                if(appeared==null)return false;
                source=XposedHelpers.callMethod(appeared,"getLeash");
                if(!topCollectionSourceCurrent(organizer,selected,source))return false;
            }
            boolean started=host.topPresentationHost.startTopTaskTransition(true,false,transfer,source,
                    ()->topCollectionSourceCurrent(organizer,selected,source),upper.view);
            if(started) {
                TopTaskTransition prepared=host.topTransition;
                // The fade keeps the main surface exactly where the workspace put it,
                // so a swapped main side needs no special case and nothing shifts.
                // The main slot's surface rests at (0,0) with matrix=mainScale;
                // its view carries the offset, so the fade must not add one.
                prepared.setNativeRestore(()->setTopCollectionTranslucent(selected,false));
                if(!host.executeOnShell(()->{
                    try {
                        if(host.topTransition!=prepared || !topCollectionSourceCurrent(organizer,selected,source))
                            throw new IllegalStateException("collection source changed before background sync");
                        Object wct=topCollectionTransaction(selected,true);
                        if(wct==null)throw new IllegalStateException("collection source no longer fullscreen");
                        SyncPreparation.submit(upper.controller,wct,action->host.main.post(action),()->{
                            if(host.topTransition==prepared) {
                                if(!prepared.collectionReady())setTopCollectionTranslucent(selected,false);
                            } else if(host.topTransition==null)setTopCollectionTranslucent(selected,false);
                        },error->{WorkspaceHost.log("upper background sync failed: "+error);prepared.close();});
                    } catch(Throwable error) {
                        WorkspaceHost.log("upper background sync unavailable: "+error);host.main.post(prepared::close);
                    }
                }))prepared.close();
            }
            return started;
        } catch(Throwable error) {
            WorkspaceHost.log("upper live collection unavailable: "+error);return false;
        }
    }

    boolean setTopCollectionTranslucent(TopCandidate selected,boolean translucent) {
        try {
            Object wct=topCollectionTransaction(selected,translucent);
            return wct!=null && WorkspaceNativeHooks.applyWct("upper-live-collect-opacity-"+translucent,wct);
        } catch(Throwable error) {
            WorkspaceHost.log("upper live collection opacity: "+error);return false;
        }
    }

    Object topCollectionTransaction(TopCandidate selected,boolean translucent) throws Exception {
        Object info=host.slotRegistry.rootInfo(selected.task);
        if(!selected.task.matches(info) || !host.slotRegistry.ordinaryRoot(info,1)
                || XposedHelpers.getIntField(info,"userId")!=selected.key.userId)return null;
        Object wct=Class.forName("android.window.WindowContainerTransaction")
                .getDeclaredConstructor().newInstance();
        if(translucent) {
            XposedHelpers.callMethod(wct,"reorder",host.slotRegistry.homeForCollection(selected.task),true);
            XposedHelpers.callMethod(wct,"reorder",selected.task.token,true);
        }
        XposedHelpers.callMethod(wct,"setForceTranslucent",selected.task.token,translucent);
        return wct;
    }

    boolean topCollectionSourceCurrent(Object organizer,TopCandidate selected,Object surface) {
        try {
            synchronized(XposedHelpers.getObjectField(organizer,"mLock")) {
                Object appeared=((android.util.SparseArray<?>)XposedHelpers.getObjectField(organizer,"mTasks")).get(selected.task.id);
                if(appeared==null || XposedHelpers.callMethod(appeared,"getLeash")!=surface)return false;
                Object info=XposedHelpers.callMethod(appeared,"getTaskInfo");
                return selected.task.matches(info) && XposedHelpers.getIntField(info,"userId")==selected.key.userId
                        && host.slotRegistry.ordinaryRoot(info,1) && host.slotRegistry.mainTaskVisible(info);
            }
        } catch(Throwable unavailable) {return false;}
    }

    void revealTopTaskTransition() {
        final TopTaskTransition bridge=host.topTransition;
        final Slot upper=host.slots[WorkspaceHost.SLOT_TOP];
        if(bridge==null)return;
        // Return has already cleared TOP and installed the fullscreen surface on
        // this bridge. The incoming-TOP draw gate must not stall that reveal.
        if(!upper.occupied) {bridge.reveal();return;}
        if(upper.promoting)return;
        final Object token=upper.taskToken;
        final long binding=upper.taskEpoch,epoch=host.sceneEpoch;
        TopTaskFrame.Target target=new TopTaskFrame.Target(upper.controller,host.transitions,token,upper.taskId,
                host.slotRegistry.logicalBounds(upper),upper.rect[2]-upper.rect[0],upper.rect[3]-upper.rect[1]);
        TopTaskFrame.await(host.main,upper.view,command->{
            if(!host.executeOnShell(command))throw new IllegalStateException("Shell queue unavailable");
        },target,()->host.topTransition==bridge && host.sceneEpoch==epoch && host.scene==WorkspaceHost.ACTIVE && host.screenOn
                && !host.keyguardLocked && !host.suspendRequested && upper.occupied && upper.taskEpoch==binding
                && token.equals(upper.taskToken) && host.slotRegistry.controllerOwns(upper,target.taskId,token),()->{
                    host.scales.reapplyScale(upper);
                    bridge.setLiveSurface(WorkspaceNativeHooks.getSurfaceField(upper.controller,"mTaskLeash"));
                    bridge.reveal();
                },error->{
                    WorkspaceHost.log("upper frame admission failed: "+error);
                    bridge.abortAdmission();host.topPresentationHost.releaseTopTaskNow(true,false,null);
                });
    }

    void cancelTopTaskTransition() {
        if(host.topTransition!=null)host.topTransition.close();
    }

    void updateTopTaskGeometry() {
        if(host.layout==null || host.slots[WorkspaceHost.SLOT_TOP].view==null)return;
        Slot upper=host.slots[WorkspaceHost.SLOT_TOP];
        FrameLayout taskParent=host.layout.tabletLandscape?host.root:host.topLiveClip;
        reparentTopView(upper.view,taskParent);
        upper.rect=host.slotRegistry.slotBounds(upper);host.sideRows.applyRect(upper);
        if(host.layout.tabletLandscape) {
            host.topFace=true;
            host.topLiveClip.setLayoutParams(new FrameLayout.LayoutParams(-1,host.layout.topStrip));
            host.topLiveClip.setClipBounds(new Rect(0,0,host.screenW,host.layout.topStrip));
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(
                    upper.rect[2]-upper.rect[0],upper.rect[3]-upper.rect[1]);
            p.leftMargin=upper.rect[0];p.topMargin=upper.rect[1];
            upper.view.setScaleX(1);upper.view.setScaleY(1);
            upper.view.setClipBounds(new Rect(0,0,p.width,p.height));
            host.cellSurfaces.updateCellSurfaceVisibility(upper);
            return;
        }
        host.topLiveClip.setLayoutParams(new FrameLayout.LayoutParams(-1,host.layout.topStrip));
        host.topLiveClip.setClipBounds(new Rect(0,0,host.screenW,host.layout.topStrip));
    }

    void reparentTopView(View view,FrameLayout parent) {
        if(view==null || view.getParent()==parent)return;
        if(view.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup)view.getParent()).removeView(view);
        parent.addView(view,view.getLayoutParams());
    }

    boolean topReady() {
        return host.root!=null && host.scene==WorkspaceHost.ACTIVE && host.screenOn && !host.keyguardLocked && !host.transferBusy
                && host.promotion==null && host.cells.motion()==null && !host.workspaceEntering;
    }

    void resetTopForSuspend() {
        host.tabletCoveredMain=null;host.tabletReturnedTop=null;
        cancelTopTaskTransition();
        host.topTaskState.sceneChanged();
        host.topFace=false;
        host.topPresentationHost.releaseTopExitWindow();
        host.tabletToolsOpen=false;
        if(host.topBar!=null) {
            if(host.layout.tabletLandscape) host.topBar.setTabletToolsExpanded(false,false);
            else {host.topBar.setVisibility(View.VISIBLE);host.topBar.bringToFront();}
        }
    }

    void settleTopFace(boolean taskFace) {
        if(host.root==null || host.scene!=WorkspaceHost.ACTIVE || !host.screenOn || host.keyguardLocked)return;
        if(host.layout.tabletLandscape) {
            host.topFace=true;
            // The cross-fade owns its visibility until its current animation settles.
            updateTopTaskGeometry();host.slotLifecycle.updateHint(host.slots[WorkspaceHost.SLOT_TOP]);host.root.requestLayout();return;
        }
        host.topFace=taskFace;
        host.topBar.setVisibility(View.VISIBLE);
        // The empty SurfaceView can still clear its old hole while Shell finishes returning.
        // Paint the restored controls after it, including the now full-width adaptive hole.
        if(!taskFace)host.topBar.bringToFront();
        host.topPresentationHost.updateTopExitWindow();
        if(!taskFace) {

            Slot upper=host.slots[WorkspaceHost.SLOT_TOP];
            if(upper.occupied) {
                Boolean mainOccupied=host.topAdmission.mainTaskOccupiedBeside(upper.taskId);
                boolean occupiedOrUnknown=mainOccupied==null || mainOccupied;
                if(TopTaskReturnPolicy.keepUpperTask(occupiedOrUnknown)) {
                    WorkspaceHost.log("upper task retained in landscape TaskView while main is occupied or unknown task="
                            +upper.taskId);
                } else if(TopTaskReturnPolicy.liveCenterReveal(false,host.topFromMain,host.topOriginalSlot==null)) {
                    if(!host.topPresentationHost.startLiveMainRevealTransition(
                            ()->host.topPresentationHost.releaseTopTask(false,true,null,true)))
                        host.topPresentationHost.releaseTopTask(false,true,null);
                } else host.topPresentationHost.releaseTopTask(false,true,null);
            }
        }
        host.slotLifecycle.updateHint(host.slots[WorkspaceHost.SLOT_TOP]); host.root.requestLayout();
    }

    void fadeTopTaskOut(Runnable after) {
        final Slot upper=host.slots[WorkspaceHost.SLOT_TOP];
        final Object surface=WorkspaceNativeHooks.getSurfaceField(upper.controller,"mSurfaceControl");
        final TopTaskTransition bridge=host.topTransition;
        final long binding=upper.taskEpoch;
        final Object token=upper.taskToken;
        if(bridge!=null)bridge.followOpacity(host.topPresentation==null?null:host.topPresentation.controlsView());
        if(host.topBar!=null) {
            host.topBar.setPivotY(0f);
            host.topBar.setScaleY(1f);
            host.topBar.setTranslationY(0f);
            host.topBar.setVisibility(View.VISIBLE);
            host.topBar.bringToFront();
        }
        if(bridge==null || surface==null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface,"isValid"))) {
            after.run();
            return;
        }
        bridge.fadeOut(surface,()->upper.taskEpoch==binding && java.util.Objects.equals(upper.taskToken,token),after);
    }
    boolean startLiveMainRevealTransition(Runnable transfer) {
        return startTopTaskTransition(false,true,transfer);
    }

    boolean startTopTaskTransition(boolean intoTop, boolean liveMainReveal, Runnable transfer) {
        return startTopTaskTransition(intoTop,liveMainReveal,transfer,null,null,null);
    }

    boolean startTopTaskTransition(boolean intoTop, boolean liveMainReveal, Runnable transfer,
            Object source,java.util.function.BooleanSupplier sourceCurrent,View destination) {
        if(host.root==null || host.topTransition!=null)return false;
        if(intoTop ? source==null || sourceCurrent==null || destination==null : !liveMainReveal)return false;
        final long epoch=host.sceneEpoch;
        final FrameLayout drawingRoot=host.root;
        try {
            host.topTransition=new TopTaskTransition(drawingRoot,host.screenW,host.screenH,
                    ()->host.root==drawingRoot && host.sceneEpoch==epoch && host.scene==WorkspaceHost.ACTIVE
                            && host.screenOn && !host.keyguardLocked && !host.suspendRequested,
                    transfer,closed->{
                        if(host.topTransition==closed)host.topTransition=null;
                        if(host.scene==WorkspaceHost.ACTIVE && !host.slots[WorkspaceHost.SLOT_TOP].occupied && !host.topTaskState.busy())settleTopFace(false);
                        host.chrome.updateTopBarState();
                        host.resumes.finishPendingSuspend();
                    },WorkspaceHost::log);
            if(intoTop) {
                host.topTransition.collect(source,sourceCurrent,destination);
                host.topTransition.holdDestination(WorkspaceNativeHooks.getSurfaceField(host.slots[WorkspaceHost.SLOT_TOP].controller,"mSurfaceControl"));
            }
            host.topTransition.start();
            return true;
        } catch(Throwable error) {
            host.topPresentationHost.cancelTopTaskTransition();
            WorkspaceHost.log("upper center transition unavailable; keep native handoff: "+error);
            return false;
        }
    }

    void releaseTopTask(boolean front, boolean dismissed, Runnable after) {
        releaseTopTask(front,dismissed,after,false);
    }

    void releaseTopTask(boolean front, boolean dismissed, Runnable after,
            boolean liveMainReveal) {
        if (front && host.topTransition != null) return;
        final Slot upper=host.slots[WorkspaceHost.SLOT_TOP];
        final long binding=upper.taskEpoch;
        final int taskId=upper.taskId;
        final Object token=upper.taskToken;
        java.util.function.BooleanSupplier stillBound = () -> upper.taskEpoch == binding
                && upper.taskId == taskId && java.util.Objects.equals(upper.taskToken, token);
        // Explicit returns fade the top task before changing its native ownership.
        if(front && host.topPresentationHost.topReady() && host.slots[WorkspaceHost.SLOT_TOP].occupied
                && !host.slots[WorkspaceHost.SLOT_TOP].promoting && !host.suspendRequested
                && startLiveMainRevealTransition(()->{
                    if (!stillBound.getAsBoolean()) {
                        host.topPresentationHost.cancelTopTaskTransition();
                        return;
                    }
                    final TopTaskTransition bridge = host.topTransition;
                    Runnable handBack = () -> {
                        if (host.topTransition != bridge) return;
                        if (!stillBound.getAsBoolean()) {
                            host.topPresentationHost.cancelTopTaskTransition();
                            return;
                        }
                        releaseTopTaskNow(front, dismissed, after, true);
                    };
                    if (host.topFace && !host.layout.tabletLandscape) {
                        // Fade the task out over the strip's own clock and rail so the
                        // exit mirrors the entry.
                        fadeTopTaskOut(handBack);
                    } else handBack.run();
                }))return;
        releaseTopTaskNow(front,dismissed,after,liveMainReveal);
    }

    void releaseTopTaskNow(boolean front, boolean dismissed, Runnable after) {
        releaseTopTaskNow(front,dismissed,after,false);
    }

    void releaseTopTaskNow(boolean front, boolean dismissed, Runnable after,
            boolean liveMainReveal) {
        Slot upper=host.slots[WorkspaceHost.SLOT_TOP];
        if(!upper.occupied) {host.topPresentationHost.cancelTopTaskTransition();if(after!=null)after.run();return;}
        if(host.scene!=WorkspaceHost.ACTIVE || host.promotion!=null || host.transferBusy || upper.promoting) {host.topPresentationHost.cancelTopTaskTransition();return;}
        final LiveTask task=new LiveTask(upper);
        final TopTaskState.Key key=host.topTaskState.owner();
        final Slot origin=host.topOriginalSlot;
        boolean returnFront=front;
        try { if(host.topFromMain && origin==null && host.slotRegistry.findMainTask()==null)returnFront=true; } catch(Throwable ignored){}
        final boolean bringingFront=returnFront;
        host.returnToSystem(upper,bringingFront,liveMainReveal,()->{
            host.topTaskState.release(key);host.slotLifecycle.clearSlot(upper);host.topOriginalSlot=null;
            Runnable finish=()->{
                if(front) {
                    // The strip is empty again, so the title face (clock and app rail)
                    // must come back; leaving the task face up showed a blank strip.
                    host.topFace=false;
                    settleTopFace(false);
                }
                host.topPresentationHost.revealTopTaskTransition();
                if(after!=null)after.run();host.resumes.finishPendingSuspend();
            };
            if(!front && origin!=null && host.slotRegistry.isActive(origin) && !origin.occupied && host.scene==WorkspaceHost.ACTIVE)
                host.adoptExisting(origin,task,finish,error->{WorkspaceHost.log("upper return original unavailable: "+error);finish.run();});
            else finish.run();
            WorkspaceHost.log("upper task returned alive="+task.id+" front="+bringingFront+" dismissed="+dismissed);
        },error->{
            host.topPresentationHost.cancelTopTaskTransition();
            WorkspaceHost.log("upper return pending: "+error);
            if(!host.topFace)host.topFace=true;
            host.slotLifecycle.updateHint(upper);host.root.requestLayout();WorkspaceHost.log("upper return stopped: "+error);host.resumes.finishPendingSuspend();
        });
    }

    void observeWideVideo(TopTaskState.Key key, boolean landscape, boolean fullscreen,
            boolean focused, int mode, boolean portrait, long issued) {
        if(host.root==null || !host.slotRegistry.supportsTaskUser(key.userId)
                || !WideVideoPolicy.fresh(android.os.SystemClock.uptimeMillis(),issued))return;
        if(wideVideoEpoch!=host.sceneEpoch) {wideVideoEpoch=host.sceneEpoch;wideVideoPolicy.clear();}
        wideVideoPolicy.observe(key,landscape,fullscreen,mode);
        // Task migration can publish temporary portrait/non-fullscreen state. Never treat
        // these configuration callbacks as a request to return a pinned application.
        if(mode!=1)return;
        final long sequence=++wideVideoSequence;
        if(!wideVideoPolicy.eligible(key,landscape,fullscreen,focused,mode))return;
        final long epoch=host.sceneEpoch;
        attemptWideVideo(key,epoch,sequence,issued,host.workspaceOptions.landscapeVideo);
    }

    private void attemptWideVideo(TopTaskState.Key key, long epoch, long sequence, long issued, int mode) {
        if(epoch!=host.sceneEpoch || sequence!=wideVideoSequence || host.root==null
                || !WideVideoPolicy.fresh(android.os.SystemClock.uptimeMillis(),issued)
                || host.workspaceOptions.landscapeVideo!=mode || !host.screenOn || host.keyguardLocked
                || host.retainedForLock || host.suspendRequested || (host.scene!=WorkspaceHost.ACTIVE && host.scene!=WorkspaceHost.RESUMING)
                || !EntryGuard.canOpen(host.ctx))return;
        if(!host.topPresentationHost.topReady() || host.slots[WorkspaceHost.SLOT_TOP].view==null || host.slots[WorkspaceHost.SLOT_TOP].controller==null
                || host.topTaskState.busy() || host.topTransition!=null) {
            host.main.postDelayed(()->attemptWideVideo(key,epoch,sequence,issued,mode),80L);
            return;
        }
        TopCandidate current=host.topAdmission.currentTopCandidate();
        if(current==null || !key.equals(current.key))return;
        try {
            Object fresh=host.slotRegistry.rootInfo(current.task);
            if(fresh==null || !XposedHelpers.getBooleanField(fresh,"isFocused"))return;
        } catch(Throwable unavailable) {return;}
        wideVideoPolicy.handled(key);
        if(mode==WorkspaceOptions.VIDEO_FULLSCREEN) {
            WorkspaceHost.log("wide video exits workspace task="+key.taskId);
            host.resumes.suspendSession("video-fullscreen");
        } else WorkspaceHost.log("wide video stays in main task="+key.taskId);
    }

    void topTaskDirection(TopTaskState.Key key, TopDirection direction) {
        if(host.root==null || !host.slotRegistry.supportsTaskUser(key.userId))return;
        host.topDirections.put(key,direction);
        if(host.topDirections.size()>64)host.topDirections.keySet().removeIf(item->!item.equals(host.topTaskState.owner()) && !item.equals(key));
        // Observe only. A direction change never opens the upper face or claims a task.
        host.chrome.updateTopBarState();
    }

    void createTopPresentation() {
        host.topPresentation=new TopPresentationController(host.ctx,host.main,host.wm,new TopPresentationController.Host() {
            @Override public boolean mayCaptureVideo() {
                return host.scene==WorkspaceHost.ACTIVE && host.screenOn && !host.keyguardLocked && !host.workspaceEntering
                        && host.workspaceAlpha>=1f && !host.slots[WorkspaceHost.SLOT_TOP].occupied
                        && !host.topTaskState.busy() && host.topTransition==null && !host.topFace;
            }
            @Override public boolean mayShowTaskControls() {
                return host.topFace && host.scene==WorkspaceHost.ACTIVE && host.screenOn && !host.keyguardLocked
                        && host.slots[WorkspaceHost.SLOT_TOP].occupied && !host.slots[WorkspaceHost.SLOT_TOP].parked
                        && !host.slots[WorkspaceHost.SLOT_TOP].promoting && host.layout!=null && !host.layout.tabletLandscape
                        && host.topTransition==null && !host.workspaceEntering && host.workspaceAlpha>=1f;
            }
            @Override public int width() { return host.screenW; }
            @Override public int height() { return host.layout.topStrip; }
            @Override public void videoChanged() { host.chrome.updateTopBarState(); }
            @Override public void exitTask() { releaseTopTask(true,true,null); }
        });
    }

    void updateTopExitWindow() {
        if(host.topPresentation!=null)host.topPresentation.refreshControls();
    }

    void releaseTopExitWindow() {
        if(host.topPresentation!=null)host.topPresentation.hideControls();
    }

    static boolean ownsTopNativeCaption(Object info) {
        WorkspaceHost h=WorkspaceHost.currentInstance();
        if(h==null || h.root==null || h.scene!=WorkspaceHost.ACTIVE || info==null)return false;
        Slot upper=h.slots[WorkspaceHost.SLOT_TOP];
        if(upper==null || !upper.occupied || upper.taskId<0 || upper.taskToken==null)return false;
        try {
            return XposedHelpers.getIntField(info,"displayId")==0
                    && Integer.valueOf(6).equals(XposedHelpers.callMethod(info,"getWindowingMode"))
                    && XposedHelpers.getIntField(info,"taskId")==upper.taskId
                    && upper.taskToken.equals(XposedHelpers.getObjectField(info,"token"));
        } catch(Throwable ignored) {return false;}
    }

    static void onTopTaskState(Intent intent) {
        WorkspaceHost host=WorkspaceHost.currentInstance();
        if(host==null)return;
        WorkspaceContract.TopTaskSignal signal=new WorkspaceContract.TopTaskSignal(
                intent.getIntExtra(WorkspaceContract.EXTRA_TASK,-1),
                intent.getIntExtra(WorkspaceContract.EXTRA_USER,-1),
                intent.getParcelableExtra(WorkspaceContract.EXTRA_TOKEN),
                intent.getBooleanExtra(WorkspaceContract.EXTRA_LANDSCAPE,false),
                intent.getBooleanExtra(WorkspaceContract.EXTRA_FULLSCREEN,false),
                intent.getBooleanExtra(WorkspaceContract.EXTRA_FOCUSED,false),
                intent.getIntExtra(WorkspaceContract.EXTRA_MODE,-1),
                intent.getBooleanExtra(WorkspaceContract.EXTRA_PORTRAIT,false),
                intent.getStringExtra(WorkspaceContract.EXTRA_PAGE),
                intent.getIntExtra(WorkspaceContract.EXTRA_REQUEST,Integer.MIN_VALUE),
                intent.getIntExtra(WorkspaceContract.EXTRA_VIEWPORT_ORIENTATION,0),
                intent.getLongExtra(WorkspaceContract.EXTRA_ISSUED,0));
        if(!signal.hasTaskIdentity())return;
        final TopTaskState.Key key=new TopTaskState.Key(signal.taskId(),signal.userId(),signal.token());
        host.main.post(()->{
            host.topPresentationHost.topTaskDirection(key,new TopDirection(signal.landscape(),signal.page(),
                    signal.request(),signal.mode(),signal.viewportOrientation()));
            host.topPresentationHost.observeWideVideo(key,signal.landscape(),signal.fullscreen(),
                    signal.focused(),signal.mode(),signal.portrait(),signal.issued());
        });
    }

    void preferMainAfterTopChange() {
        final long epoch=host.sceneEpoch;
        host.executeOnShell(()->{
            if(epoch!=host.sceneEpoch || host.scene!=WorkspaceHost.ACTIVE || host.suspendRequested || !host.screenOn || host.keyguardLocked)return;
            try {
                for(Object info:host.slotRegistry.rootInfos())if(XposedHelpers.getBooleanField(info,"isFocused")
                        && host.taskStates.windowModeOf(info)!=1 && !host.slotRegistry.isSideToken(XposedHelpers.getObjectField(info,"token")))return;
                LiveTask target=host.slotRegistry.findMainTask();
                if(target==null)target=host.slotRegistry.existingHomeTask();
                Object info=host.slotRegistry.rootInfo(target);
                if(info==null || host.taskStates.windowModeOf(info)!=1 || !XposedHelpers.getBooleanField(info,"isVisible"))return;
                Object service=XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityTaskManager"),"getService");
                XposedHelpers.callMethod(service,"setFocusedTask",target.id);
                WorkspaceHost.log("upper default main focus task="+target.id);
            } catch(Throwable error) {WorkspaceHost.log("upper main focus unavailable: "+error);}
        });
    }
}
