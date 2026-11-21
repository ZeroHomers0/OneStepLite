package io.github.zerohomers0.onesteplite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Visual bridge only for main <-> upper task. Native task ownership stays with Host. */
final class TopTaskTransition {
    private final FrameLayout host;
    private final WindowManager windows;
    private final WindowManager.LayoutParams params;
    private final Face face;
    private final int width, height;
    private final BooleanSupplier current;
    private final Runnable handoff;
    private final Consumer<TopTaskTransition> finished;
    private final Consumer<String> log;
    private Object liveSurface;
    private View collectionView;
    private BooleanSupplier collectionSourceCurrent;
    private float collectionPivotX, collectionPivotY;
    private float collectionScaleX, collectionScaleY;
    private float collectionFrameScale=-1f;
    private boolean drawer;
    private float drawerScale=1f;
    private float drawerTravel;
    private Runnable nativeRestore;
    private boolean liveSurfaceAtEnd;
    private ValueAnimator animator;
    private boolean attached, closed, firstDraw, collapsed, revealing;
    private boolean collectionPrepared, collectionStarted;
    /** Fraction of the collapse at which the destination starts growing. */
    private static final float DRAWER_HANDOFF = .35f;
    private boolean handoffEarly;
    private ValueAnimator collapseAnimator;
    private final Runnable timeout=this::close;
    private final View.OnAttachStateChangeListener attachment=new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) {close();}
    };

    TopTaskTransition(FrameLayout host,int width,int height,
            BooleanSupplier current,Runnable handoff,
            Consumer<TopTaskTransition> finished,Consumer<String> log) throws Throwable {
        this.host=host;this.width=width;this.height=height;
        this.current=current;this.handoff=handoff;this.finished=finished;this.log=log;
        face=new Face();
        windows=(WindowManager)host.getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
        params=new WindowManager.LayoutParams(-1,-1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.START;
        params.windowAnimations=0;
        params.setTitle("OneStepLiteUpperTransition");
        HostWindowPolicy.configure(params);
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        face.setOnTouchListener((view,event)->true);
        face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void start() {
        if(closed || attached)return;
        if(!current.getAsBoolean()) {close();return;}
        windows.addView(face,params);attached=true;
        log.accept("upper live center start");
        host.addOnAttachStateChangeListener(attachment);
        // A failed/late native handoff must never leave an input-blocking overlay behind.
        host.postDelayed(timeout,5000L);
    }

    void setLiveSurface(Object surface) {
        liveSurface=surface;
    }

    void collect(Object surface,BooleanSupplier sourceCurrent,View destination) {
        liveSurface=surface;
        collectionSourceCurrent=sourceCurrent;
        collectionView=destination;
        collectionPivotX=destination.getPivotX();collectionPivotY=destination.getPivotY();
        collectionScaleX=destination.getScaleX();collectionScaleY=destination.getScaleY();
    }

    /**
     * Turn the collection into a drawer: the main surface slides up into the strip
     * and is cropped at the boundary while the destination grows out of that edge.
     */
    void drawer(float scale,float travel) {
        drawer=true;
        drawerScale=scale>0f?scale:1f;
        drawerTravel=Math.max(0f,travel);
    }

    /** True while the transition runs as a drawer instead of a centre zoom. */
    boolean drawerMode() {return drawer;}

    /** The scale the drawer starts from, so the native handoff can match it. */
    float drawerStartScale() {return drawerScale*.92f;}

    /** How far above its resting place the drawer starts, in surface pixels. */
    float drawerStartOffsetY() {return drawerTravel;}

    void setNativeRestore(Runnable restore) {
        if(closed)restore.run();
        else nativeRestore=restore;
    }

    /** The source stays full size until the native background sync is committed. */
    boolean collectionReady() {
        if(closed || collectionView==null)return false;
        if(!current.getAsBoolean() || !validLiveSurface()) {close();return false;}
        collectionPrepared=true;
        startCollectionIfReady();
        return true;
    }

    private void startCollectionIfReady() {
        if(closed || !firstDraw || !collectionPrepared || collectionStarted)return;
        collectionStarted=true;
        face.postOnAnimation(()->{
            if(closed)return;
            if(!current.getAsBoolean() || !validLiveSurface()) {close();return;}
            if(drawer) {
                // The drawer runs forward: 0 is the resting main window, 1 is fully
                // tucked under the strip. The destination grows back out afterwards.
                animate(0f,1f,260L,()->{
                    applyDrawerCollapse(1f);
                    log.accept("upper live main tucked under the strip; collection handoff");
                    handoff();
                });
                return;
            }
            animate(1f,0f,160L,()->{
                applyCollectionScale(0f);
                log.accept("upper live main collapsed; collection handoff");
                handoff();
            });
        });
    }

    /** Keep geometry refreshes from exposing the destination before native admission. */
    void applyCollectionFrame(View destination) {
        if(!closed && collectionView==destination && collectionFrameScale>=0f)
            applyCollectionScale(collectionFrameScale);
    }

    private void handoff() {
        if(closed || collapsed)return;
        collapsed=true;
        log.accept("upper roll finished; live main reveal handoff");
        if(!current.getAsBoolean()) {close();return;}
        try {handoff.run();}
        catch(Throwable error) {log.accept("upper center handoff: "+error);close();}
    }

    /** Called after the existing adoption/return callback confirms task ownership. */
    void reveal() {
        if(closed || !collapsed || revealing)return;
        revealing=true;
        if(collectionView!=null) {
            // Shell now owns the task matrix; animate only its original SurfaceView parent.
            animate(0f,1f,drawer?200L:180L,()->{
                // The task now belongs to the strip; the old main surface must not
                // be moved again by close().
                liveSurfaceAtEnd=true;
                log.accept(drawer?"upper live drawer expanded":"upper live collection expanded");
                close();
            });
            return;
        }
        if(!validLiveSurface()) {
            log.accept("upper live main surface unavailable; skip center animation");
            close();return;
        }
        if(drawer) {
            // The returning task slides back down out of the strip seam.
            animate(0f,1f,220L,()->{
                applyDrawerReveal(1f);
                log.accept("upper live drawer returned to the workspace");
                close();
            });
            return;
        }
        animate(0f,1f,180L,()->{
            applyLiveScale(1f);
            log.accept("upper live main surface expanded");
            close();
        });
    }

    private void animate(float from,float to,long duration,Runnable end) {
        if(closed)return;
        ValueAnimator next=ValueAnimator.ofFloat(from,to);
        animator=next;
        if(drawer && !revealing)collapseAnimator=next;
        next.setDuration(duration);
        next.setInterpolator(new AccelerateDecelerateInterpolator());
        next.addUpdateListener(value->{
            if(!current.getAsBoolean()) {close();return;}
            float fraction=(Float)value.getAnimatedValue();
            if(drawer && !revealing && !handoffEarly && fraction>=DRAWER_HANDOFF
                    && validLiveSurface()) {
                // Start the destination while the main window is still sliding, so
                // the two halves overlap instead of running back to back.
                handoffEarly=true;
                handoff();
            }
            if(drawer) {
                // Collapse drives the real main surface; reveal grows the destination
                // (or slides the returning task back down) out of the same seam.
                boolean ok=revealing?applyDrawerReveal(fraction):applyDrawerCollapse(fraction);
                if(!ok) {
                    log.accept("upper drawer surface lost; restoring full frame");
                    close();return;
                }
            } else if(collectionView!=null && revealing) {
                // Zero until admission, then start moving immediately. A .14 hold
                // exposed a miniature task during the asynchronous native handoff.
                applyCollectionScale(fraction);
            } else if(!applyLiveScale(TopTaskReturnPolicy.revealScale(fraction))) {
                    log.accept("upper live main surface transform failed; restoring full frame");
                close();return;
            }
            face.invalidate();
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if(closed || animator!=animation)return;
                animator=null;end.run();
            }
        });
        next.start();
    }

    void close() {
        if(closed)return;
        closed=true;
        if(!liveSurfaceAtEnd) {
            if(drawer)applyDrawerCollapse(0f);
            else applyLiveScale(1f);
        }
        if(collectionView!=null) {
            collectionView.setPivotX(collectionPivotX);collectionView.setPivotY(collectionPivotY);
            collectionView.setScaleX(collectionScaleX);collectionView.setScaleY(collectionScaleY);
        }
        Runnable restore=nativeRestore;nativeRestore=null;
        if(restore!=null)try {restore.run();}
        catch(Throwable error) {log.accept("upper live main state restore: "+error);}
        ValueAnimator old=animator;animator=null;
        if(old!=null)old.cancel();
        ValueAnimator collapse=collapseAnimator;collapseAnimator=null;
        if(collapse!=null && collapse!=old)collapse.cancel();
        host.removeCallbacks(timeout);host.removeOnAttachStateChangeListener(attachment);
        if(attached) {
            face.setVisibility(View.GONE);
            try {windows.removeViewImmediate(face);}
            catch(Throwable error) {log.accept("upper transition removal: "+error);}
            attached=false;
        }
        finished.accept(this);
    }

    private boolean validLiveSurface() {
        try {
            return (collectionSourceCurrent==null || collectionSourceCurrent.getAsBoolean())
                    && liveSurface!=null && Boolean.TRUE.equals(XposedHelpers.callMethod(liveSurface,"isValid"));
        } catch(Throwable ignored) {return false;}
    }

    private void applyCollectionScale(float scale) {
        collectionFrameScale=scale;
        if(drawer) {
            // Collapse moves the real main surface; reveal grows the destination.
            if(revealing)applyDrawerReveal(scale);
            else applyDrawerCollapse(scale);
            return;
        }
        collectionView.setPivotX(collectionView.getWidth()*.5f);
        collectionView.setPivotY(collectionView.getHeight()*.5f);
        collectionView.setScaleX(scale);collectionView.setScaleY(scale);
    }

    /**
     * The main surface slides up out of the workspace. The strip backing and the
     * task view's own clip hide it at the seam, so no extra crop is needed; the
     * slight shrink keeps the motion reading as "flowing into" the strip.
     */
    private boolean applyDrawerCollapse(float fraction) {
        float f=Math.max(0f,Math.min(1f,fraction));
        // The destination must stay invisible until native admission confirms it;
        // a miniature task peeking out of the strip reads as a glitch.
        if(collectionView!=null && !revealing) {
            // Keep the recorded frame at zero so a geometry refresh cannot reveal it.
            // Once the reveal owns the destination this must stop writing to it.
            collectionFrameScale=0f;
            collectionView.setPivotX(collectionView.getWidth()*.5f);
            collectionView.setPivotY(collectionView.getHeight());
            collectionView.setScaleX(0f);
            collectionView.setScaleY(0f);
        }
        if(!validLiveSurface())return false;
        Object transaction=null;
        try {
            transaction=Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            // Pure translation: shrinking while rising read as the window
            // suddenly getting narrower, so the width stays exactly as it was.
            XposedHelpers.callMethod(transaction,"setMatrix",liveSurface,drawerScale,0f,0f,drawerScale);
            XposedHelpers.callMethod(transaction,"setPosition",liveSurface,0f,-f*drawerTravel);
            XposedHelpers.callMethod(transaction,"setWindowCrop",liveSurface,
                    new Rect(0,0,width,height));
            XposedHelpers.callMethod(transaction,"apply");
            liveSurfaceAtEnd=f>=1f;
            return true;
        } catch(Throwable error) {
            log.accept("upper drawer collapse: "+error);
            return false;
        } finally {
            if(transaction!=null)try {XposedHelpers.callMethod(transaction,"close");}
            catch(Throwable ignored) {}
        }
    }

    /**
     * The destination grows downward out of the strip's lower edge; on the way back
     * the returning task slides down from that same seam instead.
     */
    private boolean applyDrawerReveal(float fraction) {
        float f=Math.max(0f,Math.min(1f,fraction));
        if(collectionView==null) {
            if(!validLiveSurface())return false;
            Object transaction=null;
            try {
                transaction=Class.forName("android.view.SurfaceControl$Transaction")
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(transaction,"setMatrix",liveSurface,drawerScale,0f,0f,drawerScale);
                XposedHelpers.callMethod(transaction,"setPosition",liveSurface,0f,-(1f-f)*drawerTravel);
                XposedHelpers.callMethod(transaction,"setWindowCrop",liveSurface,
                        new Rect(0,0,width,height));
                XposedHelpers.callMethod(transaction,"apply");
                liveSurfaceAtEnd=f>=1f;
                return true;
            } catch(Throwable error) {
                log.accept("upper drawer reveal: "+error);
                return false;
            } finally {
                if(transaction!=null)try {XposedHelpers.callMethod(transaction,"close");}
                catch(Throwable ignored) {}
            }
        }
        // Anchored on the bottom edge it shares with the main window, so the task
        // grows downward out of the seam instead of from the screen centre.
        collectionFrameScale=f;
        collectionView.setPivotX(collectionView.getWidth()*.5f);
        collectionView.setPivotY(collectionView.getHeight());
        collectionView.setScaleX(f);
        collectionView.setScaleY(f);
        return true;
    }

    private boolean applyLiveScale(float scale) {
        if(!validLiveSurface())return false;
        Object transaction=null;
        try {
            transaction=Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            float x=TopTaskReturnPolicy.centeredOffset(width,scale);
            float y=TopTaskReturnPolicy.centeredOffset(height,scale);
            XposedHelpers.callMethod(transaction,"setPosition",liveSurface,x,y);
            XposedHelpers.callMethod(transaction,"setMatrix",liveSurface,scale,0f,0f,scale);
            XposedHelpers.callMethod(transaction,"setWindowCrop",liveSurface,new Rect(0,0,width,height));
            XposedHelpers.callMethod(transaction,"apply");
            liveSurfaceAtEnd=scale>=1f;
            return true;
        } catch(Throwable error) {
            log.accept("upper live main surface transaction: "+error);
            return false;
        } finally {
            if(transaction!=null)try {XposedHelpers.callMethod(transaction,"close");}
            catch(Throwable ignored) {}
        }
    }

    private final class Face extends View {
        Face() {super(host.getContext());}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if(!firstDraw) {
                firstDraw=true;
                if(collectionView!=null)startCollectionIfReady();
                else postOnAnimation(()->{if(!closed)handoff();});
            }
        }
    }
}
