package io.github.zerohomers0.onesteplite;


/** Routes content drags to live workspace tasks and owns the hover target. */
final class WorkspaceContentDragController {
    private final WorkspacePorts.ScenePort scenes;
    private final WorkspacePorts.SlotPort slots;
    private final WorkspacePorts.SurfacePort surfaces;
    private final WorkspacePorts.PresentationPort presentation;
    private final WorkspacePorts.TaskPort tasks;
    private Slot contentDropSlot;
    private String contentDropIdentity;
    private final DropTargetHighlight contentDropHighlight=new DropTargetHighlight();
    private String dragHoverDropPackage,dragHoverDropSource;
    private long dragHoverDropScene;
    private int dragHoverDropUser;
    private int[] dragHoverDropTarget;

    WorkspaceContentDragController(WorkspacePorts.ScenePort scenes,WorkspacePorts.SlotPort slots,
            WorkspacePorts.SurfacePort surfaces,WorkspacePorts.PresentationPort presentation,
            WorkspacePorts.TaskPort tasks) {
        this.scenes=scenes;this.slots=slots;this.surfaces=surfaces;
        this.presentation=presentation;this.tasks=tasks;
    }

    String contentHoverKeyFor(String pkg) {
        if(pkg==null || scenes.scene()!=WorkspaceHost.ACTIVE || tasks.promotion()!=null || tasks.transferBusy() || !scenes.screenOn() || scenes.keyguardLocked() || scenes.taskCellsCovered()){
            return null;
        }
        Slot slot=contentDropSlot;
        return slot!=null && pkg.equals(slot.pkg)?contentDropKey(slot):null;
    }

    private void clearDragHoverDrop() {
        dragHoverDropPackage=null;dragHoverDropSource=null;dragHoverDropTarget=null;
        dragHoverDropScene=0L;dragHoverDropUser=-1;
    }

    private boolean dragHoverDropCurrent() {
        return DragHoverDropPolicy.current(dragHoverDropPackage!=null,dragHoverDropScene,
                scenes.sceneEpoch(),dragHoverDropUser,slots.slotRegistry().currentUserId());
    }

    private boolean overDragHoverTarget(android.view.DragEvent event) {
        int[] rect=dragHoverDropTarget;
        return rect!=null && event.getX()>=rect[0] && event.getX()<rect[2]
                && event.getY()>=rect[1] && event.getY()<rect[3];
    }

    private int[] dragHoverTargetBounds(boolean hadMain) {
        if(surfaces.layout()==null)return null;
        boolean right=surfaces.layout().tabletLandscape || !hadMain
                ?surfaces.layout().mainOnRight:presentation.options().mainOnRightAfterSwap(surfaces.layout().mainOnRight);
        return surfaces.windowLayout().makeAdaptiveLayout(right).mainBounds();
    }

    private String contentDropKey(Slot slot) {
        if(!slots.slotRegistry().isActive(slot) || !slot.occupied || slot.parked || slot.frozen || slot.carrier
                || slot.launching || slot.promoting || slot.closing || slot.pkg==null
                || slot.taskId<0 || slot.taskToken==null || slot.windowMode!=6
                || !slots.slotRegistry().controllerOwns(slot,slot.taskId,slot.taskToken) || !tasks.scales().mayWriteSideSurface(slot)
                || slot.view==null || !slot.view.isShown())return null;
        return slot.pkg+"\n"+slot.taskId+":"+slot.taskEpoch+":"+scenes.sceneEpoch()+":"+slots.slotRegistry().currentUserId();
    }

    void promoteContentHover(String key) {
        int split=key==null?-1:key.indexOf('\n');if(split<1)return;
        String pkg=key.substring(0,split);
        if(!key.equals(contentHoverKeyFor(pkg)))return;
        // The cell is about to be rebound to the previous main. Capture the source
        // package and predicted main bounds while the hovered task still owns the cell.
        LiveTask main=null;
        try{main=slots.slotRegistry().findMainTask();}catch(Exception unavailable){/* Home/main identity is optional. */}
        int[] target=dragHoverTargetBounds(main!=null);
        String source=main==null?null:main.pkg;
        contentDropHighlight.close();
        if(WorkspaceMainLaunchController.promoteForNotification(pkg,slots.slotRegistry().currentUserId(),()->{})){
            tasks.promotion().dragHover=true;
            dragHoverDropPackage=pkg;dragHoverDropSource=source;
            dragHoverDropScene=scenes.sceneEpoch();dragHoverDropUser=slots.slotRegistry().currentUserId();dragHoverDropTarget=target;
        }
    }

    boolean onWindowContentDrag(android.view.DragEvent event) {
        if(presentation.root()==null)return false;
        int action=event.getAction();
        if(action==android.view.DragEvent.ACTION_DRAG_ENDED
                || action==android.view.DragEvent.ACTION_DRAG_EXITED) {
            contentDropSlot=null;contentDropIdentity=null;contentDropHighlight.close();presentation.sideInteraction().showQuickDrop(presentation.root(),null);
            boolean accepted=presentation.topBar()!=null && presentation.topBar().onWindowContentDrag(event,null);
            if(action==android.view.DragEvent.ACTION_DRAG_ENDED)clearDragHoverDrop();
            return accepted;
        }
        boolean hoverDrop=dragHoverDropCurrent();
        boolean promotionBlocks=tasks.promotion()!=null && !DragHoverDropPolicy.keepPromotionDrag(hoverDrop,
                action==android.view.DragEvent.ACTION_DRAG_STARTED,
                action==android.view.DragEvent.ACTION_DRAG_LOCATION,
                action==android.view.DragEvent.ACTION_DROP);
        if(presentation.topBar()==null || !presentation.options().dragShareEnabled || scenes.scene()!=WorkspaceHost.ACTIVE || !scenes.screenOn() || scenes.keyguardLocked()
                || tasks.transferBusy() || scenes.taskCellsCovered() || promotionBlocks) {
            contentDropSlot=null;contentDropIdentity=null;contentDropHighlight.close();presentation.sideInteraction().showQuickDrop(presentation.root(),null);
            if(promotionBlocks || !hoverDrop)clearDragHoverDrop();
            return false;
        }
        if(action==android.view.DragEvent.ACTION_DRAG_STARTED){
            if(!hoverDrop)clearDragHoverDrop();
            contentDropSlot=null;contentDropIdentity=null;contentDropHighlight.close();
            if(tasks.promotion()!=null)return true;
            return presentation.topBar().onWindowContentDrag(event,null);
        }
        Slot hit=null;String identity=null;
        for(int index=1;index<slots.cellSlotCount();index++) {
            Slot slot=slots.slots()[index];int[] rect=slot.rect;
            if(rect==null || event.getX()<rect[0] || event.getX()>=rect[2]
                    || event.getY()<rect[1] || event.getY()>=rect[3])continue;
            identity=contentDropKey(slot);if(identity!=null)hit=slot;
            break;
        }
        if(action==android.view.DragEvent.ACTION_DRAG_LOCATION){
            if(tasks.promotion()!=null){
                // The hovered cell has moved into the main area. Keep this drag session
                // alive without pretending the empty old cell is still the target.
                contentDropSlot=null;contentDropIdentity=null;
                return hoverDrop;
            }
            contentDropSlot=hit;contentDropIdentity=identity;
            boolean accepted=presentation.topBar().onWindowContentDrag(event,hit==null?null:hit.pkg);
            contentDropHighlight.show(accepted && hit!=null?hit.view:null);
            return accepted;
        }
        if(action!=android.view.DragEvent.ACTION_DROP)return false;
        boolean sameSourceHit=hit!=null && dragHoverDropSource!=null
                && dragHoverDropSource.equals(hit.pkg);
        String target=hit!=null && !sameSourceHit && hit==contentDropSlot
                && identity!=null && identity.equals(contentDropIdentity)?hit.pkg:null;
        boolean sameSource=hoverDrop && dragHoverDropSource!=null
                && dragHoverDropPackage.equals(dragHoverDropSource);
        // The side cell is gone after hover promotion. If the host still owns the DROP,
        // keep the task the user aimed at instead of silently cancelling the session.
        boolean promoted=DragHoverDropPolicy.usePromotedTarget(hoverDrop,hit!=null,
                overDragHoverTarget(event),sameSource);
        contentDropSlot=null;contentDropIdentity=null;contentDropHighlight.close();presentation.sideInteraction().showQuickDrop(presentation.root(),null);
        if(promoted)return presentation.topBar().onPromotedContentDrop(event,dragHoverDropPackage,
                DragHoverDropPolicy.deferUntilReady(tasks.promotion()!=null));
        return presentation.topBar().onWindowContentDrag(event,target);
    }
    void clearHighlight() {
        contentDropSlot = null;
        contentDropIdentity = null;
        contentDropHighlight.close();
    }

    boolean onTransitionDrop(android.view.DragEvent event) {
        if (presentation.topBar() == null || scenes.scene() != WorkspaceHost.ACTIVE || !scenes.screenOn()
                || scenes.keyguardLocked() || !presentation.options().dragShareEnabled || !dragHoverDropCurrent()
                || dragHoverDropPackage.equals(dragHoverDropSource)) return false;
        android.content.ClipDescription clip = event.getClipDescription();
        if (clip == null || !clip.hasMimeType("image/*")) return false;
        if (event.getAction() == android.view.DragEvent.ACTION_DRAG_STARTED) return true;
        if (event.getAction() != android.view.DragEvent.ACTION_DROP || !overDragHoverTarget(event)) return false;
        return presentation.topBar().onPromotedContentDrop(event, dragHoverDropPackage, tasks.promotion() != null);
    }

}
