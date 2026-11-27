package io.github.zerohomers0.onesteplite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Transparent video carrier above the original header; native ownership stays in VideoDock. */
final class VideoPane extends FrameLayout implements SurfaceHolder.Callback {
    private final SurfaceView video;
    private final LinearLayout controls;
    private final IconButton toggle;
    private final IconButton fit;
    private final PopupWindow popup;
    private final int controlSize, controlsWidth;
    private final Handler handler;
    private final BooleanSupplier allowed;
    private final Consumer<Boolean> changed;
    private VideoHostLease lease=new VideoHostLease();
    private final PendingIntent identity;
    private SurfaceControl carrier;
    private ValueAnimator animator;
    private long source,sequence;
    private float opacity;
    private boolean embedded,registered,dismissing,surfaceRetiring;
    private boolean fill;
    private float touchX,touchY;
    private String lastPublished="";
    private final Runnable releaseTimeout=this::releaseForPrivacy;
    private final BroadcastReceiver receiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {receive(intent);}
    };

    VideoPane(Context context,Handler handler,BooleanSupplier allowed,Consumer<Boolean> changed) {
        super(context);this.handler=handler;this.allowed=allowed;this.changed=changed;
        // SurfaceView's native Z order does not order this View's background or
        // input region. Keep the whole carrier above the header, even when the
        // header calls bringToFront during layout or workspace transitions.
        setTranslationZ(1f);
        identity=PendingIntent.getBroadcast(context,9301,new Intent(VideoDock.HOST).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        video=new SurfaceView(context);video.setZOrderOnTop(true);
        video.getHolder().setFormat(PixelFormat.TRANSLUCENT);video.getHolder().addCallback(this);
        video.setContentDescription("顶栏视频，点按显示控制按钮");
        video.setOnClickListener(view->showControls());
        video.setClickable(false);
        video.setOnTouchListener((view,event)->{
            if(!embedded && !dismissing)return false;
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN) {touchX=event.getX();touchY=event.getY();}
            if(event.getActionMasked()==MotionEvent.ACTION_UP && !dismissing
                    && Math.hypot(event.getX()-touchX,event.getY()-touchY)<dp(12))view.performClick();
            return true;
        });
        addView(video,new FrameLayout.LayoutParams(-1,-1));
        controls=new LinearLayout(context);controls.setGravity(Gravity.CENTER);
        controls.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        controlSize=dp(IconButton.TOP_CONTROL_TOUCH_DP);
        int gap=dp(8);
        toggle=addControl(IconButton.PLAY,"播放视频",()->{command("toggle");showControls();},gap);
        fit=addControl(IconButton.VIDEO_FILL,"填满顶栏",this::toggleFit,gap);
        addControl(IconButton.VIDEO_RETURN,"退出顶栏，返回播放器小窗",this::dismiss,0);
        controlsWidth=3*controlSize+2*gap;
        popup=new PopupWindow(controls,controlsWidth,controlSize,false);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(false);popup.setTouchModal(false);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        setBackgroundColor(Color.TRANSPARENT);
    }

    boolean embedded() {return embedded;}

    private IconButton addControl(int icon,String text,Runnable action,int rightMargin) {
        IconButton button=IconButton.topControl(getContext(),icon,text,action);
        LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(controlSize,controlSize);
        bounds.rightMargin=rightMargin;
        controls.addView(button,bounds);
        return button;
    }

    private int dp(float value) {return Math.round(value*getResources().getDisplayMetrics().density);}

    private void toggleFit() {
        if(dismissing)return;
        fill=!fill;fit.setIcon(fill?IconButton.VIDEO_FIT:IconButton.VIDEO_FILL);
        String text=fill?"完整显示视频":"填满顶栏";fit.setContentDescription(text);fit.setTooltipText(text);
        command(fill?"fill":"fit");showControls();
    }

    private void receive(Intent intent) {
        Bundle binding=intent.getBundleExtra("identity");
        if(binding==null)return;
        VideoHostLease.receiveRetired(binding,handler);
        if(!lease.owner.equals(binding.getBinder("owner")))return;
        long next=intent.getLongExtra("source",0);
        if(next<source)return;
        lease.releaseToken=binding.getBinder("release");
        boolean showing=intent.getBooleanExtra("embedded",false);
        if(showing)lease.published=true;
        if(showing && (surfaceRetiring || !isShown() || !allowed.getAsBoolean()
                || !EntryGuard.canOpen(getContext()))) {releaseForPrivacy();return;}
        boolean playing=intent.getBooleanExtra("playing",false),controllable=intent.getBooleanExtra("controllable",false);
        toggle.setIcon(playing?IconButton.PAUSE:IconButton.PLAY);
        toggle.setContentDescription(playing?"暂停视频":"播放视频");
        toggle.setTooltipText(playing?"暂停视频":"播放视频");
        toggle.setEnabled(controllable);toggle.setAlpha(controllable?1f:.32f);
        if(showing && embedded && source==next)return;
        source=next;handler.removeCallbacks(releaseTimeout);
        stopAnimation();dismissing=false;embedded=showing;
        video.setClickable(showing);
        if(showing) {
            controls.setEnabled(true);showControls();animateOpacity(0f,1f,null);
        } else {setOpacity(0f);popup.dismiss();lease.published=false;}
        changed.accept(showing);
    }

    private void showControls() {

        if(!isAttachedToWindow() || !embedded || dismissing)return;
        if(popup.isShowing())return;
        int[] origin=new int[2];getLocationOnScreen(origin);controls.setAlpha(opacity);
        int margin=dp(IconButton.TOP_CONTROL_MARGIN_DP);
        try {popup.showAtLocation(this,IconButton.TOP_CONTROL_GRAVITY,
                origin[0]+Math.max(0,getWidth()-controlsWidth-margin),
                origin[1]+Math.max(0,getHeight()-controlSize-margin));}
        catch(RuntimeException unavailable) {popup.dismiss();}
    }


    private void dismiss() {
        if(!embedded || dismissing)return;

        dismissing=true;controls.setEnabled(false);
        animateOpacity(opacity,0f,()->{
            popup.dismiss();command("release");handler.postDelayed(releaseTimeout,1500L);
        });
    }

    private void animateOpacity(float from,float to,Runnable end) {
        stopAnimation();ValueAnimator next=ValueAnimator.ofFloat(from,to);animator=next;
        next.setDuration(OverlayMotionSpec.TOP_FADE_MS);
        next.addUpdateListener(value->{if(animator==next)setOpacity((Float)value.getAnimatedValue());});
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if(animator!=animation)return;
                animator=null;if(end!=null)end.run();
            }
        });
        next.start();
    }

    private void stopAnimation() {ValueAnimator old=animator;animator=null;if(old!=null)old.cancel();}

    private void setOpacity(float value) {
        opacity=Math.max(0f,Math.min(1f,value));controls.setAlpha(opacity);video.setAlpha(opacity);
        // Native overlays can include recommendation rows and change aspect ratio
        // while playing. Keep the entire player fitted; its unused area belongs
        // to the video presentation, not to the clock and application rail below.
        setBackgroundColor(Math.round(opacity*255f)<<24);
        if(carrier==null || !carrier.isValid())return;
        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()) {tx.setAlpha(carrier,opacity).apply();}
        catch(RuntimeException failure) {android.util.Log.w(Tags.PROBE,"video opacity unavailable",failure);}
    }

    void refresh() {
        if(!surfaceRetiring && carrier==null && isShown() && isAttachedToWindow()) {
            try {
                SurfaceControl parent=(SurfaceControl)XposedHelpers.callMethod(video,"getSurfaceControl");
                if(parent!=null && parent.isValid()) {lease.create(parent);carrier=lease.surface;}
            } catch(RuntimeException unavailable) {return;}
        }
        publish(!surfaceRetiring && allowed.getAsBoolean() && isShown() && EntryGuard.canOpen(getContext()));
    }

    private void publish(boolean active) {
        active=active && carrier!=null && carrier.isValid();
        String key=System.identityHashCode(carrier)+":"+video.getWidth()+":"+video.getHeight()+":"+active;
        if(key.equals(lastPublished))return;
        lastPublished=key;Bundle binding=new Bundle();binding.putBinder("owner",lease.owner);
        if(active)lease.published=true;
        getContext().sendBroadcast(new Intent(VideoDock.HOST).setPackage("android").putExtras(binding)
                .putExtra("identity",identity).putExtra("sequence",++sequence).putExtra("surface",carrier)
                .putExtra("fill",fill)
                .putExtra("width",video.getWidth()).putExtra("height",video.getHeight()).putExtra("active",active),
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    private void command(String action) {
        if(dismissing && !"release".equals(action))return;
        Bundle binding=new Bundle();binding.putBinder("owner",lease.owner);
        getContext().sendBroadcast(new Intent(VideoDock.COMMAND).setPackage("android").putExtras(binding)
                .putExtra("identity",identity).putExtra("source",source).putExtra("command",action),
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    /** Return the native child before privacy hiding or destruction of its temporary parent. */
    void releaseForPrivacy() {
        stopAnimation();handler.removeCallbacks(releaseTimeout);popup.dismiss();setOpacity(0f);
        publish(false);
        if(!lease.returnChild()) {
            java.lang.ref.WeakReference<VideoPane> retained=new java.lang.ref.WeakReference<>(this);
            lease.retire(handler,()->{
                VideoPane pane=retained.get();
                if(pane!=null && pane.isAttachedToWindow()) {pane.lastPublished="";pane.post(pane::refresh);}
            });
            lease=new VideoHostLease();carrier=null;
            source=sequence=0;lastPublished="";
        }
        boolean wasEmbedded=embedded;embedded=false;dismissing=false;
        video.setClickable(false);
        if(wasEmbedded)changed.accept(false);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceRetiring=false;
        try {
            android.graphics.Canvas canvas=holder.lockCanvas();
            if(canvas!=null) {canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);holder.unlockCanvasAndPost(canvas);}
            lease.create((SurfaceControl)XposedHelpers.callMethod(video,"getSurfaceControl"));
            carrier=lease.surface;
        } catch(RuntimeException unavailable) {carrier=null;}
        setOpacity(0f);refresh();
    }

    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) {refresh();}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceRetiring=true;
        releaseForPrivacy();lease.retire(handler);lease=new VideoHostLease();carrier=null;lastPublished="";
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if(!registered) {
            getContext().registerReceiver(receiver,new IntentFilter(VideoDock.STATE),android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    handler,Context.RECEIVER_EXPORTED);registered=true;
        }
        post(this::refresh);
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if(video==null)return;
        if(visibility!=VISIBLE)releaseForPrivacy();else post(this::refresh);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        super.onSizeChanged(w,h,oldw,oldh);
        if(popup!=null)popup.dismiss();post(()->{refresh();if(embedded)showControls();});
    }
    @Override protected void onDetachedFromWindow() {
        surfaceRetiring=true;
        releaseForPrivacy();
        if(registered) {getContext().unregisterReceiver(receiver);registered=false;}
        super.onDetachedFromWindow();
    }
}
