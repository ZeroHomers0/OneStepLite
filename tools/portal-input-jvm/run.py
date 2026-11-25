"""Exercise the production native handoff hook with window and input doubles."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root = Path(__file__).resolve().parents[2]
src = package_dir(root)
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
files = {
'android/os/IBinder.java': 'package android.os; public interface IBinder {}',
'android/view/InputDevice.java':'package android.view; public class InputDevice {public static final int SOURCE_TOUCHSCREEN=4098;}',
'android/view/MotionEvent.java':'''package android.view; public class MotionEvent {
public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5;
public final int action;public MotionEvent(int action){this.action=action;}
public int getActionMasked(){return action;}public int getPointerCount(){return 1;}public boolean isFromSource(int source){return true;}
public float getRawX(){return 50;}public float getRawY(){return 50;}
public long getDownTime(){return 12345;}public int getDeviceId(){return 4;}public int getPointerId(int index){return 0;}}''',
'android/view/WindowManager.java': '''package android.view;
public class WindowManager {public static class LayoutParams {
public static final int FIRST_SUB_WINDOW=1000,LAST_SUB_WINDOW=1999,FLAG_SECURE=8192;public int type=2,flags;
public String packageName;public CharSequence accessibilityTitle,title;
public CharSequence getTitle(){return title;}}}''',
'android/util/Log.java': 'package android.util; public class Log {public static int w(String t,String s,Throwable e){return 0;}}',
'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;
public class XC_MethodHook {public static class MethodHookParam {public Object[] args;}
protected void beforeHookedMethod(MethodHookParam call) throws Throwable {}}''',
'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;
public class XposedBridge {static XC_MethodHook hook;
public static java.util.Set<Object> hookAllMethods(Class<?> c,String n,XC_MethodHook h){hook=h;return java.util.Set.of(h);}
public static Object transfer(Object token) throws Throwable {
XC_MethodHook.MethodHookParam call=new XC_MethodHook.MethodHookParam();call.args=new Object[]{token,new Object()};
hook.beforeHookedMethod(call);return call.args[0];}}''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
public class XposedHelpers {
public static Class<?> findClass(String n,ClassLoader c){return Object.class;}
public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
public static Object callMethod(Object o,String n,Object...a){try{
if(n.equals("getTouchableWinAtPointLocked"))return o.getClass().getMethod(n,float.class,float.class).invoke(o,a);
return o.getClass().getMethod(n).invoke(o);
}catch(Exception e){throw new RuntimeException(e);}}}''',
'io/github/zerohomers0/onesteplite/PortalInputRegression.java': '''package io.github.zerohomers0.onesteplite;
import java.util.*;import android.os.IBinder;import android.view.MotionEvent;import android.view.WindowManager;
import de.robv.android.xposed.XposedBridge;
class Tags {static final String PROBE="test";}
class WindowIdentity {static boolean ownsPicker(Object w,Object d){return ((PortalInputRegression.Win)w).authorized;}}
public class PortalInputRegression {
public static class Token implements IBinder {}
public static class Task {public int mode=1;public int getWindowingMode(){return mode;}}
public static class Win {
public IBinder mInputChannelToken=new Token();public int mOwnerUid=10100;
public WindowManager.LayoutParams mAttrs=new WindowManager.LayoutParams();
public boolean visible=true,authorized=true;public Task task=new Task();public Win parent;
public boolean isVisible(){return visible;}public Task getTask(){return task;}public Win getParentWindow(){return parent;}}
public static class Wm {public Object mGlobalLock=new Object();public Map<Object,Object> mWindowMap=new LinkedHashMap<>();}
public static class Display {public Wm mWmService=new Wm();public Win mCurrentFocus,touched;
public Win getTouchableWinAtPointLocked(float x,float y){return touched;}}
static int checks;static void check(boolean yes){checks++;if(!yes)throw new AssertionError("check "+checks);}
public static void main(String[] args) throws Throwable {
Display display=new Display();Win source=new Win(),focus=new Win();display.mCurrentFocus=focus;
source.mAttrs.title=PortalDragInput.TITLE;source.mAttrs.accessibilityTitle="source.app";focus.mAttrs.packageName="source.app";
display.mWmService.mWindowMap.put("source",source);display.mWmService.mWindowMap.put("focus",focus);
boolean[] active={true};PortalDragInput.install(PortalInputRegression.class.getClassLoader(),()->display,()->active[0]);
check(XposedBridge.transfer(source.mInputChannelToken)==focus.mInputChannelToken);
MotionEvent down=new MotionEvent(MotionEvent.ACTION_DOWN);
display.touched=focus;PortalDragInput.observeTouch(display,down);
source.mAttrs.accessibilityTitle=PortalDragInput.identity("source.app",down);
Win popup=new Win();popup.mAttrs.packageName="portal.app";display.mCurrentFocus=popup;
check(XposedBridge.transfer(source.mInputChannelToken)==focus.mInputChannelToken);
focus.mAttrs.flags=WindowManager.LayoutParams.FLAG_SECURE;
check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);focus.mAttrs.flags=0;
display.mWmService.mWindowMap.remove("focus");
check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);
display.mWmService.mWindowMap.put("focus",focus);
PortalDragInput.observeTouch(display,new MotionEvent(MotionEvent.ACTION_UP));
check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);
display.mCurrentFocus=focus;
focus.mAttrs.type=1002;check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);
Win parent=new Win();parent.task=focus.task;parent.mAttrs.packageName="source.app";focus.parent=parent;
check(XposedBridge.transfer(source.mInputChannelToken)==parent.mInputChannelToken);
parent.task=new Task();check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);
parent.task=focus.task;parent.mOwnerUid++;check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);
parent.mOwnerUid=focus.mOwnerUid;parent.mAttrs.packageName="another.app";
check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);
focus.mAttrs.type=2;focus.parent=null;
active[0]=false;check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);active[0]=true;
source.authorized=false;check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);source.authorized=true;
source.mAttrs.title="other";check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);source.mAttrs.title=PortalDragInput.TITLE;
focus.mAttrs.packageName="changed.app";check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);focus.mAttrs.packageName="source.app";
source.mAttrs.accessibilityTitle=null;check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);source.mAttrs.accessibilityTitle="source.app";
focus.mOwnerUid=110100;check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);focus.mOwnerUid=10100;
focus.visible=false;check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);focus.visible=true;
focus.task.mode=5;check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);focus.task.mode=1;
focus.task=null;check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);focus.task=new Task();
display.mCurrentFocus=null;check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);
display.mCurrentFocus=source;check(XposedBridge.transfer(source.mInputChannelToken)==source.mInputChannelToken);
display.mCurrentFocus=focus;Token unknown=new Token();check(XposedBridge.transfer(unknown)==unknown);
check(XposedBridge.transfer(source.mInputChannelToken)==focus.mInputChannelToken);
System.out.println("Production portal native input checks="+checks+" passed (WM doubles; physical handoff pending)");
}}'''
}
paths = []
for name, body in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf-8')
    paths.append(str(path))
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out),
                *paths, str(src / 'PortalDragInput.java')], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.PortalInputRegression'], check=True)
