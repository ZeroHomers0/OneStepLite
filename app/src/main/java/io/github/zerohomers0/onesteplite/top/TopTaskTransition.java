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
    private float collectionAlpha=1f;
    private float collectionFrameScale=-1f;
    private boolean fade;
    private float fadeScale=1f;
    private float fadeOffsetX, fadeOffsetY;
    private boolean fadeRevealed;
    private Runnable nativeRestore;
    private boolean liveSurfaceAtEnd;
    private ValueAnimator animator;
    private boolean attached, closed, firstDraw, collapsed, revealing;
    private boolean collectionPrepared, collectionStarted;
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
        collectionAlpha=destination.getAlpha();
    }

    /**
     * Cross-fade instead of moving surfaces: the main window fades out over the
     * strip's own clock and rail, and the admitted task fades in on top of them.
     * Nothing changes size or position, so the window never jumps orientation.
     */
    void fade(float scale,float offsetX,float offsetY) {
        fade=true;
        fadeScale=scale>0f?scale:1f;
        fadeOffsetX=offsetX;
        fadeOffsetY=offsetY;
    }

    /** True while the transition cross-fades instead of zooming. */
    boolean fadeMode() {return fade;}

    /** The alpha the returning task starts from, so the native handoff can match it. */
    float fadeStartAlpha() {return 0f;}

    /** True once the admitted task finished fading in over the strip. */
    boolean fadeRevealed() {return fadeRevealed;}

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
            if(fade) {
                // The main window fades out in place; the strip's clock and rail stay
                // visible underneath, so no empty placeholder is ever shown.
                animate(0f,1f,200L,()->{
                    applyFadeOut(1f);
                    log.accept("upper live main faded out; collection handoff");
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
            animate(0f,1f,fade?200L:180L,()->{
                // The task now belongs to the strip; the old main surface must not
                // be moved again by close().
                liveSurfaceAtEnd=true;
                fadeRevealed=fade;
                log.accept(fade?"upper live task faded in":"upper live collection expanded");
                close();
            });
            return;
        }
        if(!validLiveSurface()) {
            log.accept("upper live main surface unavailable; skip center animation");
            close();return;
        }
        animate(0f,1f,fade?200L:180L,()->{
            applyFadeIn(1f);
            fadeRevealed=fade;
            log.accept(fade?"upper live main faded in":"upper live main surface expanded");
            close();
        });
    }

    private void animate(float from,float to,long duration,Runnable end) {
        if(closed)return;
        ValueAnimator next=ValueAnimator.ofFloat(from,to);
        animator=next;
        next.setDuration(duration);
        next.setInterpolator(new AccelerateDecelerateInterpolator());
        next.addUpdateListener(value->{
            if(!current.getAsBoolean()) {close();return;}
            float fraction=(Float)value.getAnimatedValue();
            if(fade) {
                // Fade drives the real main surface out; the reveal fades the
                // admitted task, or the returning window, back in.
                boolean ok=revealing?applyFadeIn(fraction):applyFadeOut(fraction);
                if(!ok) {
                    log.accept("upper fade surface lost; restoring full frame");
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
            if(fade)applyFadeOut(0f);
            else applyLiveScale(1f);
        }
        if(collectionView!=null) {
            collectionView.setPivotX(collectionPivotX);collectionView.setPivotY(collectionPivotY);
            collectionView.setScaleX(collectionScaleX);collectionView.setScaleY(collectionScaleY);
            collectionView.setAlpha(collectionAlpha);
        }
        Runnable restore=nativeRestore;nativeRestore=null;
        if(restore!=null)try {restore.run();}
        catch(Throwable error) {log.accept("upper live main state restore: "+error);}
        ValueAnimator old=animator;animator=null;
        if(old!=null)old.cancel();
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
        if(fade) {
            // The admitted task fades in over the strip's own content.
            collectionView.setAlpha(scale);
            return;
        }
        collectionView.setPivotX(collectionView.getWidth()*.5f);
        collectionView.setPivotY(collectionView.getHeight()*.5f);
        collectionView.setScaleX(scale);collectionView.setScaleY(scale);
    }

    /**
     * The main surface fades out where it stands. Its resting transform is kept
     * exactly as the workspace set it, so the window never shifts or resizes and
     * the strip's own clock and rail stay visible underneath.
     */
    private boolean applyFadeOut(float fraction) {
        float f=Math.max(0f,Math.min(1f,fraction));
        // The destination must stay invisible until native admission confirms it;
        // a half-faded task peeking out of the strip reads as a glitch.
        if(collectionView!=null && !revealing) {
            collectionFrameScale=0f;
            collectionView.setAlpha(0f);
        }
        if(!validLiveSurface())return false;
        Object transaction=null;
        try {
            transaction=Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(transaction,"setMatrix",liveSurface,
                    fadeScale,0f,0f,fadeScale);
            XposedHelpers.callMethod(transaction,"setPosition",liveSurface,
                    fadeOffsetX,fadeOffsetY);
            XposedHelpers.callMethod(transaction,"setWindowCrop",liveSurface,
                    new Rect(0,0,width,height));
            XposedHelpers.callMethod(transaction,"setAlpha",liveSurface,1f-f);
            XposedHelpers.callMethod(transaction,"apply");
            liveSurfaceAtEnd=f>=1f;
            return true;
        } catch(Throwable error) {
            log.accept("upper fade out: "+error);
            return false;
        } finally {
            if(transaction!=null)try {XposedHelpers.callMethod(transaction,"close");}
            catch(Throwable ignored) {}
        }
    }

    /**
     * The admitted task, or the returning window, fades in over the strip content.
     * Only alpha moves, so a landscape task never jumps orientation on the way in.
     */
    private boolean applyFadeIn(float fraction) {
        float f=Math.max(0f,Math.min(1f,fraction));
        if(collectionView==null) {
            if(!validLiveSurface())return false;
            Object transaction=null;
            try {
                transaction=Class.forName("android.view.SurfaceControl$Transaction")
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(transaction,"setMatrix",liveSurface,
                        fadeScale,0f,0f,fadeScale);
                XposedHelpers.callMethod(transaction,"setPosition",liveSurface,
                        fadeOffsetX,fadeOffsetY);
                XposedHelpers.callMethod(transaction,"setWindowCrop",liveSurface,
                        new Rect(0,0,width,height));
                XposedHelpers.callMethod(transaction,"setAlpha",liveSurface,f);
                XposedHelpers.callMethod(transaction,"apply");
                liveSurfaceAtEnd=f>=1f;
                return true;
            } catch(Throwable error) {
                log.accept("upper fade in: "+error);
                return false;
            } finally {
                if(transaction!=null)try {XposedHelpers.callMethod(transaction,"close");}
                catch(Throwable ignored) {}
            }
        }
        collectionFrameScale=f;
        collectionView.setAlpha(f);
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
