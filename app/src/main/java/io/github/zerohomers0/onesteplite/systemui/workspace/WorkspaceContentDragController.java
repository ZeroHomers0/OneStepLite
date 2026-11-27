package io.github.zerohomers0.onesteplite;


/** Routes content drags to live workspace tasks and owns the hover target. */
final class WorkspaceContentDragController {
    private final WorkspaceHost host;
    private Slot contentDropSlot;
    private String contentDropIdentity;
    private final DropTargetHighlight contentDropHighlight=new DropTargetHighlight();
    private String dragHoverDropPackage,dragHoverDropSource;
    private long dragHoverDropScene;
    private int dragHoverDropUser;
    private int[] dragHoverDropTarget;

    WorkspaceContentDragController(WorkspaceHost host) {
        this.host = host;
    }

    String contentHoverKeyFor(String pkg) {
        if(pkg==null || host.scene!=WorkspaceHost.ACTIVE || host.promotion!=null || host.transferBusy || !host.screenOn || host.keyguardLocked || host.taskCellsCovered){
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
                host.sceneEpoch,dragHoverDropUser,host.slotRegistry.currentUserId());
    }

    private boolean overDragHoverTarget(android.view.DragEvent event) {
        int[] rect=dragHoverDropTarget;
        return rect!=null && event.getX()>=rect[0] && event.getX()<rect[2]
                && event.getY()>=rect[1] && event.getY()<rect[3];
    }

    private int[] dragHoverTargetBounds(boolean hadMain) {
        if(host.layout==null)return null;
        boolean right=host.layout.tabletLandscape || !hadMain
                ?host.layout.mainOnRight:host.workspaceOptions.mainOnRightAfterSwap(host.layout.mainOnRight);
        return host.windowLayout.makeAdaptiveLayout(right).mainBounds();
    }

    private String contentDropKey(Slot slot) {
        if(!host.slotRegistry.isActive(slot) || !slot.occupied || slot.parked || slot.frozen || slot.carrier
                || slot.launching || slot.promoting || slot.closing || slot.pkg==null
                || slot.taskId<0 || slot.taskToken==null || slot.windowMode!=6
                || !host.slotRegistry.controllerOwns(slot,slot.taskId,slot.taskToken) || !host.scales.mayWriteSideSurface(slot)
                || slot.view==null || !slot.view.isShown())return null;
        return slot.pkg+"\n"+slot.taskId+":"+slot.taskEpoch+":"+host.sceneEpoch+":"+host.slotRegistry.currentUserId();
    }

    void promoteContentHover(String key) {
        int split=key==null?-1:key.indexOf('\n');if(split<1)return;
        String pkg=key.substring(0,split);
        if(!key.equals(contentHoverKeyFor(pkg)))return;
        // The cell is about to be rebound to the previous main. Capture the source
        // package and predicted main bounds while the hovered task still owns the cell.
        LiveTask main=null;
        try{main=host.slotRegistry.findMainTask();}catch(Exception unavailable){/* Home/main identity is optional. */}
        int[] target=dragHoverTargetBounds(main!=null);
        String source=main==null?null:main.pkg;
        contentDropHighlight.close();
        if(WorkspaceMainLaunchController.promoteForNotification(pkg,host.slotRegistry.currentUserId(),()->{})){
            host.promotion.dragHover=true;
            dragHoverDropPackage=pkg;dragHoverDropSource=source;
            dragHoverDropScene=host.sceneEpoch;dragHoverDropUser=host.slotRegistry.currentUserId();dragHoverDropTarget=target;
        }
    }

    boolean onWindowContentDrag(android.view.DragEvent event) {
        if(host.root==null)return false;
        int action=event.getAction();
        if(action==android.view.DragEvent.ACTION_DRAG_ENDED
                || action==android.view.DragEvent.ACTION_DRAG_EXITED) {
            contentDropSlot=null;contentDropIdentity=null;contentDropHighlight.close();host.sideInteraction.showQuickDrop(host.root,null);
            boolean accepted=host.topBar!=null && host.topBar.onWindowContentDrag(event,null);
            if(action==android.view.DragEvent.ACTION_DRAG_ENDED)clearDragHoverDrop();
            return accepted;
        }
        boolean hoverDrop=dragHoverDropCurrent();
        boolean promotionBlocks=host.promotion!=null && !DragHoverDropPolicy.keepPromotionDrag(hoverDrop,
                action==android.view.DragEvent.ACTION_DRAG_STARTED,
                action==android.view.DragEvent.ACTION_DRAG_LOCATION,
                action==android.view.DragEvent.ACTION_DROP);
        if(host.topBar==null || !host.workspaceOptions.dragShareEnabled || host.scene!=WorkspaceHost.ACTIVE || !host.screenOn || host.keyguardLocked
                || host.transferBusy || host.taskCellsCovered || promotionBlocks) {
            contentDropSlot=null;contentDropIdentity=null;contentDropHighlight.close();host.sideInteraction.showQuickDrop(host.root,null);
            if(promotionBlocks || !hoverDrop)clearDragHoverDrop();
            return false;
        }
        if(action==android.view.DragEvent.ACTION_DRAG_STARTED){
            if(!hoverDrop)clearDragHoverDrop();
            contentDropSlot=null;contentDropIdentity=null;contentDropHighlight.close();
            if(host.promotion!=null)return true;
            return host.topBar.onWindowContentDrag(event,null);
        }
        Slot hit=null;String identity=null;
        for(int index=1;index<host.CELL_SLOT_COUNT;index++) {
            Slot slot=host.slots[index];int[] rect=slot.rect;
            if(rect==null || event.getX()<rect[0] || event.getX()>=rect[2]
                    || event.getY()<rect[1] || event.getY()>=rect[3])continue;
            identity=contentDropKey(slot);if(identity!=null)hit=slot;
            break;
        }
        if(action==android.view.DragEvent.ACTION_DRAG_LOCATION){
            if(host.promotion!=null){
                // The hovered cell has moved into the main area. Keep this drag session
                // alive without pretending the empty old cell is still the target.
                contentDropSlot=null;contentDropIdentity=null;
                return hoverDrop;
            }
            contentDropSlot=hit;contentDropIdentity=identity;
            boolean accepted=host.topBar.onWindowContentDrag(event,hit==null?null:hit.pkg);
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
        contentDropSlot=null;contentDropIdentity=null;contentDropHighlight.close();host.sideInteraction.showQuickDrop(host.root,null);
        if(promoted)return host.topBar.onPromotedContentDrop(event,dragHoverDropPackage,
                DragHoverDropPolicy.deferUntilReady(host.promotion!=null));
        return host.topBar.onWindowContentDrag(event,target);
    }
    void clearHighlight() {
        contentDropSlot = null;
        contentDropIdentity = null;
        contentDropHighlight.close();
    }

    boolean onTransitionDrop(android.view.DragEvent event) {
        if (host.topBar == null || host.scene != WorkspaceHost.ACTIVE || !host.screenOn
                || host.keyguardLocked || !host.workspaceOptions.dragShareEnabled || !dragHoverDropCurrent()
                || dragHoverDropPackage.equals(dragHoverDropSource)) return false;
        android.content.ClipDescription clip = event.getClipDescription();
        if (clip == null || !clip.hasMimeType("image/*")) return false;
        if (event.getAction() == android.view.DragEvent.ACTION_DRAG_STARTED) return true;
        if (event.getAction() != android.view.DragEvent.ACTION_DROP || !overDragHoverTarget(event)) return false;
        return host.topBar.onPromotedContentDrop(event, dragHoverDropPackage, host.promotion != null);
    }

}
