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
'android/view/WindowManager.java': '''package android.view;
public class WindowManager {public static class LayoutParams {
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
public static Object callMethod(Object o,String n,Object...a){try{return o.getClass().getMethod(n).invoke(o);}catch(Exception e){throw new RuntimeException(e);}}}''',
'io/github/zerohomers0/onesteplite/PortalInputRegression.java': '''package io.github.zerohomers0.onesteplite;
import java.util.*;import android.os.IBinder;import android.view.WindowManager;
import de.robv.android.xposed.XposedBridge;
class Tags {static final String PROBE="test";}
class WindowIdentity {static boolean ownsPicker(Object w,Object d){return ((PortalInputRegression.Win)w).authorized;}}
public class PortalInputRegression {
public static class Token implements IBinder {}
public static class Task {public int mode=1;public int getWindowingMode(){return mode;}}
public static class Win {
public IBinder mInputChannelToken=new Token();public int mOwnerUid=10100;
public WindowManager.LayoutParams mAttrs=new WindowManager.LayoutParams();
public boolean visible=true,authorized=true;public Task task=new Task();
public boolean isVisible(){return visible;}public Task getTask(){return task;}}
public static class Wm {public Object mGlobalLock=new Object();public Map<Object,Object> mWindowMap=new LinkedHashMap<>();}
public static class Display {public Wm mWmService=new Wm();public Win mCurrentFocus;}
static int checks;static void check(boolean yes){checks++;if(!yes)throw new AssertionError("check "+checks);}
public static void main(String[] args) throws Throwable {
Display display=new Display();Win source=new Win(),focus=new Win();display.mCurrentFocus=focus;
source.mAttrs.title=PortalDragInput.TITLE;source.mAttrs.accessibilityTitle="source.app";focus.mAttrs.packageName="source.app";
display.mWmService.mWindowMap.put("source",source);display.mWmService.mWindowMap.put("focus",focus);
boolean[] active={true};PortalDragInput.install(PortalInputRegression.class.getClassLoader(),()->display,()->active[0]);
check(XposedBridge.transfer(source.mInputChannelToken)==focus.mInputChannelToken);
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
