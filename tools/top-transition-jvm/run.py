"""Run the actual center bridge with controllable draw, animation and native callbacks."""
from pathlib import Path
import os, subprocess, sys
import sys as _sys, pathlib as _pathlib; _sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1])); from source_paths import package_dir
root=Path(__file__).resolve().parents[2]
src=package_dir(os.environ.get('ONESTEPLITE_TRANSITION_SOURCE_ROOT',str(root)))
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/content/Context.java':'''package android.content;public class Context {
 public static final String WINDOW_SERVICE="window";public final android.view.WindowManager windows=new android.view.WindowManager();
 public Object getSystemService(String name){return windows;}public ClassLoader getClassLoader(){return getClass().getClassLoader();}}''',
'android/graphics/Rect.java':'''package android.graphics;public class Rect {
 public int left,top,right,bottom;public Rect(int a,int b,int c,int d){left=a;top=b;right=c;bottom=d;}
 public Rect(Rect r){this(r.left,r.top,r.right,r.bottom);}public int width(){return right-left;}public int height(){return bottom-top;}
 public float exactCenterX(){return (left+right)*.5f;}public float exactCenterY(){return (top+bottom)*.5f;}}''',
'android/graphics/Bitmap.java':'''package android.graphics;public class Bitmap {public enum Config {ARGB_8888}public boolean isRecycled(){return false;}public Bitmap copy(Config c,boolean mutable){return new Bitmap();}}''',
'android/graphics/Paint.java':'''package android.graphics;public class Paint {public static final int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2;public Paint(int f){}}''',
'android/graphics/PixelFormat.java':'''package android.graphics;public class PixelFormat {public static final int TRANSLUCENT=-3;}''',
'android/graphics/Canvas.java':'''package android.graphics;public class Canvas {
 public static int colorDraws,bitmapDraws;public float scale,pivotX,pivotY;public Rect image;
 public int save(){return 1;}public void clipRect(Rect r){}public void restoreToCount(int n){}public void drawColor(int c){colorDraws++;}
 public void scale(float x,float y,float px,float py){scale=x;pivotX=px;pivotY=py;}
 public void drawBitmap(Bitmap b,Object from,Rect r,Paint p){bitmapDraws++;image=r;}}''',
'android/view/SurfaceControl.java':'''package android.view;import android.graphics.Rect;public class SurfaceControl {
 public static class Transaction {
  public static Transaction last;public Object surface;public float sx,sy,x,y,alpha=1;public Rect crop;public int applies,closes;
  public Transaction(){last=this;}
  public Transaction setPosition(Object s,float px,float py){x=px;y=py;return this;}
  public Transaction setMatrix(Object s,float dsdx,float dtdx,float dsdy,float dtdy){surface=s;sx=dsdx;sy=dtdy;return this;}
  public Transaction setWindowCrop(Object s,Rect r){crop=r;return this;}
  public Transaction setAlpha(Object s,float a){surface=s;alpha=a;return this;}
  public void apply(){applies++;}public void close(){closes++;}
 }}''',
'android/view/View.java':'''package android.view;import java.util.*;import android.content.Context;import android.graphics.Canvas;
public class View {
 public static final int VISIBLE=0,INVISIBLE=4,GONE=8,IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS=4;
 public static final List<Runnable> frames=new ArrayList<>(),timers=new ArrayList<>();
 public interface OnAttachStateChangeListener {void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}
 public interface Touch {boolean onTouch(View v,Object event);}
 private final Context ctx;public OnAttachStateChangeListener attachment;public int visibility;
 public float pivotX,pivotY,scaleX=1,scaleY=1,alpha=1;
 public int getWidth(){return 1080;}public int getHeight(){return 622;}
 public float getPivotX(){return pivotX;}public float getPivotY(){return pivotY;}
 public float getScaleX(){return scaleX;}public float getScaleY(){return scaleY;}
 public void setPivotX(float f){pivotX=f;}public void setPivotY(float f){pivotY=f;}
 public void setScaleX(float f){scaleX=f;}public void setScaleY(float f){scaleY=f;}
 public float getAlpha(){return alpha;}public void setAlpha(float f){alpha=f;}
 public void setTranslationY(float f){}public void setClipBounds(android.graphics.Rect r){}
 public Object animate(){return null;}
 public View(Context c){ctx=c;}public Context getContext(){return ctx;}
 public void setOnTouchListener(Touch t){}public void setImportantForAccessibility(int x){}public void invalidate(){}
 public void setVisibility(int v){visibility=v;}protected void onDraw(Canvas c){}public void draw(Canvas c){onDraw(c);}
 public void postOnAnimation(Runnable r){frames.add(r);}public void postDelayed(Runnable r,long ms){timers.add(r);}
 public void removeCallbacks(Runnable r){timers.remove(r);}public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){attachment=l;}
 public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l){if(attachment==l)attachment=null;}
 public static void drain(){while(!frames.isEmpty())frames.remove(0).run();}
}''',
'android/widget/FrameLayout.java':'''package android.widget;public class FrameLayout extends android.view.View {public FrameLayout(android.content.Context c){super(c);}}''',
'android/view/Gravity.java':'''package android.view;public class Gravity {public static final int TOP=48,START=8388611;}''',
'android/view/WindowManager.java':'''package android.view;public class WindowManager {
 public View view;public LayoutParams params;public int adds,removes;
 public void addView(View v,LayoutParams p){view=v;params=p;adds++;}public void removeViewImmediate(View v){removes++;}
 public static class LayoutParams {
  public static final int TYPE_APPLICATION_OVERLAY=2038,FLAG_NOT_FOCUSABLE=8,FLAG_LAYOUT_IN_SCREEN=256,FLAG_LAYOUT_NO_LIMITS=512,LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS=3;
  public int gravity,windowAnimations,layoutInDisplayCutoutMode;public String title;
  public LayoutParams(int w,int h,int type,int flags,int format){}public void setFitInsetsTypes(int n){}public void setTitle(String s){title=s;}}
}''',
'android/animation/Animator.java':'''package android.animation;public class Animator {}''',
'android/animation/AnimatorListenerAdapter.java':'''package android.animation;public class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}}''',
'android/animation/ValueAnimator.java':'''package android.animation;import java.util.*;import java.util.function.Consumer;
public class ValueAnimator extends Animator {
 public static final List<ValueAnimator> all=new ArrayList<>();public float value,start,end;public long duration;
 Consumer<ValueAnimator> update;AnimatorListenerAdapter listener;public boolean cancelled;
 public static ValueAnimator ofFloat(float a,float b){ValueAnimator v=new ValueAnimator();v.start=a;v.end=b;all.add(v);return v;}
 public void setDuration(long d){duration=d;}public void setInterpolator(Object x){}
 public void addUpdateListener(Consumer<ValueAnimator> c){update=c;}public void addListener(AnimatorListenerAdapter l){listener=l;}
 public Object getAnimatedValue(){return value;}public void start(){step(0);}public void step(float f){value=start+(end-start)*f;update.accept(this);}
 public void end(){step(1);listener.onAnimationEnd(this);}public void cancel(){cancelled=true;listener.onAnimationEnd(this);}
}''',
'android/view/animation/AccelerateDecelerateInterpolator.java':'''package android.view.animation;public class AccelerateDecelerateInterpolator {}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;import java.lang.reflect.*;public class XposedHelpers {
 public static Object getObjectField(Object o,String n){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static int getIntField(Object o,String n){return(Integer)getObjectField(o,n);}
 public static final Object surface=new Object();public static Object callMethod(Object o,String n,Object...args){
  if(n.equals("isValid"))return true;
  for(Method m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)
   try{return m.invoke(o,args);}catch(Throwable ignored){}
  return surface;}}''',
'io/github/zerohomers0/onesteplite/HostWindowPolicy.java':'''package io.github.zerohomers0.onesteplite;class HostWindowPolicy {static void configure(Object p){}}''',
'io/github/zerohomers0/onesteplite/HyperOsStyle.java':'''package io.github.zerohomers0.onesteplite;class HyperOsStyle {static int page(Object c){return 0xff101010;}}''',
'io/github/zerohomers0/onesteplite/SwapPreview.java':'''package io.github.zerohomers0.onesteplite;import android.graphics.*;class SwapPreview {
 static int captures;static Object exclusion;static boolean fail;
 static Bitmap captureRegion(ClassLoader c,int w,int h,Rect r)throws Throwable{return captureRegion(c,w,h,r,null);}
 static Bitmap captureRegion(ClassLoader c,int w,int h,Rect r,Object exclude)throws Throwable {
  captures++;exclusion=exclude;if(fail)throw new IllegalStateException("secure/unavailable");return new Bitmap();}}
''',
'io/github/zerohomers0/onesteplite/TopTransitionRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.animation.*;import android.graphics.*;import android.view.*;import android.widget.*;import java.util.concurrent.atomic.AtomicBoolean;
public class TopTransitionRegression {
 static int checks,handoffs,finishes,restores;static AtomicBoolean valid;static FrameLayout host;static android.content.Context ctx;
 static Rect upper=new Rect(0,0,1080,622),main=new Rect(273,622,1080,2460);
 static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static void reset(){ValueAnimator.all.clear();View.frames.clear();View.timers.clear();SwapPreview.fail=false;SwapPreview.captures=0;
  handoffs=finishes=restores=0;Canvas.colorDraws=Canvas.bitmapDraws=0;android.view.SurfaceControl.Transaction.last=null;
  valid=new AtomicBoolean(true);ctx=new android.content.Context();host=new FrameLayout(ctx);}
 static TopTaskTransition make(Runnable transfer)throws Throwable {
  return new TopTaskTransition(host,1080,2460,valid::get,transfer,v->finishes++,s->{});}
 static void handoff(TopTaskTransition run){run.start();check(ValueAnimator.all.isEmpty(),"no transform before native handoff");
  ctx.windows.view.draw(new Canvas());View.drain();}
 public static void main(String[]args)throws Throwable {
  reset();var run=make(()->handoffs++);run.start();run.close();ctx.windows.view.draw(new Canvas());View.drain();
  check(handoffs==0&&ValueAnimator.all.isEmpty()&&finishes==1,"cancel before first draw never transfers a task");
  reset();run=make(()->handoffs++);run.start();valid.set(false);ctx.windows.view.draw(new Canvas());View.drain();
  check(handoffs==0&&ctx.windows.removes==1&&finishes==1,"scene loss before handoff frees input blocker");
  reset();run=make(()->handoffs++);handoff(run);View.timers.get(0).run();run.reveal();
  check(handoffs==1&&finishes==1&&ValueAnimator.all.isEmpty(),"timeout frees overlay and late native callback starts nothing");
  reset();run=make(()->{throw new IllegalStateException("native refusal");});handoff(run);
  check(ctx.windows.removes==1&&finishes==1,"handoff exception frees input overlay");
  reset();run=make(()->handoffs++);run.start();host.attachment.onViewDetachedFromWindow(host);View.drain();
  check(finishes==1&&handoffs==0&&ctx.windows.removes==1,"Host detach retires live bridge");
  reset();run=new TopTaskTransition(host,1080,2460,valid::get,
   ()->handoffs++,v->finishes++,s->{});run.start();ctx.windows.view.draw(new Canvas());View.drain();
  check(handoffs==1&&SwapPreview.captures==0&&Canvas.colorDraws==0&&Canvas.bitmapDraws==0,
   "live center handoff draws neither wallpaper nor a bitmap snapshot");
  Object liveSurface=new Object();run.setLiveSurface(liveSurface);run.reveal();run.reveal();
  check(ValueAnimator.all.size()==1&&android.view.SurfaceControl.Transaction.last.alpha==0f,"native return starts fully transparent");
  var expand=ValueAnimator.all.get(0);expand.step(.5f);
  var tx=android.view.SurfaceControl.Transaction.last;
  check(tx.alpha==.5f&&tx.sx==0f&&tx.crop==null,"return changes alpha only, preserving native task geometry");
  expand.end();check(android.view.SurfaceControl.Transaction.last.alpha==1f&&finishes==1&&ctx.windows.removes==1,"return completes opaque and frees its input blocker");
  run.setLiveSurface(new Object());check(android.view.SurfaceControl.Transaction.last.alpha==1f,"late native handoff cannot strand a retired bridge at alpha zero");
  for(int reason=0;reason<5;reason++) {
   reset();AtomicBoolean sourceCurrent=new AtomicBoolean(true);View destination=new View(ctx);destination.alpha=0f;
   Object source=new Object();run=make(()->handoffs++);run.collect(source,sourceCurrent::get,destination);handoff(run);
   check(ValueAnimator.all.isEmpty(),"source waits for native background preparation");
   run.collectionReady();View.drain();var fade=ValueAnimator.all.get(0);fade.step(.4f);
   check(android.view.SurfaceControl.Transaction.last.alpha==.6f,"source fades at its existing geometry");
   if(reason==0)run.close();
   else if(reason==1)View.timers.get(0).run();
   else if(reason==2){valid.set(false);fade.step(.5f);}
   else if(reason==3){sourceCurrent.set(false);fade.step(.5f);}
   else {
    fade.end();check(handoffs==1,"admission starts once after source faded out");
    sourceCurrent.set(false);run.setLiveSurface(new Object());destination.alpha=1f;run.reveal();
    ValueAnimator.all.get(1).step(.4f);run.close();
    check(destination.alpha==1f&&android.view.SurfaceControl.Transaction.last.alpha==1f,"admitted target survives an interrupted reveal");
   }
   tx=android.view.SurfaceControl.Transaction.last;fade.end();
   check(finishes==1&&ctx.windows.removes==1,"collection cancellation releases once "+reason);
   check(android.view.SurfaceControl.Transaction.last==tx,"retired source animator never writes another frame");
   if(reason<3)check(tx.alpha==1f&&handoffs==0,"unadmitted source is restored without native transfer");
  }
  reset();View destination=new View(ctx);destination.alpha=0f;run=make(()->handoffs++);
  run.collect(new Object(),()->true,destination);run.collectionReady();handoff(run);ValueAnimator.all.get(0).end();
  run.setLiveSurface(new Object());run.reveal();ValueAnimator.all.get(1).step(.5f);
  check(run.ownsTopOpacity()&&destination.alpha==1f&&android.view.SurfaceControl.Transaction.last.alpha==.5f,"admitted child owns the fade under an opaque destination");
  ValueAnimator.all.get(1).end();check(destination.alpha==1f&&finishes==1,"completed admission stays visible");
  for(int reason=0;reason<4;reason++) {
   reset();AtomicBoolean bound=new AtomicBoolean(true);run=make(()->handoffs++);handoff(run);
   run.fadeOut(new Object(),bound::get,()->restores++);var outgoing=ValueAnimator.all.get(0);outgoing.step(.4f);
   check(android.view.SurfaceControl.Transaction.last.alpha==.6f&&run.ownsTopOpacity(),"outgoing strip uses the managed fade");
   if(reason==0)run.close();else if(reason==1)View.timers.get(0).run();
   else if(reason==2){valid.set(false);outgoing.step(.5f);}else{bound.set(false);outgoing.step(.5f);}
   tx=android.view.SurfaceControl.Transaction.last;outgoing.end();
   check(restores==0&&finishes==1&&ctx.windows.removes==1,"cancelled outgoing fade never returns a native task "+reason);
   check(android.view.SurfaceControl.Transaction.last==tx,"retired outgoing animator never writes again");
   if(reason<3)check(tx.alpha==1f,"interrupted strip is opaque again");
  }
  for(int reason=0;reason<3;reason++) {
   reset();run=make(()->handoffs++);run.collect(new Object(),()->true,new View(ctx));handoff(run);
   if(reason==0)run.close();else if(reason==1)View.timers.get(0).run();else valid.set(false);
   check(!run.collectionReady(),"late readiness cannot revive a cancelled collection");
   View.drain();check(ValueAnimator.all.isEmpty()&&handoffs==0&&finishes==1,"waiting cancellation never claims a task");
  }
  reset();View exit=new View(ctx);run=make(()->handoffs++);handoff(run);run.followOpacity(exit);
  run.fadeOut(new Object(),()->true,()->{check(exit.visibility==View.INVISIBLE&&exit.alpha==0f,"exit plate disappears before native handback");restores++;});
  ValueAnimator.all.get(0).step(.5f);check(exit.alpha==.5f,"exit plate shares the outgoing fade frame");
  ValueAnimator.all.get(0).end();check(restores==1,"native return starts only after the exit plate is hidden");
  run.close();check(exit.alpha==1f&&exit.visibility==View.VISIBLE,"cancelled native return restores the retained exit control");
  System.out.println("Production upper center transition checks="+checks+" passed (visual timing still needs device)");
 }
}'''
}
paths=[]
for name,text in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'TopTaskTransition.java'))
paths.append(str(src/'TopTaskReturnPolicy.java'))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.TopTransitionRegression'],check=True)

host=Path(os.environ.get('ONESTEPLITE_COLLECT_HOST_SOURCE',str(src/'WorkspaceHost.java'))).read_text(encoding='utf-8-sig')
def member(signature):
    start=host.index(signature)
    end=host.index('{',start)+1;depth=1
    while depth:
        depth+=(host[end]=='{')-(host[end]=='}');end+=1
    return host[start:end]
host_body='''package io.github.zerohomers0.onesteplite;
import android.view.*;import android.widget.*;import android.graphics.Rect;import java.util.function.*;import de.robv.android.xposed.XposedHelpers;
public class WorkspaceHost {
 static final int SLOT_TOP=4,ACTIVE=1;static int checks;int scene=ACTIVE,screenW=1080,screenH=2460;
 boolean screenOn=true,keyguardLocked,suspendRequested,topFace=true;long sceneEpoch=1;int claimed,finished;
 android.content.Context ctx=new android.content.Context();FrameLayout root=new FrameLayout(ctx);
 View topBar=new View(ctx),topTaskPane=new View(ctx);Layout layout=new Layout();
 static class Layout {int topStrip=622;boolean tabletLandscape;int mainHeight=1838;int mainDispHeight=1838;int mainOffsetX=0,mainOffsetY=622;}
 void updateCellSurfaceVisibility(Slot upper){}void updateTopTaskGeometry(){}float mainScale(){return .5f;}
 TopTaskTransition topTransition;TopTaskState topTaskState=new TopTaskState();int opacityWrites,homeRequests;boolean translucent,failOpacity,failHome;
 java.util.List<Object> appliedOrder=java.util.List.of();
 static class Main {boolean post(Runnable r){r.run();return true;}}Main main=new Main();
 boolean executeOnShell(Runnable r){r.run();return true;}
 static class Slot {View view;Object controller;boolean occupied,promoting;}
 static class Task {int id=42;Object token="token";boolean matches(Object o){return o instanceof Info&&((Info)o).taskId==id&&token.equals(((Info)o).token);}}
 static class TopCandidate {Task task=new Task();TopTaskState.Key key=new TopTaskState.Key(42,0,"token");}
 public static class Info {public int taskId=42,userId,mode=1;public Object token="token";boolean visible=true;}
 public static class Entry {Info info=new Info();Object leash=new Object();public Object getLeash(){return leash;}public Object getTaskInfo(){return info;}}
 public static class Organizer {Object mLock=new Object();android.util.SparseArray<Entry> mTasks=new android.util.SparseArray<>();}
 public static class Controller {Object mTaskOrganizer;}
 Slot[] slots={new Slot(),new Slot(),new Slot(),new Slot(),new Slot()};TopCandidate selected=new TopCandidate();
 WorkspaceHost(){Controller c=new Controller();Organizer o=new Organizer();o.mTasks.put(42,new Entry());c.mTaskOrganizer=o;
  slots[4].controller=c;slots[4].view=new View(ctx);}
 Entry entry(){return ((Organizer)((Controller)slots[4].controller).mTaskOrganizer).mTasks.get(42);}
 Object getSurfaceField(Object controller,String name){return entry().leash;}
 void updateTopBarState(){}void settleTopFace(boolean task){topFace=task;}
 Object rootInfo(Task task){return entry().info;}
 Object homeForCollection(Task task)throws Exception{homeRequests++;if(failHome)throw new Exception("Home unavailable");return "home";}
 boolean applyWct(String what,Object wct){opacityWrites++;if(failOpacity)return false;
  appliedOrder=new java.util.ArrayList<>(((android.window.WindowContainerTransaction)wct).order);
  translucent=((android.window.WindowContainerTransaction)wct).translucent;return true;}
 void commitPreparation(){if(SyncPreparation.wct==null)return;
  if(applyWct("native-sync",SyncPreparation.wct))SyncPreparation.ready.run();
  else SyncPreparation.failed.accept(new IllegalStateException("sync failed"));}
 TopCandidate currentTopCandidate(){return selected;}boolean topReady(){return true;}
 boolean ordinaryRoot(Object o,int mode){return o instanceof Info&&((Info)o).mode==mode;}
 boolean mainTaskVisible(Object o){return ((Info)o).visible;}
 void claimTopTask(TopCandidate c,boolean manual){claimed++;}void finishPendingSuspend(){finished++;}
 void cancelTopTaskTransition(){if(topTransition!=null)topTransition.close();}static void log(String s){}
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
'''+member('    private void captureMainIntoTop(')
for signature in ('    private boolean startTopCollectionTransition(',
                  '    private boolean topCollectionSourceCurrent(',
                  '    private boolean setTopCollectionTranslucent(',
                  '    private Object topCollectionTransaction(',
                  '    private void revealTopTaskTransition('):
    if signature in host:host_body+=member(signature)
for signature in ('    private boolean startTopTaskTransition(boolean intoTop, boolean liveMainReveal, Runnable transfer)',):
    host_body+=member(signature)
signature='    private boolean startTopTaskTransition(boolean intoTop, boolean liveMainReveal, Runnable transfer,'
if signature in host:host_body+=member(signature)
host_body+='''public static void main(String[]args){
 ValueAnimatorReset();WorkspaceHost h=new WorkspaceHost();h.captureMainIntoTop();
 check(h.topTransition!=null&&h.claimed==0,"actual main capture starts the live bridge before claiming");
 check(h.opacityWrites==0,"source does not expose the background before its native sync");
 check(((android.window.WindowContainerTransaction)SyncPreparation.wct).order.equals(java.util.List.of("home","token"))&&h.homeRequests==1,
  "actual Home must be ordered directly behind the retained source in its opacity transaction before shrinking");
 h.ctx.windows.view.draw(new android.graphics.Canvas());View.drain();
 check(android.animation.ValueAnimator.all.isEmpty()&&h.claimed==0,"production entry still waits after overlay draw");
 h.commitPreparation();View.drain();
 check(h.translucent&&h.opacityWrites==1,"background is revealed only by the completed native preparation");
 check(android.animation.ValueAnimator.all.size()==1&&h.claimed==0,"production admission waits for source collapse");
 android.animation.ValueAnimator.all.get(0).end();check(h.claimed==1,"production admission claims exactly once after collapse");
 check(android.view.SurfaceControl.Transaction.last.alpha==0,"production handoff keeps the shared surface invisible while native admission waits");
 h.entry().info.mode=6;h.slots[4].occupied=true;h.revealTopTaskTransition();
 check(android.animation.ValueAnimator.all.size()==2,"native admission restores the real destination expansion");
 android.animation.ValueAnimator.all.get(1).step(.5f);
 check(android.view.SurfaceControl.Transaction.last.alpha==.5f,"production reveal keeps the shared surface in step with the fade");
 android.animation.ValueAnimator.all.get(1).end();
 check(h.topTransition==null&&h.finished==1,"production completion releases its own transition");
 check(h.opacityWrites==1&&h.translucent,"new native TOP ownership keeps its own translucent policy");
 for(int mutation=0;mutation<6;mutation++){
  ValueAnimatorReset();h=new WorkspaceHost();
  switch(mutation){case 0:h.entry().info.userId=999;break;case 1:h.entry().info.token="other";break;
   case 2:h.entry().info.taskId=43;break;case 3:h.entry().info.mode=6;break;case 4:h.entry().info.visible=false;break;
   case 5:h.slots[4].view=null;break;}
  h.captureMainIntoTop();check(h.topTransition==null&&android.animation.ValueAnimator.all.isEmpty(),"invalid source cannot acquire an animation surface "+mutation);
 }
 ValueAnimatorReset();h=new WorkspaceHost();h.captureMainIntoTop();h.commitPreparation();h.ctx.windows.view.draw(new android.graphics.Canvas());View.drain();
 android.animation.ValueAnimator.all.get(0).step(.2f);h.entry().leash=new Object();
 var tx=android.view.SurfaceControl.Transaction.last;android.animation.ValueAnimator.all.get(0).step(.8f);
 check(h.claimed==0&&h.topTransition==null&&android.view.SurfaceControl.Transaction.last==tx,
  "canonical replacement interrupts before handoff and never resets the replacement");
 check(!h.translucent&&h.opacityWrites==2,"cancelled fullscreen source regains its native opacity even after canonical replacement");
 ValueAnimatorReset();h=new WorkspaceHost();h.captureMainIntoTop();h.commitPreparation();h.topTransition.close();
 check(!h.translucent&&h.opacityWrites==2,"cancel before first draw restores temporary translucency");
 check(h.appliedOrder.isEmpty()&&h.homeRequests==1,"opacity restoration does not reorder Home or another task");
 ValueAnimatorReset();h=new WorkspaceHost();h.captureMainIntoTop();h.commitPreparation();View.timers.get(0).run();
 check(!h.translucent&&h.opacityWrites==2,"watchdog restores temporary source translucency");
 ValueAnimatorReset();h=new WorkspaceHost();h.failOpacity=true;h.captureMainIntoTop();h.commitPreparation();
 check(h.topTransition==null&&android.animation.ValueAnimator.all.isEmpty(),"failed translucency preparation never starts source shrinking");
 ValueAnimatorReset();h=new WorkspaceHost();h.failHome=true;h.captureMainIntoTop();
 check(h.topTransition==null&&!h.translucent&&h.claimed==0&&android.animation.ValueAnimator.all.isEmpty(),
  "missing canonical Home rejects shrinking before exposing a different background app");
 ValueAnimatorReset();h=new WorkspaceHost();h.captureMainIntoTop();h.topTransition.close();h.commitPreparation();View.drain();
 check(h.topTransition==null&&!h.translucent&&h.claimed==0&&android.animation.ValueAnimator.all.isEmpty(),
  "late native completion restores opacity after cancellation without reviving the bridge");
 System.out.println("Production Host collection entry checks="+checks+" passed");
 }
 static void ValueAnimatorReset(){android.animation.ValueAnimator.all.clear();View.frames.clear();View.timers.clear();SyncPreparation.wct=null;}
}'''
extra={
 'io/github/zerohomers0/onesteplite/WorkspaceHost.java':host_body,
 'io/github/zerohomers0/onesteplite/SyncPreparation.java':'''package io.github.zerohomers0.onesteplite;
 class SyncPreparation {static Object wct;static Runnable ready;static java.util.function.Consumer<Throwable> failed;
 static void submit(Object c,Object w,java.util.concurrent.Executor ui,Runnable r,java.util.function.Consumer<Throwable> f){
 wct=w;ready=()->ui.execute(r);failed=e->ui.execute(()->f.accept(e));}}''',
 'android/util/SparseArray.java':'''package android.util;public class SparseArray<T>{
 java.util.Map<Integer,T>m=new java.util.HashMap<>();public T get(int i){return m.get(i);}public void put(int i,T v){m.put(i,v);}}''',
 'android/widget/Toast.java':'''package android.widget;public class Toast {public static final int LENGTH_SHORT=0;
 public static Toast makeText(Object c,String s,int d){return new Toast();}public void show(){}}''',
 'android/window/WindowContainerTransaction.java':'''package android.window;public class WindowContainerTransaction {
 public boolean translucent;public java.util.List<Object> order=new java.util.ArrayList<>();
 public void reorder(Object token,boolean onTop){if(!onTop)throw new AssertionError("unexpected reorder direction");order.add(token);}
 public void setForceTranslucent(Object token,boolean value){translucent=value;}}'''
}
extra_paths=[]
for name,text in extra.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8');extra_paths.append(str(p))
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-cp',str(out),'-d',str(out),
                *extra_paths,str(src/'TopTaskState.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.WorkspaceHost'],check=True)
