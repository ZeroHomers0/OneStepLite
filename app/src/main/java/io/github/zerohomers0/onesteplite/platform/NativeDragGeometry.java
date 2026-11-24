package io.github.zerohomers0.onesteplite;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.view.Surface;
import android.view.SurfaceControl;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Owns hotspot changes and redirects only the corresponding native DROP to its visible arrow. */
final class NativeDragGeometry {
    private static final String KEY="content_drag_geometry";
    private NativeDragGeometry(){}
    static boolean install(ClassLoader loader,BooleanSupplier active){
        try{
            Class<?> type=XposedHelpers.findClass("com.android.server.wm.DragState",loader);
            if(XposedBridge.hookAllMethods(type,"updateDragSurfaceLocked",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call){
                    Object value=XposedHelpers.getAdditionalInstanceField(call.thisObject,KEY);
                    if(value instanceof Session && (call.args.length==3 || call.args.length==4))try{
                        ((Session)value).move(((Number)call.args[call.args.length-2]).floatValue(),((Number)call.args[call.args.length-1]).floatValue());
                    }catch(Throwable unavailable){((Session)value).stop();}
                }
            }).isEmpty())return false;
            if(XposedBridge.hookAllMethods(type,"reportDropWindowLock",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call){
                    Object value=XposedHelpers.getAdditionalInstanceField(call.thisObject,KEY);
                    if(value instanceof Session && (call.args.length==3 || call.args.length==5))try{((Session)value).drop(call);}
                    catch(Throwable unavailable){((Session)value).reject(call);}
                }
            }).isEmpty())return false;
            XposedBridge.hookAllMethods(type,"closeLocked",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call){
                    Object value=XposedHelpers.getAdditionalInstanceField(call.thisObject,KEY);
                    if(value instanceof Session){((Session)value).stop();XposedHelpers.removeAdditionalInstanceField(call.thisObject,KEY);}
                }
            });
            return true;
        }catch(Throwable unavailable){return false;}
    }
    static void mark(ClipData clip){
        android.os.PersistableBundle data=clip.getDescription().getExtras();
        data.putString("onesteplite_geometry_id",UUID.randomUUID().toString());clip.getDescription().setExtras(data);
    }
    static void attach(Object controller,Object display,ContentDragShadow card,SurfaceControl surface,BooleanSupplier active){
        try{
            Object wm=XposedHelpers.getObjectField(display,"mWmService");
            synchronized(XposedHelpers.getObjectField(wm,"mGlobalLock")){
                Object state=XposedHelpers.getObjectField(controller,"mDragState");
                if(state==null || XposedHelpers.getObjectField(state,"mSurfaceControl")!=surface){card.recycleThumbnail();return;}
                XposedHelpers.setAdditionalInstanceField(state,KEY,new Session(state,display,wm,card,surface,active));
            }
        }catch(Throwable unavailable){card.recycleThumbnail();}
    }
    private static final class Session {
        final Object state,display,lock;
        final Object origin;
        final Context context;
        final Handler handler;
        final ContentDragShadow card;
        final SurfaceControl surface;
        final BooleanSupplier active;
        final Point size=new Point(),touch=new Point();
        final float unit;
        float currentX,currentY;
        ValueAnimator animator;
        boolean stopped;
        int generation;
        final Runnable expiry=this::stop;
        Session(Object state,Object display,Object wm,ContentDragShadow card,SurfaceControl surface,BooleanSupplier active){
            this.state=state;this.display=display;this.card=card;this.surface=surface;this.active=active;
            lock=XposedHelpers.getObjectField(wm,"mGlobalLock");context=(Context)XposedHelpers.getObjectField(wm,"mContext");
            Object source=((java.util.Map<?,?>)XposedHelpers.getObjectField(wm,"mWindowMap")).get(XposedHelpers.getObjectField(state,"mLocalWin"));
            if(source!=null && WindowIdentity.ownsPicker(source,display)){
                Object attrs=XposedHelpers.getObjectField(source,"mAttrs");
                if("OneStepLitePortalSource".equals(String.valueOf(XposedHelpers.callMethod(attrs,"getTitle")))){
                    Object focus=XposedHelpers.getObjectField(display,"mCurrentFocus");
                    if(focus!=null && java.util.Objects.equals(String.valueOf(XposedHelpers.getObjectField(attrs,"accessibilityTitle")),
                            XposedHelpers.getObjectField(XposedHelpers.getObjectField(focus,"mAttrs"),"packageName")))source=focus;
                }
            }
            origin=source;
            try{currentX=XposedHelpers.getFloatField(state,"mCurrentDisplayX");currentY=XposedHelpers.getFloatField(state,"mCurrentDisplayY");}
            catch(RuntimeException older){currentX=XposedHelpers.getFloatField(state,"mCurrentX");currentY=XposedHelpers.getFloatField(state,"mCurrentY");}
            handler=(Handler)XposedHelpers.getObjectField(wm,"mH");card.onProvideShadowMetrics(size,touch);unit=(size.y-touch.y)/3f;
            handler.postDelayed(expiry,60_000L);
        }
        boolean valid(){return !stopped && active.getAsBoolean() && surface.isValid()
                && XposedHelpers.getObjectField(state,"mSurfaceControl")==surface;}
        void move(float x,float y){
            if(!valid())return;
            currentX=x;currentY=y;
            float bottom=context.getResources().getDisplayMetrics().heightPixels;
            try{Object ime=XposedHelpers.getObjectField(display,"mInputMethodWindow");
                if(ime!=null && Boolean.TRUE.equals(XposedHelpers.callMethod(ime,"isVisible")))
                    bottom=Math.min(bottom,((Rect)XposedHelpers.callMethod(ime,"getFrame")).top);
            }catch(Throwable unavailable){/* Display bottom remains the conservative fallback. */}
            boolean down=y>bottom-(card.pointsDown()?200:140)*unit;
            if(!card.pointDown(down))return;
            repaint();int token=++generation;
            float from=XposedHelpers.getFloatField(state,"mThumbOffsetY"),to=down?3*unit:touch.y;
            handler.post(()->{
                synchronized(lock){if(!valid() || token!=generation)return;if(animator!=null)animator.cancel();
                    animator=ValueAnimator.ofFloat(from,to);animator.setDuration(ValueAnimator.areAnimatorsEnabled()?180:0);
                    animator.addUpdateListener(frame->{synchronized(lock){if(!valid() || token!=generation)return;
                        float offset=(Float)frame.getAnimatedValue();XposedHelpers.setFloatField(state,"mThumbOffsetY",offset);
                        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
                            tx.setPosition(surface,currentX-touch.x,currentY-offset).apply();}
                    }});animator.start();}
            });
        }
        void repaint(){
            Surface buffer=new Surface(surface);
            try{Canvas canvas=buffer.lockCanvas(null);try{
                canvas.drawColor(android.graphics.Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR);card.onDrawShadow(canvas);
            }finally{buffer.unlockCanvasAndPost(canvas);}}finally{buffer.release();}
        }
        void drop(XC_MethodHook.MethodHookParam call){
            if(!valid())return;
            if(origin!=null && java.util.Objects.equals(call.args[0],XposedHelpers.getObjectField(origin,"mInputChannelToken"))){reject(call);return;}
            if(!card.pointsDown())return;
            int rawIndex=call.args.length==5?3:1;
            float x=((Number)call.args[rawIndex]).floatValue(),raw=((Number)call.args[rawIndex+1]).floatValue();
            float y=raw-XposedHelpers.getFloatField(state,"mThumbOffsetY")+size.y-3*unit;
            Object target=XposedHelpers.callMethod(display,"getTouchableWinAtPointLocked",x,y);
            Object notified=XposedHelpers.getObjectField(state,"mNotifiedWindows");
            if(target==null || target==origin || !Boolean.TRUE.equals(XposedHelpers.callMethod(notified,"contains",target))){reject(call);return;}
            call.args[0]=XposedHelpers.getObjectField(target,"mInputChannelToken");
            if(call.args.length==5){
                call.args[1]=XposedHelpers.callMethod(target,"translateToWindowX",x);
                call.args[2]=XposedHelpers.callMethod(target,"translateToWindowY",y);
                call.args[3]=x;call.args[4]=y;
            }else{call.args[1]=x;call.args[2]=y;}
        }
        void reject(XC_MethodHook.MethodHookParam call){
            call.setResult(false);
            try{XposedHelpers.callMethod(state,"endDragLocked",false,false);}
            catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"native drag cancellation unavailable",unavailable);}
        }
        void stop(){if(stopped)return;stopped=true;generation++;handler.removeCallbacks(expiry);
            handler.post(()->{if(animator!=null){animator.cancel();animator=null;}card.recycleThumbnail();});}
    }
}
