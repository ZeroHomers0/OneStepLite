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
    private final ShareTargetPicker picker=new ShareTargetPicker();
    private final ShareEdgePolicy edge=new ShareEdgePolicy();
    private final ShareRailTransition transition;
    private final float[] pointer=new float[2];
    private float lastX,lastY;
    private boolean edgeQueued;
    private final Runnable edgeTick=this::scrollEdge;
    private boolean dragging, dropped;
    private int generation, user;
    private ComponentName hovered;
    private ShareReadLease pendingRead;
    private DropPermissionContract delegatedRead;
    private final Runnable expire = this::cancel;

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
        this.normalRail = normalRail;
        this.ready = ready;
        this.dispatch = dispatch;
        this.hint = hint;
        history=new ClipboardHistory(context);
        capture=new SourceCaptureController(this,()->ready.getAsBoolean() && !dragging,bounds);
        panel = new ContentPanel(this, bounds,history,()->capture.open());
        content = new IconButton(context, IconButton.CONTENT, "内容：近期图片、文件和剪贴板");
        content.setTooltipText("近期图片、文件和剪贴板");
        content.setOnClickListener(v -> {
            if (!ready.getAsBoolean() || dragging) return;
            try { panel.open(); }
            catch (RuntimeException unavailable) {
                panel.close(); message("暂时无法打开内容面板");
                android.util.Log.w(Tags.PROBE, "content panel unavailable", unavailable);
            }
        });
        addView(content, new LayoutParams(dp(50), -1));
        shareFace=new FrameLayout(context);shareFace.setBackgroundColor(HyperOsStyle.shareRailFill(context));
        shareFace.setClipChildren(false);shareFace.setClipToPadding(false);
        addView(shareFace,new LayoutParams(-1,-1));
        scroll = new HorizontalScrollView(context);
        scroll.setClipChildren(false);scroll.setClipToPadding(false);
        scroll.setHorizontalScrollBarEnabled(false);
        targets = new LinearLayout(context);targets.setClipChildren(false);targets.setClipToPadding(false);
        scroll.addView(targets, new FrameLayout.LayoutParams(-2, -1));
        LayoutParams row = new LayoutParams(-1, -1);
        shareFace.addView(scroll, row);
        shareFace.setVisibility(INVISIBLE);
        transition=new ShareRailTransition(normalRail,shareFace,content);
        setOnDragListener((v, event) -> drag(event));
        if(shareOnly)setAlpha(0);
    }

    private boolean drag(DragEvent event) {
        if(!shareOnly && (covered.getAsBoolean() || overlay!=null && overlay.isOpen()))
            return onWindowDrag(event,null,true);
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                if (!ready.getAsBoolean() || !ContentAccessCompat.currentProcessUser()
                        || event.getLocalState()!=null && !(event.getLocalState() instanceof SharePayload)
                        || !accepts(event.getClipDescription())) return false;
                if(dragging)return true;
                cancel();
                dragging = true;
                user = CurrentUser.id();
                int token = generation;
                SharePayload local=event.getLocalState() instanceof SharePayload
                        ?(SharePayload)event.getLocalState():null;
                ClipDescription description = local==null?new ClipDescription(event.getClipDescription())
                        :new ClipDescription("内容",new String[]{local.mime});
                int count=local==null?0:Math.max(1,local.uris.size());
                handler.postDelayed(expire, 60_000L);
                WORKER.execute(() -> {
                    try {
                        List<ShareTarget> entries=ShareTargetCatalog.load(getContext(),description,count,false);
                        post(()->{if(live(token))showTargets(entries);});
                    } catch(RuntimeException unavailable) {
                        post(()->{if(live(token))message("无法查询分享入口");});
                    }
                });
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                if (dragging) locate(event.getX(), event.getY());
                return dragging;
            case DragEvent.ACTION_DRAG_EXITED:
                stopEdge();hovered = null; clearTargetHover();hint.accept(null); return dragging;
            case DragEvent.ACTION_DROP:
                if (!live(generation)) return false;
                locate(event.getX(), event.getY());
                if (hovered == null) return false;
                return drop(event, hovered);
            case DragEvent.ACTION_DRAG_ENDED:
                if(!dragging)return true;
                boolean consumed = event.getResult();
                finishVisuals();
                panel.dragEnded(consumed);
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

    private boolean live(int token) {
        return dragging && token == generation && user == CurrentUser.id()
                && isAttachedToWindow() && isShown() && ready.getAsBoolean();
    }

    boolean onWindowDrag(DragEvent event, String targetPackage,boolean covered) {
        if(covered || overlay!=null && overlay.isOpen()) {
            if(overlay==null)overlay=new ShareRailOverlay(this,ready,dispatch,hint,bounds);
            return overlay.onDrag(event,targetPackage);
        }
        if (event.getAction() == DragEvent.ACTION_DRAG_STARTED)
            return drag(event);
        if(event.getAction()==DragEvent.ACTION_DRAG_ENDED)return drag(event);
        if (!dragging || targetPackage == null) return false;
        if (event.getAction() == DragEvent.ACTION_DROP)
            return live(generation) && drop(event, new ComponentName(targetPackage, ""));
        if (event.getAction() == DragEvent.ACTION_DRAG_LOCATION) hint.accept("松开分享到 " + targetPackage);
        return true;
    }

    private void showTargets(List<ShareTarget> entries) {
        targets.removeAllViews();
        for (ShareTarget entry : entries) {
            ShareTargetButton icon = new ShareTargetButton(getContext(),entry);
            targets.addView(icon, new LinearLayout.LayoutParams(dp(50), -1));
        }
        if (entries.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("没有可用的分享入口");
            empty.setTextColor(HyperOsStyle.textPrimary(getContext()));
            targets.addView(empty);
        }
        scroll.scrollTo(0,0);
        if(shareOnly){transition.showShareOnly();setAlpha(1);}else transition.show(true);
    }

    private void locate(float x, float y) {
        lastX=x;lastY=y;
        updateHovered(x,y);
        edge.update(x,0,getWidth(),dp(24),android.os.SystemClock.uptimeMillis());
        if(x>=0 && (x<dp(24) || x>getWidth()-dp(24))) {
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
        for (int i = 0; i < targets.getChildCount(); i++) {
            View view = targets.getChildAt(i);
            ShareTarget target=view.getTag() instanceof ShareTarget?(ShareTarget)view.getTag():null;
            boolean hit=target!=null && target.enabled && position>=view.getLeft() && position<view.getRight();
            view.setAlpha(target!=null && !target.enabled?.3f:1f);
            if(view instanceof ShareTargetButton)((ShareTargetButton)view).setDragHovered(hit);
            if(hit){hovered=target.component;hint.accept(target.label);}
        }
        if (hovered == null) hint.accept(null);
        if(hovered!=null && !hovered.equals(previous))performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    private void scrollEdge() {
        edgeQueued=false;
        if(!dragging || !isShown())return;
        int direction=edge.update(lastX,0,getWidth(),dp(24),android.os.SystemClock.uptimeMillis());
        if(direction!=0){scroll.scrollBy(direction*dp(4),0);updateHovered(lastX,lastY);}
        edgeQueued=true;handler.postDelayed(edgeTick,16L);
    }

    private void stopEdge(){handler.removeCallbacks(edgeTick);edgeQueued=false;edge.reset();}

    private boolean drop(DragEvent event, ComponentName target) {
        if (dropped) return false;
        ShareReadLease readLease = null;
        try {
            SharePayload payload = new SharePayload(event.getClipData());
            Intent intent = payload.intent(target);
            if (getContext().getPackageManager().queryIntentActivities(intent, 0).isEmpty())
                throw new IllegalArgumentException("Target does not accept this content count");
            deliveryPending=true;handler.removeCallbacks(expire);handler.postDelayed(expire,60_000L);
            if(!payload.uris.isEmpty() && payload.uris.stream().allMatch(uri->ContentInboxProvider.AUTHORITY.equals(uri.getAuthority()))) {
                dropped=true;launchInbox(target,intent,generation,user);return true;
            }
            if (!payload.uris.isEmpty()) {
                ClipData files=new ClipData("文件",new String[]{payload.mime},new ClipData.Item(payload.uris.get(0)));
                for(int i=1;i<payload.uris.size();i++)files.addItem(new ClipData.Item(payload.uris.get(i)));
                delegatedRead=ContentAccessCompat.delegate(event,files);
                dropped=true;
                launchExternal(target,intent,delegatedRead,generation,user);return true;
            }
            dropped = true;
            final int owner = user;
            final int token = generation;
            final ShareReadLease lease = readLease;
            dispatch.accept(target, () -> {
                try {
                    if (token != generation || owner != CurrentUser.id() || !ready.getAsBoolean()
                        || lease != null && !lease.isOpen()) {
                        if (lease != null) lease.close();return;
                    }
                    getContext().startActivity(intent);
                }
                catch (RuntimeException failure) { message("无法打开分享入口或交付读取权限"); }
                finally {
                    if (lease != null) lease.close();
                    if (pendingRead == lease) pendingRead = null;
                    finishDelivery(token);
                }
            });
            return true;
        } catch (Exception error) {
            if (readLease != null) readLease.close();
            dropped=false;
            finishDelivery(generation);
            message("无法交付内容或读取权限，请尝试应用原有分享");
            android.util.Log.w(Tags.PROBE, "content drop rejected", error);
            return false;
        }
    }

    private void launchExternal(ComponentName target,Intent share,DropPermissionContract grant,int token,int owner) {
        WORKER.execute(()->{
            try {
                android.os.Bundle data=new android.os.Bundle();data.putParcelable("intent",share);data.putBinder("grant",grant.token);
                android.os.Bundle reply=getContext().getContentResolver().call(ContentInboxProvider.ROOT,"prepare_external",null,data);
                String capability=reply==null?null:reply.getString("token");
                if(capability==null)throw new SecurityException("No delegated launch");
                handler.post(()->{
                    if(token!=generation || owner!=CurrentUser.id() || !ready.getAsBoolean()){grant.close();finishDelivery(token);return;}
                    try{dispatch.accept(target,()->{
                        try {
                            if(token!=generation || owner!=CurrentUser.id() || !ready.getAsBoolean()){grant.close();return;}
                            getContext().startActivity(new Intent().setComponent(new ComponentName(ContentInboxProvider.MODULE,
                                    ContentImportActivity.class.getName())).putExtra("launch_token",capability).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            if(delegatedRead==grant)delegatedRead=null;
                        } catch(RuntimeException denied){grant.close();message("无法交接外部内容");}
                        finally{finishDelivery(token);}
                    });}catch(RuntimeException rejected){grant.close();finishDelivery(token);message("目标窗口暂时无法分享");}
                });
            } catch(RuntimeException failure) {
                android.util.Log.w(Tags.PROBE,"external drop preparation failed",failure);
                grant.close();handler.post(()->{if(token==generation)message("外部内容权限交接失败");finishDelivery(token);});
            }
        });
    }

    private void launchInbox(ComponentName target,Intent share,int token,int owner) {
        WORKER.execute(()->{
            try {
                android.os.Bundle input=new android.os.Bundle();input.putParcelable("intent",share);
                android.os.Bundle reply=getContext().getContentResolver().call(ContentInboxProvider.ROOT,"prepare_share",null,input);
                String capability=reply==null?null:reply.getString("token");
                if(capability==null)throw new IllegalStateException("No inbox launch capability");
                handler.post(()->{
                    if(token!=generation || owner!=CurrentUser.id() || !ready.getAsBoolean()){finishDelivery(token);return;}
                    try{dispatch.accept(target,()->{
                        try {
                            if(token!=generation || owner!=CurrentUser.id() || !ready.getAsBoolean())return;
                            Intent proxy=new Intent().setComponent(new ComponentName(ContentInboxProvider.MODULE,
                                    ContentImportActivity.class.getName())).putExtra("launch_token",capability)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getContext().startActivity(proxy);
                        } catch(RuntimeException blocked){message("无法打开内容的分享入口");}
                        finally{finishDelivery(token);}
                    });}catch(RuntimeException rejected){finishDelivery(token);message("目标窗口暂时无法分享");}
                });
            } catch(RuntimeException failure) {
                handler.post(()->{if(token==generation && owner==CurrentUser.id())message("内容已失效或无法分享");finishDelivery(token);});
            }
        });
    }

    void editTargets() {
        if (!ready.getAsBoolean()) return;
        final int owner = CurrentUser.id();
        final int token = generation;
        ClipDescription types = new ClipDescription("分享", new String[]{"text/plain", "image/*", "*/*"});
        WORKER.execute(() -> {
            try {
                List<ShareTarget> entries=ShareTargetCatalog.load(getContext(),types,0,true);
                post(() -> {
                    if (token != generation || owner != CurrentUser.id()
                            || !ready.getAsBoolean() || !isShown() || picker.isOpen()) return;
                    try { picker.show(this,entries,owner); }
                    catch (RuntimeException unavailable) {
                        picker.close();
                        message("无法打开分享入口选择框");
                    }
                });
            } catch (RuntimeException unavailable) {
                post(() -> { if (token == generation && isShown()) message("无法查询分享入口"); });
            }
        });
    }

    private void finishVisuals() {
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
        handler.removeCallbacks(expire);
        if (pendingRead != null) { pendingRead.close(); pendingRead = null; }
        if(delegatedRead!=null){delegatedRead.close();delegatedRead=null;}
        dropped = false;
        deliveryPending=false;retainingDelivery=false;
        Runnable settled=deliverySettled;deliverySettled=()->{};settled.run();
        finishVisuals();
        transition.reset();
    }

    void close() {
        if(overlay!=null)overlay.close();
        cancel(); panel.close();history.stop();
        capture.close();
        picker.close();
    }

    void setActive(boolean active) { if(active)history.start();else close(); }
    void setCoveredSource(BooleanSupplier covered){this.covered=covered;}

    boolean retainDelivery(Runnable settled) {
        retainingDelivery=deliveryPending;deliverySettled=settled;return retainingDelivery;
    }

    private void finishDelivery(int token) {
        if(token!=generation)return;
        deliveryPending=false;retainingDelivery=false;handler.removeCallbacks(expire);
        if(delegatedRead!=null){delegatedRead.close();delegatedRead=null;}
        Runnable settled=deliverySettled;deliverySettled=()->{};settled.run();
    }

    @Override protected void onDetachedFromWindow() {
        if(retainingDelivery){stopEdge();panel.close();capture.close();history.stop();picker.close();}
        else close();super.onDetachedFromWindow();
    }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (oldW > 0 && (w != oldW || h != oldH)) close();
    }
    @Override protected void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (panel != null) close();
    }
    @Override protected void onVisibilityChanged(View view, int visibility) {
        super.onVisibilityChanged(view, visibility);
        if (panel != null && !isShown() && !retainingDelivery) close();
        else if(history!=null && ready.getAsBoolean())history.start();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void message(String text) { android.widget.Toast.makeText(getContext(), text, android.widget.Toast.LENGTH_SHORT).show(); }
}
