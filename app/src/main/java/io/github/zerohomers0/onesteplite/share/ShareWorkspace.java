package io.github.zerohomers0.onesteplite;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Owns only the content rail and drag session; task promotion stays with the Host. */
final class ShareWorkspace extends FrameLayout {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final HorizontalScrollView scroll;
    private final FrameLayout shareFace;
    private final LinearLayout targets;
    private final IconButton content;
    private final ContentPanel panel;
    private final SourceCaptureController capture;
    private final PortalSource portal;
    private final ClipboardHistory history;
    private final BooleanSupplier ready;
    private final BiConsumer<ComponentName, Runnable> dispatch;
    private final Consumer<String> hint;
    private final View normalRail;
    private final Supplier<android.graphics.Rect> bounds;
    private final boolean shareOnly;
    private BooleanSupplier covered=()->false;
    private ShareRailOverlay overlay;
    private boolean deliveryPending,retainingDelivery;
    private Runnable deliverySettled=()->{};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ShareEdgePolicy edge=new ShareEdgePolicy();
    private final ShareRailTransition transition;
    private final float[] pointer=new float[2],physicalAim=new float[2];
    private final int[] railOrigin=new int[2],plateOrigin=new int[2];
    private float lastX,lastY;
    private boolean hasPointer;
    private boolean edgeQueued;
    private final Runnable edgeTick=this::scrollEdge;
    private boolean dragging, dropped;
    private volatile int generation;
    private int user;
    private ComponentName hovered;
    private String sourcePackage;
    private java.util.Set<String> windowPackages;
    private long lastFeedback;
    private ShareReadLease pendingRead;
    private DropPermissionContract delegatedRead;
    private final Runnable expire = ()->{if(deliveryPending)failDelivery(generation,user);else cancel();};
    private static final ComponentName IMAGE_TEXT=new ComponentName(ContentInboxProvider.MODULE,"recognize_image_text");
    private boolean imageDrag;
    private Runnable afterDrag=()->{};
    private ShareDragCatchment catchment;
    private int tipOffset;
    private float dragHalfWidth;
    private boolean mainRight,panelSource;
    private ShareDelivery receipt;
    private ClipData transferClip;
    private final WindowDragHover windowHover;
    private java.util.function.Function<String,String> hoverIdentity=value->value;
    private Consumer<String> hoverActivate=value->{};
    private BiConsumer<Boolean,ClipData> resultSink=this::onDeliveryResult;

    void setActiveViewSource(java.util.function.Predicate<String> source) {
        if(portal!=null)portal.setActiveViewSource(source);
    }

    ShareWorkspace(Context context, View normalRail, BooleanSupplier ready,
            BiConsumer<ComponentName, Runnable> dispatch, Consumer<String> hint,
            Supplier<android.graphics.Rect> bounds) {
        this(context,normalRail,ready,dispatch,hint,bounds,false);
    }

    ShareWorkspace(Context context, View normalRail, BooleanSupplier ready,
            BiConsumer<ComponentName, Runnable> dispatch, Consumer<String> hint,
            Supplier<android.graphics.Rect> bounds,boolean shareOnly) {
        super(context);
        this.bounds=bounds;this.shareOnly=shareOnly;
        android.graphics.Rect initialBounds=bounds.get();mainRight=initialBounds!=null && initialBounds.left>0;
        this.normalRail = normalRail;
        this.ready = ready;
        windowHover=new WindowDragHover(this,()->dragging && this.ready.getAsBoolean());
        this.dispatch = dispatch;
        this.hint = hint;
        history=new ClipboardHistory(context);
        capture=new SourceCaptureController(this,()->ready.getAsBoolean() && !dragging,bounds);
        panel = new ContentPanel(this, bounds,history,()->capture.open());
        portal=shareOnly?null:new PortalSource(this,()->ready.getAsBoolean() && !dragging,bounds,panel::showExtractedImage);
        content = new IconButton(context, IconButton.CONTENT, "内容：近期图片、文件和剪贴板");
        content.setCompactGlyph();
        content.setTooltipText("近期图片、文件和剪贴板");
        content.setOnClickListener(v -> {
            if (!ready.getAsBoolean() || dragging) return;
            try { panel.open(); }
            catch (RuntimeException unavailable) {
                panel.close(); message("暂时无法打开内容面板");
                android.util.Log.w(Tags.PROBE, "content panel unavailable", unavailable);
            }
        });
        LayoutParams contentBounds=new LayoutParams(dp(50),-1);contentBounds.leftMargin=dp(6);addView(content,contentBounds);
        if(normalRail instanceof QuickAppRail) {
            content.setTooltipText(null);content.setClickable(false);content.setLongClickable(false);
            ((QuickAppRail)normalRail).setContentCell(dp(46),content::performClick,
                    offset->content.setTranslationX(-offset));
        }
        shareFace=new FrameLayout(context);shareFace.setBackground(HyperOsStyle.shareRailBackground(context));
        shareFace.setClipChildren(false);shareFace.setClipToPadding(false);
        addView(shareFace,new LayoutParams(-1,-1));
        scroll = new HorizontalScrollView(context);
        scroll.setClipChildren(false);scroll.setClipToPadding(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOnScrollChangeListener((view,x,y,oldX,oldY)->{
            if(dragging && hasPointer)updateHovered(lastX,lastY);
        });
        targets = new LinearLayout(context);targets.setClipChildren(false);targets.setClipToPadding(false);
        scroll.addView(targets, new FrameLayout.LayoutParams(-2, -1));
        LayoutParams row = new LayoutParams(-1, -1);
        shareFace.addView(scroll, row);
        shareFace.setVisibility(INVISIBLE);
        transition=new ShareRailTransition(normalRail,shareFace,content);
        transition.onFrame(()->{if(dragging && hasPointer)updateHovered(lastX,lastY);});
        setOnDragListener((v, event) -> drag(event, null));
        if(shareOnly)setAlpha(0);
    }

    void refreshAppearance(){shareFace.setBackground(HyperOsStyle.shareRailBackground(getContext()));}

    private boolean drag(DragEvent event, View plate) {
        if(!shareOnly && (covered.getAsBoolean() || overlay!=null && overlay.isOpen()))
            return onWindowDrag(event,null,true,plate==null?this:plate);
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                if (!ready.getAsBoolean() || !ContentAccessCompat.currentProcessUser()
                        || event.getLocalState()!=null && !(event.getLocalState() instanceof SharePayload)
                        || !accepts(event.getClipDescription())) return false;
                if(dragging)return true;
                cancel();
                dragging = true;panelSource=panel.isDragging();
                sourcePackage=event.getClipDescription().getExtras()==null?null:event.getClipDescription().getExtras().getString("onesteplite_source_package");
                if(portal!=null)portal.dragStarted();
                ContentDragMotion.managed(this,event.getClipDescription());
                performHapticFeedback(android.view.HapticFeedbackConstants.DRAG_START);
                tipOffset=ContentAccessCompat.arrowOffset(event);
                dragHalfWidth=ContentAccessCompat.shadowHalfWidth(event);
                if(tipOffset>0) {
                    catchment=new ShareDragCatchment(this,tipOffset,()->dragging && ready.getAsBoolean());catchment.start();
                }
                user = CurrentUser.id();
                int token = generation;
                SharePayload local=event.getLocalState() instanceof SharePayload
                        ?(SharePayload)event.getLocalState():null;
                ClipDescription description = local==null?new ClipDescription(event.getClipDescription())
                        :new ClipDescription("内容",new String[]{local.mime});
                imageDrag=description.hasMimeType("image/*");
                int count=local==null?0:Math.max(1,local.uris.size());
                if(local==null && description.getExtras()!=null)count=description.getExtras().getInt("onesteplite_item_count",0);
                final int itemCount=count;
                handler.postDelayed(expire, 60_000L);
                WORKER.execute(() -> {
                    ShareTargetCatalog.Matches matches=null;
                    try {
                        matches=ShareTargetCatalog.queryMatches(getContext(),description,itemCount);
                        java.util.Set<String> packages=matches.packages();
                        post(()->{if(live(token))windowPackages=packages;});
                    }catch(RuntimeException unavailable){ /* Keep native target resolution available at DROP. */ }
                    try {
                        List<ShareTarget> entries=ShareTargetCatalog.loadForDrag(getContext(),description,itemCount,matches);
                        post(()->{if(live(token))showTargets(entries);});
                    } catch(RuntimeException unavailable) {
                        post(()->{if(live(token))message("无法查询分享入口");});
                    }
                });
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                windowHover.close();
                if(dragging)locateDragTip(event,plate);
                return dragging;
            case DragEvent.ACTION_DRAG_EXITED:
                // The translucent catchment overlaps the rail window during a drag. An EXITED
                // from the rail can therefore mean the pointer moved into the catchment, not
                // that it left the share area; the catchment owns the authoritative exit.
                if(plate==null && catchment!=null)return dragging;
                hasPointer=false;stopEdge();hovered = null; clearTargetHover();hint.accept(null); return dragging;
            case DragEvent.ACTION_DROP:
                if (!live(generation)) return false;
                ComponentName selected=hovered;
                locateDragTip(event,plate);
                // The icon was already visibly selected; a late window-coordinate miss must not
                // turn that selection into a silent cancellation.
                if (hovered == null) hovered = selected;
                if (hovered == null) { ShareDiagnostics.event(ShareDiagnostics.Stage.DRAG,
                        ShareDiagnostics.Code.REJECTED); message("请将内容拖到分享入口后松手"); return false; }
                return drop(event, hovered);
            case DragEvent.ACTION_DRAG_ENDED:
                if(!dragging)return true;
                if(!dropped) {
                    // The underlying rail window can receive ENDED before the catchment window
                    // dispatches its DROP. Defer retirement one event turn so a late DROP can
                    // still claim the selected target instead of being invalidated.
                    final boolean endedAccepted=event.getResult();
                    final int endedGeneration=generation;
                    handler.postDelayed(()->{
                        if(!dragging || generation!=endedGeneration)return;
                        ContentDragMotion.close();
                        boolean consumed=dropped || endedAccepted;
                        finishVisuals();
                        panel.dragEnded(consumed);
                        Runnable next=afterDrag;afterDrag=()->{};handler.post(next);
                        int finishedGeneration=generation;
                        handler.post(()->{if(!dragging && generation==finishedGeneration)dragFinished.run();});
                    },64L);
                    return true;
                }
                ContentDragMotion.close();
                finishVisuals();
                panel.dragEnded(true);
                Runnable next=afterDrag;afterDrag=()->{};handler.post(next);
                int finishedGeneration=generation;
                handler.post(()->{if(!dragging && generation==finishedGeneration)dragFinished.run();});
                return true;
            default: return dragging;
        }
    }

    private static boolean accepts(ClipDescription description) {
        if (description == null) return false;
        boolean supported = false;
        for (int i = 0; i < description.getMimeTypeCount(); i++) {
            if (!ContentSharePolicy.accepts(description.getMimeType(i))) return false;
            supported = true;
        }
        return supported;
    }

    boolean onTipDrag(View plate,DragEvent event){return drag(event,plate);}
    private void locateDragTip(DragEvent event,View plate) {
        refreshTip(event);
        if(ContentDragMotion.physicalTip(event.getClipDescription(),physicalAim)){
            getLocationOnScreen(railOrigin);
            locate(physicalAim[0]-railOrigin[0],physicalAim[1]-railOrigin[1]);
            return;
        }
        // DragEvent coordinates are relative to the window that received them, which is not
        // necessarily this view's window: the catchment is a separate window. Convert through
        // screen space so the arrow tip is measured where it is actually drawn.
        View origin=plate!=null?plate:this;
        if(!origin.isAttachedToWindow()){locate(event.getX(),event.getY()-tipOffset);return;}
        origin.getLocationOnScreen(plateOrigin);getLocationOnScreen(railOrigin);
        locate(event.getX()+plateOrigin[0]-railOrigin[0],event.getY()+plateOrigin[1]-railOrigin[1]-tipOffset);
    }

    private void refreshTip(DragEvent event){
        dragHalfWidth=ContentAccessCompat.shadowHalfWidth(event);
        int next=ContentAccessCompat.arrowOffset(event);if(next==tipOffset)return;
        tipOffset=next;
        if(catchment!=null){catchment.extend(next);return;}
        if(next>0){catchment=new ShareDragCatchment(this,next,()->dragging && ready.getAsBoolean());catchment.start();}
    }
    void setWindowHover(java.util.function.Function<String,String> identity,Consumer<String> activate) {
        hoverIdentity=identity;hoverActivate=activate;windowHover.bind(identity,activate);
        if(overlay!=null)overlay.setWindowHover(identity,activate);
    }
    void setResultSink(BiConsumer<Boolean,ClipData> sink){resultSink=sink;}
    void onDeliveryResult(boolean success,ClipData retained) {
        if(!ready.getAsBoolean() || !isAttachedToWindow())return;
        panel.delivery(success,retained,panelSource);
        if(!success)message(retained!=null || panelSource?"内容已保留，可换个入口重试":"未能读取来源内容，请重新提起");
    }

    void captureMain(){if(ready.getAsBoolean() && !dragging){panel.close();capture.open();}}
    void setMainOnRight(boolean right){
        mainRight=right;
        for(int i=0;i<targets.getChildCount();i++)if(targets.getChildAt(i) instanceof ShareTargetButton)
            ((ShareTargetButton)targets.getChildAt(i)).setMainOnRight(right);
        if(overlay!=null)overlay.setMainOnRight(right);
    }

    private boolean live(int token) {
        return dragging && token == generation && user == CurrentUser.id()
                && isAttachedToWindow() && isShown() && ready.getAsBoolean();
    }

    boolean onWindowDrag(DragEvent event, String targetPackage,boolean covered) {
        return onWindowDrag(event,targetPackage,covered,getRootView());
    }

    boolean onWindowDrag(DragEvent event,String targetPackage,boolean covered,View origin) {
        if(event.getAction()==DragEvent.ACTION_DRAG_STARTED && portal!=null)portal.dragStarted();
        if(covered || overlay!=null && overlay.isOpen()) {
            if(event.getAction()==DragEvent.ACTION_DRAG_STARTED)panelSource=panel.isDragging();
            if(overlay==null){
                overlay=new ShareRailOverlay(this,ready,dispatch,hint,bounds);
                overlay.setMainOnRight(mainRight);overlay.setWindowHover(hoverIdentity,hoverActivate);
            }
            return overlay.onDrag(event,targetPackage,origin);
        }
        if (event.getAction() == DragEvent.ACTION_DRAG_STARTED)
            return drag(event,null);
        if(event.getAction()==DragEvent.ACTION_DRAG_ENDED)return drag(event,null);
        if(event.getAction()==DragEvent.ACTION_DRAG_EXITED && targetPackage==null)return drag(event,null);
        // No side-window package means the root Host window received the event. It must still
        // feed LOCATION/DROP back into this session so the arrow can select a share icon and
        // accept the DROP; returning false here was the silent share-bar cancellation.
        if(targetPackage==null)return drag(event,origin);
        int action=event.getAction();
        boolean location=action==DragEvent.ACTION_DRAG_LOCATION;
        boolean sameSource=targetPackage.equals(sourcePackage);
        boolean supported=windowPackages!=null && windowPackages.contains(targetPackage);
        if (!dragging) {
            windowHover.close();hint.accept(null);return false;
        }
        if (action==DragEvent.ACTION_DROP) {
            windowHover.close();
            if(!live(generation))return false;
            ComponentName selected=hovered;
            locateDragTip(event,origin);
            // Keep a visible selection if the final cross-window coordinate conversion misses.
            if(hovered==null)hovered=selected;
            if(hovered!=null)return drop(event,hovered);
            return !sameSource && drop(event,new ComponentName(targetPackage,""));
        }
        if(location){
            locateDragTip(event,origin);
            // The host uses this result for the small-window outline.
            if(hovered!=null){windowHover.close();return false;}
        }
        if(location && !sameSource)windowHover.move(targetPackage);
        else windowHover.close();
        if(location)hint.accept(sameSource?"松开取消":supported?"松开分享到此应用 · 停留切换窗口"
                :windowPackages==null?"正在查询分享入口 · 停留切换窗口":"无匹配分享入口 · 停留切换窗口");
        else if(action==DragEvent.ACTION_DRAG_EXITED)hint.accept(null);
        return !location || !sameSource && supported;
    }

    private void showTargets(List<ShareTarget> entries) {
        targets.removeAllViews();
        if(imageDrag && !shareOnly) {
            ShareTarget recognize=new ShareTarget(IMAGE_TEXT,"识别文字",new ShareActionIcon("recognize"),true);
            ShareTargetButton icon=new ShareTargetButton(getContext(),recognize);icon.setMainOnRight(mainRight);
            targets.addView(icon,new LinearLayout.LayoutParams(dp(QuickAppRail.CELL_WIDTH_DP),-1));
        }
        for (ShareTarget entry : entries) {
            ShareTargetButton icon = new ShareTargetButton(getContext(),entry);
            icon.setMainOnRight(mainRight);
            targets.addView(icon, new LinearLayout.LayoutParams(dp(QuickAppRail.CELL_WIDTH_DP), -1));
        }
        if (entries.isEmpty() && targets.getChildCount()==0) {
            TextView empty = new TextView(getContext());
            empty.setText("没有可用的分享入口");
            empty.setTextColor(HyperOsStyle.textPrimary(getContext()));
            targets.addView(empty);
        }
        scroll.scrollTo(0,0);
        if(shareOnly){transition.showShareOnly();setAlpha(1);}else transition.show(true);
    }

    private void locate(float x, float y) {
        hasPointer=true;
        lastX=x;lastY=y;
        updateHovered(x,y);
        float width=getWidth();
        float band=Math.min(width/3f,Math.max(dp(40),dragHalfWidth));
        float edgeX=Math.max(0,Math.min(width,x));
        edge.update(edgeX,0,width,band,android.os.SystemClock.uptimeMillis());
        if(y>=0 && y<getHeight() && x>=-dp(8) && x<=width+dp(8)
                && (edgeX<band || edgeX>width-band)) {
            if(!edgeQueued){edgeQueued=true;handler.postDelayed(edgeTick,16L);}
        } else stopEdge();
    }

    private void updateHovered(float x,float y) {
        ComponentName previous=hovered;
        hovered = null;
        if (x < 0 || y < 0 || y > getHeight()) { clearTargetHover();hint.accept(null); return; }
        pointer[0]=x-shareFace.getLeft();pointer[1]=y-shareFace.getTop();
        if(!transition.mapSharePoint(pointer)){clearTargetHover();hint.accept(null);return;}
        pointer[0]-=scroll.getLeft();pointer[1]-=scroll.getTop();
        if(pointer[1]<0 || pointer[1]>=scroll.getHeight()){clearTargetHover();hint.accept(null);return;}
        float position=pointer[0]+scroll.getScrollX();
        String unavailable=null;
        for (int i = 0; i < targets.getChildCount(); i++) {
            View view = targets.getChildAt(i);
            ShareTarget target=view.getTag() instanceof ShareTarget?(ShareTarget)view.getTag():null;
            float inset=dp(6);
            boolean inside=target!=null && ShareTargetHitPolicy.hit(position,pointer[1],view.getLeft(),view.getRight(),
                    scroll.getHeight(),inset,target.component.equals(previous)?dp(3):0);
            boolean hit=inside && target.enabled;
            if(inside && !target.enabled)unavailable=target.label+" · 不支持当前内容";
            view.setAlpha(target!=null && !target.enabled?.3f:1f);
            if(view instanceof ShareTargetButton)((ShareTargetButton)view).setDragHovered(hit);
            if(hit){hovered=target.component;hint.accept(target.label);}
        }
        if (hovered == null) hint.accept(unavailable);
        long now=android.os.SystemClock.uptimeMillis();
        if(hovered!=null && !hovered.equals(previous) && now-lastFeedback>=90){lastFeedback=now;
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);}
    }

    private void scrollEdge() {
        edgeQueued=false;
        if(!dragging || !isShown())return;
        float width=getWidth();
        float band=Math.min(width/3f,Math.max(dp(40),dragHalfWidth));
        float edgeX=Math.max(0,Math.min(width,lastX));
        int step=edge.step(edgeX,0,width,band,android.os.SystemClock.uptimeMillis());
        if(step!=0){
            int before=scroll.getScrollX();
            scroll.scrollBy(dp(step),0);
            if(scroll.getScrollX()==before){edge.reset();return;}
        }
        edgeQueued=true;handler.postDelayed(edgeTick,16L);
    }

    private void stopEdge(){handler.removeCallbacks(edgeTick);edgeQueued=false;edge.reset();}

    private boolean drop(DragEvent event, ComponentName target) {
        if(dropped)return false;
        try {
            SharePayload payload=new SharePayload(event.getClipData());transferClip=payload.clip;
            if(IMAGE_TEXT.equals(target))return recognizeDrop(event,payload);
            Intent intent=ShareRoutes.intent(payload,target);
            ShareDiagnostics.event(ShareDiagnostics.Stage.DELIVERY,ShareDiagnostics.Code.ACCEPTED);
            // The target catalog already filtered this enabled icon. Re-querying here made a
            // transient PackageManager miss return false and silently cancel an accepted DROP.
            // Provider preparation still resolves the receiver and reports failures.
            int token=generation,owner=user;deliveryPending=true;
            receipt=new ShareDelivery(getContext(),(success,retained)->{
                if(token!=generation || owner!=CurrentUser.id())return;
                finishDelivery(token);
                if(success && ShareActions.STAGE.equals(ShareActions.operation(target)))message("已暂存");
                resultSink.accept(success,retained);
            });
            handler.removeCallbacks(expire);handler.postDelayed(expire,60_000L);
            if(payload.uris.isEmpty() || payload.uris.stream().allMatch(uri->ContentInboxProvider.AUTHORITY.equals(uri.getAuthority()))) {
                dropped=true;
                if(ShareActions.STAGE.equals(ShareActions.operation(target)))stageOwned(payload.clip,token,owner);
                else prepareDelivery(target,intent,null,token,owner);
                return true;
            }
            ClipData files=new ClipData("文件",new String[]{payload.mime},new ClipData.Item(payload.uris.get(0)));
            for(int i=1;i<payload.uris.size();i++)files.addItem(new ClipData.Item(payload.uris.get(i)));
            delegatedRead=ContentAccessCompat.delegate(event,files);dropped=true;
            prepareDelivery(target,intent,delegatedRead,token,owner);return true;
        }catch(Exception unavailable){
            ShareDiagnostics.event(ShareDiagnostics.Stage.DELIVERY,ShareDiagnostics.Code.REJECTED);
            dropped=false;finishDelivery(generation);message("无法交付内容，原内容保留");return false;
        }
    }
    private void stageOwned(ClipData clip,int token,int owner) {
        WORKER.execute(()->{
            if(token!=generation || owner!=CurrentUser.id())return;
            try {
                android.os.Bundle data=new android.os.Bundle();data.putParcelable("clip",clip);
                if(getContext().getContentResolver().call(ContentInboxProvider.ROOT,"shelf_add",null,data)==null)
                    throw new IllegalStateException("Missing shelf confirmation");
                handler.post(()->{
                    if(token!=generation || owner!=CurrentUser.id())return;
                    finishDelivery(token);
                    message("已暂存");
                    resultSink.accept(true,clip);
                });
            }catch(RuntimeException unavailable){handler.post(()->failDelivery(token,owner));}
        });
    }

    private void prepareDelivery(ComponentName target,Intent intent,DropPermissionContract grant,int token,int owner) {
        android.os.IBinder callback=receipt.token;
        WORKER.execute(()->{
            try {
                android.os.Bundle data=new android.os.Bundle();data.putParcelable("intent",intent);data.putBinder("receipt",callback);
                data.putString("operation",ShareActions.operation(target));if(grant!=null)data.putBinder("grant",grant.token);
                android.os.Bundle reply=getContext().getContentResolver().call(ContentInboxProvider.ROOT,
                        grant==null?"prepare_share":"prepare_external",null,data);
                String capability=reply==null?null:reply.getString("token");if(capability==null)throw new IllegalStateException("Missing delivery capability");
                ShareDiagnostics.event(ShareDiagnostics.Stage.DELIVERY,ShareDiagnostics.Code.READY);
                handler.post(()->startDelivery(target,capability,grant,token,owner));
            }catch(RuntimeException unavailable){if(grant!=null)grant.close();handler.post(()->failDelivery(token,owner));}
        });
    }
    private void startDelivery(ComponentName target,String capability,DropPermissionContract grant,int token,int owner) {
        if(token!=generation || owner!=CurrentUser.id()){if(grant!=null)grant.close();return;}
        if(!ready.getAsBoolean()){failDelivery(token,owner);return;}
        Runnable launch=()->{
            if(token!=generation || owner!=CurrentUser.id()){if(grant!=null)grant.close();return;}
            if(!ready.getAsBoolean()){failDelivery(token,owner);return;}
            try{
                Intent proxy=new Intent().setComponent(new ComponentName(ContentInboxProvider.MODULE,
                        ContentImportActivity.class.getName())).putExtra("launch_token",capability)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                if(ShareActions.STAGE.equals(ShareActions.operation(target)))proxy.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                getContext().startActivity(proxy);
                ShareDiagnostics.event(ShareDiagnostics.Stage.DELIVERY,ShareDiagnostics.Code.STARTED);
                if(delegatedRead==grant)delegatedRead=null;
            }catch(RuntimeException unavailable){if(grant!=null)grant.close();failDelivery(token,owner);}
        };
        try{if(ShareActions.operation(target).isEmpty())dispatch.accept(ShareRoutes.activity(target),launch);else launch.run();}
        catch(RuntimeException unavailable){failDelivery(token,owner);}
    }
    private void failDelivery(int token,int owner) {
        if(token!=generation || owner!=CurrentUser.id())return;
        ShareDiagnostics.event(ShareDiagnostics.Stage.DELIVERY,ShareDiagnostics.Code.FAILED);
        ClipData retained=transferClip;
        try{if(retained!=null && new SharePayload(retained).uris.stream().anyMatch(uri->!ContentInboxProvider.AUTHORITY.equals(uri.getAuthority())))retained=null;}
        catch(RuntimeException unavailable){retained=null;}
        finishDelivery(token);resultSink.accept(false,retained);
    }

    private boolean recognizeDrop(DragEvent event,SharePayload payload) {
        if(payload.uris.size()!=1 || !payload.mime.startsWith("image/"))return false;
        android.net.Uri uri=payload.uris.get(0);
        ShareReadLease read=new ShareReadLease(ContentInboxProvider.AUTHORITY.equals(uri.getAuthority())?()->{}:ContentAccessCompat.take(event));
        pendingRead=read;dropped=true;int token=generation,owner=user;
        afterDrag=()->{
            if(token!=generation || owner!=CurrentUser.id() || !isAttachedToWindow() || !ready.getAsBoolean()){read.close();return;}
            try{panel.open();panel.recognizeImage(uri,read);if(pendingRead==read)pendingRead=null;}
            catch(RuntimeException unavailable){read.close();panel.close();message("无法打开图片识字");}
        };
        return true;
    }

    private Runnable dragFinished=()->{};
    void setDragFinished(Runnable finished){dragFinished=finished;}

    private void finishVisuals() {
        if(catchment!=null){catchment.close();catchment=null;}
        windowHover.close();
        windowPackages=null;
        hasPointer=false;
        dragging = false;
        hovered = null;
        clearTargetHover();
        hint.accept(null);
        stopEdge();if(shareOnly){transition.reset();setAlpha(0);}else transition.show(false);
        if (!dropped) { handler.removeCallbacks(expire); generation++; }
    }

    private void clearTargetHover() {
        for(int i=0;i<targets.getChildCount();i++) {
            View view=targets.getChildAt(i);
            if(view instanceof ShareTargetButton)((ShareTargetButton)view).setDragHovered(false);
        }
    }

    void cancel() {
        generation++;
        if(receipt!=null){receipt.close();receipt=null;}transferClip=null;
        afterDrag=()->{};
        handler.removeCallbacks(expire);
        if (pendingRead != null) { pendingRead.close(); pendingRead = null; }
        if(delegatedRead!=null){delegatedRead.close();delegatedRead=null;}
        dropped = false;
        if(dragging)panel.dragEnded(false);
        deliveryPending=false;retainingDelivery=false;
        Runnable settled=deliverySettled;deliverySettled=()->{};settled.run();
        finishVisuals();
        transition.reset();
    }

    void close() {
        if(overlay!=null)overlay.close();
        cancel(); panel.close();history.stop();
        capture.close();
        if(portal!=null)portal.close();
    }

    void setFeatureEnabled(boolean enabled) {
        transition.setPrefixEnabled(enabled);
        if(normalRail instanceof QuickAppRail) {
            ((QuickAppRail)normalRail).setContentCell(enabled?dp(46):0,content::performClick,
                    offset->content.setTranslationX(-offset));
        }
    }

    private boolean workspaceActive;

    void setActive(boolean active) {
        workspaceActive=active;
        if(active)startObservers();
        else close();
    }

    private void startObservers() {
        history.start();
        if(portal!=null)portal.start();
    }

    private void restoreObservers() {
        if(workspaceActive && isAttachedToWindow() && isShown() && getWindowVisibility()==VISIBLE)startObservers();
    }
    void setCoveredSource(BooleanSupplier covered){this.covered=covered;}

    boolean retainDelivery(Runnable settled) {
        retainingDelivery=deliveryPending;deliverySettled=settled;return retainingDelivery;
    }

    private void finishDelivery(int token) {
        if(token!=generation)return;
        deliveryPending=false;retainingDelivery=false;handler.removeCallbacks(expire);
        transferClip=null;
        if(receipt!=null){receipt.close();receipt=null;}
        if(delegatedRead!=null){delegatedRead.close();delegatedRead=null;}
        Runnable settled=deliverySettled;deliverySettled=()->{};settled.run();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restoreObservers();
    }
    @Override protected void onDetachedFromWindow() {
        if(retainingDelivery){finishVisuals();transition.reset();panel.close();capture.close();if(portal!=null)portal.close();history.stop();}
        else close();super.onDetachedFromWindow();
    }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (oldW > 0 && (w != oldW || h != oldH)) {
            close();
            post(this::restoreObservers);
        }
    }
    @Override protected void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (panel != null) {
            close();
            post(this::restoreObservers);
        }
    }
    @Override protected void onVisibilityChanged(View view, int visibility) {
        super.onVisibilityChanged(view, visibility);
        if (panel != null && !isShown() && !retainingDelivery) close();
        else if(history!=null)restoreObservers();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if(panel==null)return;
        if(visibility==VISIBLE)restoreObservers();
        else if(!retainingDelivery && !deliveryPending)close();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void message(String text) { android.widget.Toast.makeText(getContext(), text, android.widget.Toast.LENGTH_SHORT).show(); }
}
