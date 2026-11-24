"""Replay the actual WM hotspot session, arrow DROP routing and cancellation ownership."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/os/Handler.java':'''package android.os;public class Handler{public java.util.Set<Runnable> delayed=new java.util.HashSet<>();public void post(Runnable r){r.run();}public void postDelayed(Runnable r,long t){delayed.add(r);}public void removeCallbacks(Runnable r){delayed.remove(r);}}''',
'android/os/PersistableBundle.java':'''package android.os;public class PersistableBundle{public void putString(String k,String v){}}''',
'android/content/ClipData.java':'''package android.content;public class ClipData{public Description getDescription(){return new Description();}public static class Description{public android.os.PersistableBundle getExtras(){return new android.os.PersistableBundle();}public void setExtras(Object o){}}}''',
'android/content/Context.java':'''package android.content;public class Context{public Resources getResources(){return new Resources();}public static class Resources{public Metrics getDisplayMetrics(){return new Metrics();}}public static class Metrics{public int heightPixels=1000;}}''',
'android/graphics/Point.java':'package android.graphics;public class Point{public int x,y;}',
'android/graphics/Rect.java':'package android.graphics;public class Rect{public int top=700;}',
'android/graphics/Canvas.java':'package android.graphics;public class Canvas{public void drawColor(int c,Object m){}}',
'android/graphics/Color.java':'package android.graphics;public class Color{public static int TRANSPARENT=0;}',
'android/graphics/PorterDuff.java':'package android.graphics;public class PorterDuff{public static class Mode{public static Object CLEAR=new Object();}}',
'android/view/Surface.java':'''package android.view;public class Surface{public Surface(SurfaceControl s){}public android.graphics.Canvas lockCanvas(Object r){return new android.graphics.Canvas();}public void unlockCanvasAndPost(Object o){}public void release(){}}''',
'android/view/SurfaceControl.java':'''package android.view;public class SurfaceControl{public float x,y;public boolean valid=true;public boolean isValid(){return valid;}public static class Transaction implements AutoCloseable{public Transaction setPosition(SurfaceControl s,float x,float y){s.x=x;s.y=y;return this;}public void apply(){}public void close(){}}}''',
'android/animation/ValueAnimator.java':'''package android.animation;public class ValueAnimator{public interface Update{void update(ValueAnimator a);}public static ValueAnimator latest;public float from,to,value;public boolean cancelled;Update update;
public static boolean areAnimatorsEnabled(){return true;}public static ValueAnimator ofFloat(float a,float b){latest=new ValueAnimator();latest.from=a;latest.to=b;latest.value=a;return latest;}
public void setDuration(long t){}public void addUpdateListener(Update u){update=u;}public Object getAnimatedValue(){return value;}public void start(){tick(0);}public void tick(float p){value=from+(to-from)*p;update.update(this);}public void cancel(){cancelled=true;}}''',
'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;public class XC_MethodHook{protected void beforeHookedMethod(MethodHookParam p){}public void fire(MethodHookParam p){beforeHookedMethod(p);}public static class MethodHookParam{public Object thisObject;public Object[] args;public Object result;public void setResult(Object r){result=r;}}}''',
'de/robv/android/xposed/XposedBridge.java':'''package de.robv.android.xposed;public class XposedBridge{public static java.util.Map<String,XC_MethodHook> hooks=new java.util.HashMap<>();public static java.util.Set<Object> hookAllMethods(Class<?> c,String m,XC_MethodHook h){hooks.put(m,h);return java.util.Set.of(h);}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers{public static java.util.Map<Object,java.util.Map<String,Object>> extras=new java.util.IdentityHashMap<>();
public static Class<?> findClass(String n,ClassLoader l){return Object.class;}public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
public static float getFloatField(Object o,String n){return (Float)getObjectField(o,n);}public static void setFloatField(Object o,String n,float v){try{o.getClass().getField(n).setFloat(o,v);}catch(Exception e){throw new RuntimeException(e);}}
public static Object getAdditionalInstanceField(Object o,String n){return extras.getOrDefault(o,java.util.Map.of()).get(n);}public static void setAdditionalInstanceField(Object o,String n,Object v){extras.computeIfAbsent(o,k->new java.util.HashMap<>()).put(n,v);}public static void removeAdditionalInstanceField(Object o,String n){extras.get(o).remove(n);}
public static Object callMethod(Object o,String n,Object...args){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)return m.invoke(o,args);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}}''',
'io/github/zerohomers0/onesteplite/NativeGeometryRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.graphics.*;import android.os.*;import android.view.*;import android.animation.*;import de.robv.android.xposed.*;import java.util.*;
class ContentDragShadow{boolean down;int recycled,draws;boolean pointsDown(){return down;}boolean pointDown(boolean v){if(v==down)return false;down=v;return true;}void onProvideShadowMetrics(Point size,Point touch){size.x=100;size.y=110;touch.x=50;touch.y=107;}void onDrawShadow(Canvas c){draws++;}void recycleThumbnail(){recycled++;}}
class WindowIdentity{static boolean ownsPicker(Object w,Object d){return false;}}
public class NativeGeometryRegression{
public static class State{public Object mLocalWin=new Object();public SurfaceControl mSurfaceControl=new SurfaceControl();public float mThumbOffsetY=107,mCurrentX=500,mCurrentY=870;public Set<Object> mNotifiedWindows=new HashSet<>();}
public static class Wm{public Map<Object,Object> mWindowMap=new HashMap<>();public Object mGlobalLock=new Object();public Handler mH=new Handler();public android.content.Context mContext=new android.content.Context();}
public static class Target{public Object mInputChannelToken=new Object();}
public static class Display{public Wm mWmService=new Wm();public Object mInputMethodWindow;public Target target=new Target();public Object getTouchableWinAtPointLocked(float x,float y){return y>=940&&y<1000?target:null;}}
public static class Controller{public State mDragState=new State();}
static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
static XC_MethodHook.MethodHookParam event(State state,Object...args){var p=new XC_MethodHook.MethodHookParam();p.thisObject=state;p.args=args;return p;}
static void move(State s,float y){s.mCurrentY=y;XposedBridge.hooks.get("updateDragSurfaceLocked").fire(event(s,true,500f,y));}
public static void main(String[] args){check(NativeDragGeometry.install(null,()->true),"known WM hooks installed");
Controller controller=new Controller();Display display=new Display();State state=controller.mDragState;ContentDragShadow card=new ContentDragShadow();
Target source=new Target();display.mWmService.mWindowMap.put(state.mLocalWin,source);
state.mNotifiedWindows.add(display.target);NativeDragGeometry.attach(controller,display,card,state.mSurfaceControl,()->true);
check(display.mWmService.mH.delayed.size()==1,"one bounded lifetime");move(state,850);check(!card.down&&card.draws==0,"middle keeps original hotspot");
var stationary=event(state,source.mInputChannelToken,500f,850f);XposedBridge.hooks.get("reportDropWindowLock").fire(stationary);check(Boolean.FALSE.equals(stationary.result),"release in source window cancels instead of resending");
move(state,870);check(card.down&&card.draws==1,"bottom flips the visible arrow");ValueAnimator down=ValueAnimator.latest;
down.tick(1);check(state.mThumbOffsetY==3,"finger moves to top of card");check(state.mSurfaceControl.y==867,"card body is below finger");
var drop=event(state,new Object(),500f,870f);XposedBridge.hooks.get("reportDropWindowLock").fire(drop);
check((Float)drop.args[2]==974f,"DROP uses exact lower arrow tip");check(drop.args[0]==display.target.mInputChannelToken,"input destination resolved at arrow rather than finger");
move(state,840);check(card.down&&card.draws==1,"hysteresis prevents flip chatter");
state.mNotifiedWindows.clear();drop=event(state,new Object(),500f,870f);XposedBridge.hooks.get("reportDropWindowLock").fire(drop);check(Boolean.FALSE.equals(drop.result),"unnotified window cannot receive redirected drop");
move(state,790);check(!card.down&&card.draws==2,"returning upward restores upper aim");ValueAnimator up=ValueAnimator.latest;up.tick(1);check(state.mThumbOffsetY==107,"restores original physical hotspot");
down.tick(.5f);check(state.mThumbOffsetY==107,"retired lower animation cannot override newer hotspot");
XposedBridge.hooks.get("closeLocked").fire(event(state));check(card.recycled==1&&display.mWmService.mH.delayed.isEmpty(),"close frees image and deadline");
up.tick(.5f);check(state.mThumbOffsetY==107,"late animation after close cannot mutate WM");
check(XposedHelpers.getAdditionalInstanceField(state,"content_drag_geometry")==null,"close removes session ownership");
System.out.println("Production native drag geometry checks="+checks+" passed (WM doubles; device pending)");}}
'''
}
paths=[]
for name,body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'NativeDragGeometry.java'));jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.NativeGeometryRegression'],check=True)
