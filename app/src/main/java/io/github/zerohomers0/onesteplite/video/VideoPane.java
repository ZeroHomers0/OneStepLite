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
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Gravity;
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
    private final PopupWindow popup;
    private final Handler handler;
    private final BooleanSupplier allowed;
    private final Consumer<Boolean> changed;
    private final Binder owner=new Binder();
    private final PendingIntent identity;
    private SurfaceControl carrier;
    private IBinder releaseToken;
    private ValueAnimator animator;
    private long source,sequence;
    private float opacity;
    private boolean embedded,registered,dismissing;
    private String lastPublished="";
    private final Runnable releaseTimeout=this::releaseForPrivacy;
    private final BroadcastReceiver receiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {receive(intent);}
    };

    VideoPane(Context context,Handler handler,BooleanSupplier allowed,Consumer<Boolean> changed) {
        super(context);this.handler=handler;this.allowed=allowed;this.changed=changed;
        identity=PendingIntent.getBroadcast(context,9301,new Intent(VideoDock.HOST).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        video=new SurfaceView(context);video.setZOrderOnTop(true);
        video.getHolder().setFormat(PixelFormat.TRANSLUCENT);video.getHolder().addCallback(this);
        video.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        video.setOnTouchListener((view,event)->embedded || dismissing);
        addView(video,new FrameLayout.LayoutParams(-1,-1));
        controls=new LinearLayout(context);controls.setGravity(Gravity.CENTER);
        toggle=button(IconButton.PLAY,"播放视频",()->command("toggle"));
        controls.addView(toggle,new LinearLayout.LayoutParams(dp(40),dp(40)));
        controls.addView(button(IconButton.VIDEO_RETURN,"退出顶栏，返回播放器小窗",this::dismiss),
                new LinearLayout.LayoutParams(dp(40),dp(40)));
        popup=new PopupWindow(controls,dp(80),dp(40),false);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(false);popup.setTouchModal(false);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        setBackgroundColor(Color.TRANSPARENT);
    }

    boolean embedded() {return embedded;}

    private IconButton button(int icon,String text,Runnable action) {
        IconButton button=new IconButton(getContext(),icon,text);button.setTooltipText(text);
        button.setBackground(HyperOsStyle.controlButton(getContext(),false,20f));
        button.setOnClickListener(view->action.run());return button;
    }

    private int dp(float value) {return Math.round(value*getResources().getDisplayMetrics().density);}

    private void receive(Intent intent) {
        Bundle binding=intent.getBundleExtra("identity");
        if(binding==null || !owner.equals(binding.getBinder("owner")))return;
        long next=intent.getLongExtra("source",0);
        if(next<source)return;
        releaseToken=binding.getBinder("release");
        boolean showing=intent.getBooleanExtra("embedded",false);
        if(showing && (!allowed.getAsBoolean() || !EntryGuard.canOpen(getContext()))) {releaseForPrivacy();return;}
        toggle.setIcon(intent.getBooleanExtra("playing",false)?IconButton.PAUSE:IconButton.PLAY);
        toggle.setEnabled(intent.getBooleanExtra("controllable",false));
        if(showing && embedded && source==next)return;
        source=next;handler.removeCallbacks(releaseTimeout);
        stopAnimation();dismissing=false;embedded=showing;
        if(showing) {
            controls.setEnabled(true);showControls();animateOpacity(0f,1f,null);
        } else {setOpacity(0f);popup.dismiss();}
        changed.accept(showing);
    }

    private void showControls() {
        if(!isAttachedToWindow() || popup.isShowing())return;
        int[] origin=new int[2];getLocationOnScreen(origin);controls.setAlpha(opacity);
        try {popup.showAtLocation(this,Gravity.TOP|Gravity.START,
                origin[0]+Math.max(0,getWidth()-dp(92)),origin[1]+Math.max(0,getHeight()-dp(52)));}
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
        next.setDuration(180L);
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
        if(carrier==null || !carrier.isValid())return;
        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()) {tx.setAlpha(carrier,opacity).apply();}
        catch(RuntimeException failure) {android.util.Log.w(Tags.PROBE,"video opacity unavailable",failure);}
    }

    void refresh() {
        publish(allowed.getAsBoolean() && isShown() && EntryGuard.canOpen(getContext()));
    }

    private void publish(boolean active) {
        active=active && carrier!=null && carrier.isValid();
        String key=System.identityHashCode(carrier)+":"+video.getWidth()+":"+video.getHeight()+":"+active;
        if(key.equals(lastPublished))return;
        lastPublished=key;Bundle binding=new Bundle();binding.putBinder("owner",owner);
        getContext().sendBroadcast(new Intent(VideoDock.HOST).setPackage("android").putExtras(binding)
                .putExtra("identity",identity).putExtra("sequence",++sequence).putExtra("surface",carrier)
                .putExtra("width",video.getWidth()).putExtra("height",video.getHeight()).putExtra("active",active),
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    private void command(String action) {
        if(dismissing && !"release".equals(action))return;
        Bundle binding=new Bundle();binding.putBinder("owner",owner);
        getContext().sendBroadcast(new Intent(VideoDock.COMMAND).setPackage("android").putExtras(binding)
                .putExtra("identity",identity).putExtra("source",source).putExtra("command",action),
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    /** Return the native child before privacy hiding or destruction of its temporary parent. */
    void releaseForPrivacy() {
        stopAnimation();handler.removeCallbacks(releaseTimeout);popup.dismiss();setOpacity(0f);
        if(releaseToken!=null) {
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try {
                data.writeInterfaceToken("io.github.zerohomers0.onesteplite.VideoRelease");data.writeStrongBinder(owner);
                releaseToken.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0);reply.readException();
            } catch(Throwable failure) {android.util.Log.w(Tags.PROBE,"video native return pending",failure);}
            finally {reply.recycle();data.recycle();}
        }
        publish(false);boolean wasEmbedded=embedded;embedded=false;dismissing=false;
        if(wasEmbedded)changed.accept(false);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        try {
            android.graphics.Canvas canvas=holder.lockCanvas();
            if(canvas!=null) {canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);holder.unlockCanvasAndPost(canvas);}
            carrier=(SurfaceControl)XposedHelpers.callMethod(video,"getSurfaceControl");
        } catch(RuntimeException unavailable) {carrier=null;}
        setOpacity(0f);refresh();
    }

    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) {refresh();}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {releaseForPrivacy();carrier=null;}
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
        releaseForPrivacy();
        if(registered) {getContext().unregisterReceiver(receiver);registered=false;}
        super.onDetachedFromWindow();
    }
}
