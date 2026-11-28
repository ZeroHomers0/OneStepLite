package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Reveal only after native resize sync and the destination's hidden Host frame have committed. */
final class TopTaskFrame {
    static final class Target {
        final Object controller,transitions,token;
        final int taskId,width,height;
        final Rect bounds;
        Target(Object controller,Object transitions,Object token,int taskId,Rect bounds,int width,int height) {
            this.controller=controller;this.transitions=transitions;this.token=token;
            this.taskId=taskId;this.bounds=new Rect(bounds);this.width=width;this.height=height;
        }
    }
    private final Handler ui;
    private final View view;
    private final Executor shell;
    private final Target target;
    private final TransitionSession session;
    private final Runnable ready;
    private final Consumer<String> failed;
    private final long deadline=SystemClock.uptimeMillis()+2000L;
    private final Runnable pollFrame=this::poll;
    private Runnable drawn;
    private int pollAttempt;
    private final Runnable timeout=()->fail("top destination draw timed out");

    private TopTaskFrame(Handler ui,View view,Executor shell,Target target,BooleanSupplier current,
            Runnable ready,Consumer<String> failed) {
        this.ui=ui;this.view=view;this.shell=shell;this.target=target;
        this.session=new TransitionSession(current);this.ready=ready;this.failed=failed;
    }

    static void await(Handler ui,View view,Executor shell,Target target,BooleanSupplier current,
            Runnable ready,Consumer<String> failed) {
        TopTaskFrame frame=new TopTaskFrame(ui,view,shell,target,current,ready,failed);
        ui.postDelayed(frame.timeout,2000L);frame.poll();
    }

    private void poll() {
        if(!session.active()) {retire();return;}
        if(SystemClock.uptimeMillis()>=deadline) {fail("top destination draw timed out");return;}
        if(view.getWidth()!=target.width || view.getHeight()!=target.height || !view.isAttachedToWindow()) {
            retry();return;
        }
        try {shell.execute(this::checkNative);}
        catch(RuntimeException unavailable) {fail("top frame Shell unavailable");}
    }

    private void retry() {
        int delay=Math.min(64,16<<Math.min(pollAttempt++,2));
        ui.postDelayed(pollFrame,delay);
    }

    private void checkNative() {
        if(!session.active()) {ui.post(this::retire);return;}
        try {
            Object info=ShellCompat.currentTaskInfo(target.controller,target.taskId);
            Object configuration=XposedHelpers.getObjectField(info,"configuration");
            Object window=XposedHelpers.getObjectField(configuration,"windowConfiguration");
            Object transitions=XposedHelpers.getObjectField(target.transitions,"mTransitions");
            if(!target.token.equals(XposedHelpers.getObjectField(info,"token"))
                    || !Integer.valueOf(6).equals(XposedHelpers.callMethod(info,"getWindowingMode"))
                    || !target.bounds.equals(XposedHelpers.callMethod(window,"getBounds"))
                    || !Boolean.TRUE.equals(ShellCompat.call(transitions,"isIdle"))) {
                retry();return;
            }
            Object wct=TransactionCompat.newWindowTransaction();
            XposedHelpers.callMethod(wct,"setBounds",target.token,target.bounds);
            SyncPreparation.submit(target.controller,wct,ui::post,this::nativeCommitted,
                    error->fail("top destination draw sync failed: "+error));
        } catch(Throwable busy) {
            retry();
        }
    }

    private void nativeCommitted() {
        if(!session.active()) {retire();return;}
        // RenderThread must have submitted the hidden destination at the final
        // layout before the canonical task can fade in above the original strip.
        if(!view.isAttachedToWindow() || !view.isHardwareAccelerated()) {fail("top destination detached");return;}
        drawn=()->{
            if(!session.active()) {retire();return;}
            if(view.getWidth()!=target.width || view.getHeight()!=target.height) {
                drawn=null;ui.post(pollFrame);return;
            }
            retire();ready.run();
        };
        view.getViewTreeObserver().registerFrameCommitCallback(drawn);
        view.invalidate();
    }

    private void fail(String reason) {
        boolean notify=session.active();retire();if(notify)failed.accept(reason);
    }

    private void retire() {
        session.retire();ui.removeCallbacks(timeout);ui.removeCallbacks(pollFrame);
        if(drawn!=null) {
            view.getViewTreeObserver().unregisterFrameCommitCallback(drawn);drawn=null;
        }
    }
}
