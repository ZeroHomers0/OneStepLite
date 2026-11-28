package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import java.lang.reflect.Proxy;
import de.robv.android.xposed.XposedHelpers;

/** Spy channel: taps pass through; bottom horizontal intent retains the stream until acceptance. */
public final class HotZoneGesture {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static HotZoneGesture instance;
    private static int selectedEntryGesture = WorkspaceOptions.STATUS_BAR_INWARD;
    private final Context context;
    private final Runnable trigger;
    private final WindowManager windows;
    private final Point displaySize=new Point();
    private int displayRotation;
    private final CornerSwipe swipe = new CornerSwipe();
    private final BottomCenterSwipe bottomSwipe = new BottomCenterSwipe();
    private Object monitor, receiver;
    private WorkspaceSettingsClient entrySettings;
    private boolean enabled;
    private long generation;
    private long entryContext;
    private int admittedEntryGesture;
    private boolean bottomPilfered;
    private boolean bottomUnbatched;
    private Object batchingReceiver;
    private int bottomIntentPx, bottomVerticalPx;
    private long bottomDownTime, lastBottomOpened = -1;

    private HotZoneGesture(Context context, Runnable trigger) {
        this.context = context;
        this.trigger = trigger;
        this.windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }
    public static void install(Context context, Runnable trigger) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> install(context, trigger));
            return;
        }
        if (instance != null) return;
        HotZoneGesture g = new HotZoneGesture(context, trigger);
        instance = g;
        g.entrySettings = new WorkspaceSettingsClient(context, MAIN,
                options -> setEntryGesture(options.entryGesture));
        g.attach();
    }

    private void attach() {
        if (receiver != null) return;
        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> compat = XposedHelpers.findClass(
                    "com.android.systemui.shared.system.InputMonitorCompat", cl);
            Class<?> listener = XposedHelpers.findClass(
                    "com.android.systemui.shared.system.InputChannelCompat$InputEventListener", cl);
            monitor = XposedHelpers.newInstance(compat, "OneStepLiteGesture", 0);
            Object attachedMonitor = monitor;
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                    (self, method, args) -> {
                        // Target receiver ACKs after returning. Never throw out of this callback.
                        try {
                            if ("onInputEvent".equals(method.getName()) && args != null
                                    && args.length == 1 && args[0] instanceof MotionEvent) {
                                if (monitor == attachedMonitor) onMotion((MotionEvent) args[0]);
                            } else if ("toString".equals(method.getName())) return "OneStepLiteGestureListener";
                            else if ("hashCode".equals(method.getName())) return System.identityHashCode(self);
                            else if ("equals".equals(method.getName())) return self == args[0];
                        } catch (Throwable error) {
                            reset();
                            log("gesture callback stopped: " + error);
                        }
                        return null;
                    });
            receiver = XposedHelpers.callMethod(monitor, "getInputReceiver",
                    Looper.getMainLooper(), Choreographer.getInstance(), proxy);
            if (receiver == null) throw new IllegalStateException("no receiver");
            enabled = true;
            log("gesture spy installed; no touch overlay; tap pass-through enabled");
        } catch (Throwable error) {
            dispose();
            log("gesture spy unavailable: " + error);
        }
    }
    private void onMotion(MotionEvent e) {
        if (!enabled) return;
        int action=e.getActionMasked();
        boolean tracking = swipe.active() || bottomSwipe.active() || bottomPilfered;
        if (action != MotionEvent.ACTION_DOWN && !tracking) return;
        if (!e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                || !Integer.valueOf(0).equals(XposedHelpers.callMethod(e, "getDisplayId"))) return;
        if(action==MotionEvent.ACTION_DOWN)bottomDownTime=e.getEventTime();
        if(WorkspaceWindowLayout.hostSceneActive()){
            generation++;swipe.reset();bottomSwipe.reset();bottomPilfered=false;restoreBatching();return;
        }
        if(action==MotionEvent.ACTION_UP)refreshDisplay();
        retainEarlyBottomIntent(e);
        if(action==MotionEvent.ACTION_DOWN)beginGesture(e);
        else if(action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP)advanceGesture(e,action);
        else if(action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_POINTER_DOWN
                || action==MotionEvent.ACTION_POINTER_UP)cancelGesture(action,e);
    }
    private void refreshDisplay(){
        windows.getDefaultDisplay().getRealSize(displaySize);
        displayRotation=windows.getDefaultDisplay().getRotation();
    }
    private void retainEarlyBottomIntent(MotionEvent e){
        if(e.getActionMasked()!=MotionEvent.ACTION_MOVE || bottomPilfered
                || admittedEntryGesture!=WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                || selectedEntryGesture!=admittedEntryGesture || WorkspaceWindowLayout.hostSceneActive()
                || !bottomSwipe.canRetain(e.getPointerId(0),e.getPointerCount(),e.getRawX(),e.getRawY(),
                        e.getEventTime(),bottomIntentPx,bottomVerticalPx))return;
        if(!pilferPointers()){reset();return;}
        bottomPilfered=true;
        log("bottom entry retained before state queries; "+bottomSwipe.progress(e.getRawX(),e.getRawY(),e.getEventTime()));
    }
    private void beginGesture(MotionEvent e){
        refreshDisplay();reset();
        float density=context.getResources().getDisplayMetrics().density;
        int cornerPx=Math.round(72f*density);
        int bottomPx=Math.round(8f*density);
        float x=e.getRawX(), y=e.getRawY();
        boolean corner=y>=0 && y<cornerPx && (x<cornerPx || x>=displaySize.x-cornerPx);
        boolean bottom=selectedEntryGesture==WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                && y>=displaySize.y-bottomPx && y<=displaySize.y;
        if(!corner && !bottom)return;
        if(!EntryGuard.canOpen(context))return;
        admittedEntryGesture=effectiveEntryGesture();
        if(!allowsEntry(displaySize,admittedEntryGesture)){
            log("corner gesture ignored in landscape or native fullscreen");return;
        }
        entryContext=StatusBar.cornerGestureContext();
        if(admittedEntryGesture==WorkspaceOptions.BOTTOM_CENTER_OUTWARD){
            bottomIntentPx=Math.round(2f*density);bottomVerticalPx=Math.round(24f*density);
            bottomSwipe.down(e.getPointerId(0),e.getPointerCount(),e.getRawX(),e.getRawY(),e.getEventTime(),
                    displaySize.x,displaySize.y,displayRotation,bottomPx);
            if(bottomSwipe.active()){
                try{setInputBatching(false);bottomUnbatched=true;}
                catch(Throwable error){log("bottom immediate input unavailable: "+error);reset();return;}
                log("bottom entry candidate; immediate input; edge="+Math.round(8f*density));
            }
        }else swipe.down(e.getPointerId(0),e.getPointerCount(),e.getRawX(),e.getRawY(),e.getEventTime(),
                displaySize.x,displaySize.y,displayRotation,cornerPx,Math.round(58f*density),
                Math.round(24f*density));
    }
    private void advanceGesture(MotionEvent e,int action){
        if(!keepsEntry(displaySize)){reset();return;}
        float density=context.getResources().getDisplayMetrics().density;
        boolean bottomCandidate=bottomSwipe.active();
        boolean acceptedSwipe=admittedEntryGesture==WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                ?bottomSwipe.move(e.getPointerId(0),e.getPointerCount(),e.getRawX(),e.getRawY(),e.getEventTime(),
                        displaySize.x,displaySize.y,displayRotation,Math.round(58f*density),Math.round(24f*density))
                :swipe.move(e.getPointerId(0),e.getPointerCount(),e.getRawX(),e.getRawY(),e.getEventTime(),
                        displaySize.x,displaySize.y,displayRotation);
        if(admittedEntryGesture==WorkspaceOptions.BOTTOM_CENTER_OUTWARD && !bottomPilfered
                &&(acceptedSwipe || action==MotionEvent.ACTION_MOVE
                && bottomSwipe.horizontalIntent(e.getRawX(),e.getRawY(),Math.round(2f*density)))){
            if(!EntryGuard.canOpen(context) || !pilferPointers()){reset();return;}
            bottomPilfered=true;
            log("bottom entry retained; "+bottomSwipe.progress(e.getRawX(),e.getRawY(),e.getEventTime()));
        }
        if(bottomCandidate && !acceptedSwipe && !bottomSwipe.active())
            log("bottom entry rejected (pointer/geometry/time/vertical); claimed="+bottomPilfered
                    +" "+bottomSwipe.progress(e.getRawX(),e.getRawY(),e.getEventTime()));
        if(acceptedSwipe)scheduleAcceptedEntry();
        if(action==MotionEvent.ACTION_UP){
            if(bottomSwipe.active())log("bottom entry released below threshold; claimed="+bottomPilfered
                    +" "+bottomSwipe.progress(e.getRawX(),e.getRawY(),e.getEventTime()));
            swipe.reset();bottomSwipe.reset();bottomPilfered=false;restoreBatching();
        }
    }
    private void scheduleAcceptedEntry(){
        if(!EntryGuard.canOpen(context)){reset();return;}
        if(!bottomPilfered && !pilferPointers()){reset();return;}
        long accepted=generation,acceptedDown=bottomDownTime;
        MAIN.postDelayed(()->{
            if(!enabled || generation!=accepted)return;
            if(admittedEntryGesture==WorkspaceOptions.BOTTOM_CENTER_OUTWARD && lastBottomOpened==acceptedDown)return;
            if(WorkspaceWindowLayout.hostSceneActive() || !EntryGuard.canOpen(context)){reset();return;}
            Point current=new Point();windows.getDefaultDisplay().getRealSize(current);
            if(current.x!=displaySize.x || current.y!=displaySize.y
                    || windows.getDefaultDisplay().getRotation()!=displayRotation || !keepsEntry(current)){reset();return;}
            generation++;
            boolean mainRight=admittedEntryGesture==WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                    ?bottomSwipe.sign()>0:swipe.sign()>0;
            WorkspaceHost.setPendingMainSide(mainRight);
            if(admittedEntryGesture==WorkspaceOptions.BOTTOM_CENTER_OUTWARD)lastBottomOpened=acceptedDown;
            log("gesture mode="+admittedEntryGesture+" -> main="+(mainRight?"right":"left")+" -> open");
            trigger.run();
        },65L);
        log("entry swipe mode="+admittedEntryGesture+" accepted; pilfered once");
    }
    private void cancelGesture(int action,MotionEvent e){
        if(bottomSwipe.active())log("bottom entry cancelled; action="+action+" claimed="+bottomPilfered
                +" "+bottomSwipe.progress(e.getRawX(),e.getRawY(),e.getEventTime()));
        reset();
    }
    public static void setEntryGesture(int mode) {
        int valid = mode == WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                ? mode : WorkspaceOptions.STATUS_BAR_INWARD;
        if (instance != null) instance.context.sendBroadcast(
                new android.content.Intent(Actions.BOTTOM_INPUT_OPTIONS)
                        .setPackage("android").putExtra("mode", valid));
        if (selectedEntryGesture == valid) return;
        selectedEntryGesture = valid;
        if (instance != null) instance.reset();
    }

    static void completeBottomReplay(int sign, long downTime, long eventTime) {
        HotZoneGesture g = instance;
        if (g == null || (sign != -1 && sign != 1) || downTime < 0 || eventTime < downTime
                || eventTime - downTime > 600) return;
        MAIN.post(() -> {
            long now = SystemClock.uptimeMillis();
            if (!g.enabled || now < eventTime || now - eventTime > 600
                    || g.lastBottomOpened == downTime || WorkspaceWindowLayout.hostSceneActive()
                    || g.effectiveEntryGesture() != WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                    || !EntryGuard.canOpen(g.context)) return;
            WindowManager wm = (WindowManager) g.context.getSystemService(Context.WINDOW_SERVICE);
            Point size = new Point();
            wm.getDefaultDisplay().getRealSize(size);
            if (!g.allowsEntry(size, WorkspaceOptions.BOTTOM_CENTER_OUTWARD)) return;
            g.reset();
            g.lastBottomOpened = downTime;
            WorkspaceHost.setPendingMainSide(sign > 0);
            log("OS3 bottom replay -> main=" + (sign > 0 ? "right" : "left") + " -> open");
            g.trigger.run();
        });
    }

    private int effectiveEntryGesture() {
        if (selectedEntryGesture == WorkspaceOptions.STATUS_BAR_INWARD)
            return WorkspaceOptions.STATUS_BAR_INWARD;
        return EntryGesturePolicy.effectiveMode(selectedEntryGesture,
                EntryGestureSettings.lineHidden(context));
    }

    private boolean allowsEntry(Point size, int mode) {
        return mode == WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                ? StatusBar.allowsBottomGesture(size.x, size.y, DeviceProfile.isTablet(context))
                : StatusBar.allowsCornerGesture(size.x, size.y, DeviceProfile.isTablet(context));
    }

    private boolean keepsEntry(Point size) {
        if (effectiveEntryGesture() != admittedEntryGesture) return false;
        return admittedEntryGesture == WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                ? allowsEntry(size, admittedEntryGesture)
                : StatusBar.keepsCornerGesture(entryContext);
    }
    private boolean pilferPointers() {
        try {
            Object platform = XposedHelpers.getObjectField(monitor, "mInputMonitor");
            XposedHelpers.callMethod(platform, "pilferPointers");
            return true;
        } catch (Throwable error) {
            log("pilfer failed; no trigger: " + error);
            return false;
        }
    }

    private void reset() {
        generation++;
        swipe.reset();
        bottomSwipe.reset();
        bottomPilfered = false;
        restoreBatching();
    }
    public static boolean isAttached() {
        return instance != null && instance.enabled && instance.receiver != null;
    }
    public static void raiseAll() {
        if (instance != null) instance.attach();
    }
    public static void suspend() {
        if (instance != null) instance.dispose();
    }
    private void dispose() {
        enabled = false;
        reset();
        if (receiver != null) {
            try { XposedHelpers.callMethod(receiver, "dispose"); }
            catch (Throwable e) { log("receiver dispose: " + e); }
            finally { receiver = null; }
        }
        batchingReceiver = null;
        if (monitor != null) {
            try { XposedHelpers.callMethod(monitor, "dispose"); }
            catch (Throwable e) { log("monitor dispose: " + e); }
            finally { monitor = null; }
        }
    }
    private void restoreBatching() {
        if (!bottomUnbatched) return;
        bottomUnbatched = false;
        try { setInputBatching(true); }
        catch (Throwable error) { log("restore input batching unavailable: " + error); }
    }
    private void setInputBatching(boolean enabled) {
        if (batchingReceiver == null) {
            try {
                receiver.getClass().getMethod("setBatchingEnabled", boolean.class);
                batchingReceiver = receiver;
            } catch (NoSuchMethodException oldWrapper) {
                batchingReceiver = XposedHelpers.getObjectField(receiver, "mReceiver");
            }
        }
        XposedHelpers.callMethod(batchingReceiver, "setBatchingEnabled", enabled);
    }
    private static void log(String message) { ModuleLog.info("gesture", message); }
}
