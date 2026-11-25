"""Exercise native same-source rejection and fixed drop coordinates."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/util/Log.java':'package android.util;public class Log{public static int w(String t,String m,Throwable e){return 0;}}',
'android/os/Handler.java':'''package android.os;public class Handler{public java.util.Set<Runnable> delayed=new java.util.HashSet<>();public void post(Runnable r){r.run();}public void postDelayed(Runnable r,long t){delayed.add(r);}public void removeCallbacks(Runnable r){delayed.remove(r);}}''',
'android/os/PersistableBundle.java':'''package android.os;public class PersistableBundle{public void putString(String k,String v){}}''',
'android/content/ClipData.java':'''package android.content;public class ClipData{public String mime="text";public Description getDescription(){return new Description();}public class Description{public boolean hasMimeType(String type){return type.equals(mime+"/*");}public android.os.PersistableBundle getExtras(){return new android.os.PersistableBundle();}public void setExtras(Object o){}}}''',
'android/view/View.java':'package android.view;public class View{public static final int DRAG_FLAG_GLOBAL=256;}',
'android/content/Context.java':'''package android.content;public class Context{public Resources getResources(){return new Resources();}public static class Resources{public Metrics getDisplayMetrics(){return new Metrics();}}public static class Metrics{public int heightPixels=1000;}}''',
'android/graphics/Point.java':'package android.graphics;public class Point{public int x,y;}',
'android/graphics/Rect.java':'package android.graphics;public class Rect{public int top=700;}',
'android/graphics/Canvas.java':'package android.graphics;public class Canvas{public void drawColor(int c,Object m){}}',
'android/graphics/Color.java':'package android.graphics;public class Color{public static int TRANSPARENT=0;}',
'android/graphics/PorterDuff.java':'package android.graphics;public class PorterDuff{public static class Mode{public static Object CLEAR=new Object();}}',
'android/view/Surface.java':'''package android.view;public class Surface{public Surface(SurfaceControl s){}public android.graphics.Canvas lockCanvas(Object r){return new android.graphics.Canvas();}public void unlockCanvasAndPost(Object o){}public void release(){}}''',
'android/view/SurfaceControl.java':'''package android.view;public class SurfaceControl{public float x,y;public boolean valid=true;public boolean isValid(){return valid;}public static class Transaction implements AutoCloseable{public Transaction setPosition(SurfaceControl s,float x,float y){s.x=x;s.y=y;return this;}public void apply(){}public void close(){}}}''',
'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;public class XC_MethodHook{protected void beforeHookedMethod(MethodHookParam p){}public void fire(MethodHookParam p){beforeHookedMethod(p);}public static class MethodHookParam{public Object thisObject;public Object[] args;public Object result;public void setResult(Object r){result=r;}}}''',
'de/robv/android/xposed/XposedBridge.java':'''package de.robv.android.xposed;public class XposedBridge{public static java.util.Map<String,XC_MethodHook> hooks=new java.util.HashMap<>();public static java.util.Set<Object> hookAllMethods(Class<?> c,String m,XC_MethodHook h){hooks.put(m,h);return java.util.Set.of(h);}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers{public static java.util.Map<Object,java.util.Map<String,Object>> extras=new java.util.IdentityHashMap<>();
public static Class<?> findClass(String n,ClassLoader l){return Object.class;}public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
public static float getFloatField(Object o,String n){return (Float)getObjectField(o,n);}public static void setFloatField(Object o,String n,float v){try{o.getClass().getField(n).setFloat(o,v);}catch(Exception e){throw new RuntimeException(e);}}
public static Object getAdditionalInstanceField(Object o,String n){return extras.getOrDefault(o,java.util.Map.of()).get(n);}public static void setAdditionalInstanceField(Object o,String n,Object v){extras.computeIfAbsent(o,k->new java.util.HashMap<>()).put(n,v);}public static void removeAdditionalInstanceField(Object o,String n){extras.get(o).remove(n);}
public static Object callMethod(Object o,String n,Object...args){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)return m.invoke(o,args);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}}''',
'io/github/zerohomers0/onesteplite/NativeGeometryRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.graphics.*;import android.os.*;import android.view.*;import de.robv.android.xposed.*;import java.util.*;
class ContentDragShadow{int recycled;void recycleThumbnail(){recycled++;}}
class NativeContentDragCard{static boolean files(android.content.ClipData c){return c.mime.equals("file");}}
class PortalDragInput{static Object originalWindow(Object w){return ((NativeGeometryRegression.Target)w).original;}}
class WindowIdentity{static boolean ownsPicker(Object w,Object d){return false;}
static boolean ownsShareReceiver(Object w){return ((NativeGeometryRegression.Target)w).shareReceiver;}}
class Tags{static final String PROBE="test";}
public class NativeGeometryRegression{
public static class State{public Object mLocalWin=new Object();public SurfaceControl mSurfaceControl=new SurfaceControl();public float mThumbOffsetY=107,mCurrentX=500,mCurrentY=870;public Set<Object> mNotifiedWindows=new HashSet<>();public Wm mService=new Wm();public android.content.ClipData mData;public int mFlags,ended;public void endDragLocked(boolean consumed,boolean relinquish){if(consumed||relinquish)throw new AssertionError("cancel must not report success");ended++;}}
public static class RomState extends State{public float mCurrentDisplayX=500,mCurrentDisplayY=870;}
public static class Wm{public Map<Object,Object> mWindowMap=new HashMap<>(),mInputToWindowMap=new HashMap<>();public Object mGlobalLock=new Object();public Handler mH=new Handler();public android.content.Context mContext=new android.content.Context();}
public static class Target{public Object mInputChannelToken=new Object(),original;public int mOwnerUid=12345;public boolean shareReceiver;public String pkg="com.tencent.mobileqq";public String getOwningPackage(){return pkg;}public float translateToWindowX(float x){return x*.75f-20;}public float translateToWindowY(float y){return y*.75f-200;}}
public static class Display{public Wm mWmService=new Wm();public Object mInputMethodWindow;public Target target=new Target();public Display(){target.pkg="other";}public Object getTouchableWinAtPointLocked(float x,float y){return y>=940&&y<1000?target:null;}}
public static class Controller{public State mDragState=new State();}
static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
static XC_MethodHook.MethodHookParam event(State state,Object...args){var p=new XC_MethodHook.MethodHookParam();p.thisObject=state;p.args=args;return p;}
public static void main(String[] args){
check(NativeDragGeometry.install(null,()->true),"known WM drop hooks installed");
check(!XposedBridge.hooks.containsKey("updateDragSurfaceLocked"),"no native pointer flip hook is installed");
Controller controller=new Controller();Display display=new Display();State state=controller.mDragState;ContentDragShadow card=new ContentDragShadow();
Target source=new Target();display.mWmService.mWindowMap.put(state.mLocalWin,source);
state.mNotifiedWindows.add(display.target);
NativeDragGeometry.attach(controller,display,card,state.mSurfaceControl,()->true);
var stationary=event(state,source.mInputChannelToken,500f,850f);
XposedBridge.hooks.get("reportDropWindowLock").fire(stationary);
check(Boolean.FALSE.equals(stationary.result)&&state.ended==1,"release in source window cancels instead of resending");
var drop=event(state,display.target.mInputChannelToken,500f,995f);
XposedBridge.hooks.get("reportDropWindowLock").fire(drop);
check(drop.result==null&&drop.args[0]==display.target.mInputChannelToken&&(Float)drop.args[2]==995f,
      "fixed arrow leaves a valid cross-app DROP receiver and coordinates untouched");
XposedBridge.hooks.get("closeLocked").fire(event(state));
check(card.recycled==1&&display.mWmService.mH.delayed.isEmpty(),"close frees image and deadline");
check(XposedHelpers.getAdditionalInstanceField(state,"content_drag_geometry")==null,"close removes session ownership");
controller=new Controller();controller.mDragState=new RomState();state=controller.mDragState;display=new Display();card=new ContentDragShadow();
source=new Target();display.mWmService.mWindowMap.put(state.mLocalWin,source);state.mNotifiedWindows.add(display.target);
NativeDragGeometry.attach(controller,display,card,state.mSurfaceControl,()->true);
var same=event(state,source.mInputChannelToken,22f,33f,500f,850f);
XposedBridge.hooks.get("reportDropWindowLock").fire(same);
check(Boolean.FALSE.equals(same.result)&&state.ended==1,"HyperOS five-argument same-window drop still cancels");
var rom=event(state,display.target.mInputChannelToken,11f,12f,500f,995f);
XposedBridge.hooks.get("reportDropWindowLock").fire(rom);
check(rom.result==null&&rom.args[0]==display.target.mInputChannelToken&&(Float)rom.args[1]==11f&&(Float)rom.args[4]==995f,
      "HyperOS local and raw coordinates remain untouched");
check(state.mThumbOffsetY==107,"native touch offset is unchanged");
XposedBridge.hooks.get("closeLocked").fire(event(state));
state=new RomState();state.mData=new android.content.ClipData();
source=new Target();Target second=new Target();
state.mService.mWindowMap.put(state.mLocalWin,source);state.mNotifiedWindows.add(second);
same=event(state,source.mInputChannelToken,0f,0f,500f,850f);
XposedBridge.hooks.get("reportDropWindowLock").fire(same);
check(Boolean.FALSE.equals(same.result)&&state.ended==1,"QQ text drop cancels even without a replacement shadow");
state.mData.mime="image";same=event(state,second.mInputChannelToken,0f,0f,500f,850f);
XposedBridge.hooks.get("reportDropWindowLock").fire(same);
check(Boolean.FALSE.equals(same.result)&&state.ended==2,"QQ image drop cancels for another window of the same app and user");
second.mOwnerUid+=100000;same=event(state,second.mInputChannelToken,0f,0f,500f,850f);
XposedBridge.hooks.get("reportDropWindowLock").fire(same);
check(same.result==null&&state.ended==2,"another profile is not the same source");
state.mFlags=256;second.mOwnerUid=source.mOwnerUid;second.pkg="other";
same=event(state,second.mInputChannelToken,0f,0f,500f,850f);
XposedBridge.hooks.get("reportDropWindowLock").fire(same);
check(same.result==null&&state.ended==2,"another package still receives its drag");
second.pkg=source.pkg;state.mNotifiedWindows.clear();
state.mService.mInputToWindowMap.put(second.mInputChannelToken,second);state.mData.mime="custom";
same=event(state,second.mInputChannelToken,0f,0f,500f,850f);
XposedBridge.hooks.get("reportDropWindowLockInner").fire(same);
check(Boolean.FALSE.equals(same.result)&&state.ended==3,"HyperOS direct drop cancels QQ custom content in the same app");
state.mData=null;
same=event(state,second.mInputChannelToken,0f,0f,500f,850f);
XposedBridge.hooks.get("reportDropWindowLockInner").fire(same);
check(Boolean.FALSE.equals(same.result)&&state.ended==4,"QQ same-source native drag can carry private local state only");
controller=new Controller();state=controller.mDragState;display=new Display();
source=new Target();source.pkg="com.android.systemui";source.mOwnerUid=10228;
Target rail=new Target();rail.pkg=source.pkg;rail.mOwnerUid=source.mOwnerUid;rail.shareReceiver=true;
Target anotherPanel=new Target();anotherPanel.pkg=source.pkg;anotherPanel.mOwnerUid=source.mOwnerUid;
state.mData=new android.content.ClipData();state.mFlags=256;
state.mService=display.mWmService;
state.mService.mWindowMap.put(state.mLocalWin,source);
state.mService.mInputToWindowMap.put(rail.mInputChannelToken,rail);
state.mNotifiedWindows.add(rail);state.mNotifiedWindows.add(anotherPanel);
NativeDragGeometry.attach(controller,display,new ContentDragShadow(),state.mSurfaceControl,()->true);
for(String mime:List.of("text","image")){
    state.mData.mime=mime;
    var railDrop=event(state,rail.mInputChannelToken,100f,926f);
    XposedBridge.hooks.get("reportDropWindowLock").fire(railDrop);
    check(railDrop.result==null&&state.ended==0,"SystemUI share receiver keeps the native "+mime+" DROP");
}
var fileDrop=event(state,rail.mInputChannelToken,52f,422f,100f,926f);
state.mData.mime="application";
XposedBridge.hooks.get("reportDropWindowLockInner").fire(fileDrop);
check(fileDrop.result==null&&state.ended==0&&fileDrop.args[0]==rail.mInputChannelToken
        && (Float)fileDrop.args[2]==422f&&(Float)fileDrop.args[4]==926f,
        "HyperOS direct route and shadow-session fallback preserve the share receiver and coordinates");
state.mData.mime="image";
var panelDrop=event(state,anotherPanel.mInputChannelToken,100f,926f);
XposedBridge.hooks.get("reportDropWindowLockInner").fire(panelDrop);
check(Boolean.FALSE.equals(panelDrop.result)&&state.ended==1,"other same-UID panels still reject a return to source");
var sourceDrop=event(state,source.mInputChannelToken,100f,926f);
XposedBridge.hooks.get("reportDropWindowLock").fire(sourceDrop);
check(Boolean.FALSE.equals(sourceDrop.result)&&state.ended==2,"true original source window still cancels");
Target qqSource=new Target();
source.original=qqSource;
state.mNotifiedWindows.add(qqSource);
var qqDrop=event(state,qqSource.mInputChannelToken,100f,926f);
XposedBridge.hooks.get("reportDropWindowLockInner").fire(qqDrop);
check(Boolean.FALSE.equals(qqDrop.result)&&state.ended==3,"portal transfer retains the real QQ source and blocks native DROP");
state.mData=null;
var privateDrop=event(state,qqSource.mInputChannelToken,100f,926f);
XposedBridge.hooks.get("reportDropWindowLock").fire(privateDrop);
check(Boolean.FALSE.equals(privateDrop.result)&&state.ended==4,"cancel blocks QQ's native receiver even with local-only state");
var explicitDrop=event(state,rail.mInputChannelToken,100f,926f);
XposedBridge.hooks.get("reportDropWindowLock").fire(explicitDrop);
check(explicitDrop.result==null&&state.ended==4,"explicit share receiver survives portal source resolution");
for(String app:List.of("com.tencent.mm","other.source")){
    state=new State();source=new Target();source.pkg=app;
    Target target=new Target();target.pkg=app;
    state.mService.mWindowMap.put(state.mLocalWin,source);
    state.mService.mInputToWindowMap.put(target.mInputChannelToken,target);
    state.mData=new android.content.ClipData();state.mFlags=256;
    for(String mime:List.of("text","image","file")){
        state.mData.mime=mime;
        var nativeDrop=event(state,target.mInputChannelToken,100f,926f);
        XposedBridge.hooks.get("reportDropWindowLockInner").fire(nativeDrop);
        check(Boolean.FALSE.equals(nativeDrop.result),"global "+mime+" cancels in "+app);
    }
    state.mFlags=0;state.mData=null;
    var localDrop=event(state,target.mInputChannelToken,100f,926f);
    XposedBridge.hooks.get("reportDropWindowLock").fire(localDrop);
    check(localDrop.result==null,"ordinary app-local drag keeps its behavior");
    Target portal=new Target();portal.pkg="com.android.systemui";portal.original=source;
    state.mService.mWindowMap.put(state.mLocalWin,portal);
    var portalDrop=event(state,target.mInputChannelToken,100f,926f);
    XposedBridge.hooks.get("reportDropWindowLock").fire(portalDrop);
    check(Boolean.FALSE.equals(portalDrop.result),"validated portal source cancels even without a public clip");
}
System.out.println("Production native drag geometry checks="+checks+" passed (WM doubles; device pending)");}}
'''
}
paths=[]
for name,body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'NativeDragGeometry.java'));jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.NativeGeometryRegression'],check=True)
