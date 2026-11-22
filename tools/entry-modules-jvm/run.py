"""Replay extracted navigation/window hooks and independent zoom publication channels."""
from pathlib import Path
import os
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root)
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/annotation/SuppressLint.java':'package android.annotation;public @interface SuppressLint {String[] value();}',
'android/content/Context.java':'''package android.content;public class Context {
 public static final int RECEIVER_EXPORTED=2;public java.io.File files;public BroadcastReceiver receiver;public IntentFilter filter;
 public java.util.List<Intent> sent=new java.util.ArrayList<>();public boolean fail;
 public java.io.File getFilesDir(){return files;}public Object getContentResolver(){return this;}
 public void sendBroadcast(Intent i){if(fail)throw new IllegalStateException();sent.add(i);}
 public void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){receiver=r;filter=f;}}''',
'android/content/BroadcastReceiver.java':'package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
'android/content/Intent.java':'''package android.content;public class Intent {
 public static final String ACTION_SCREEN_ON="on",ACTION_SCREEN_OFF="off",ACTION_USER_PRESENT="present",ACTION_CLOSE_SYSTEM_DIALOGS="dialogs";
 public static final int FLAG_RECEIVER_REGISTERED_ONLY=1;String action;public int flags;public java.util.Map<String,Object> extras=new java.util.HashMap<>();
 public Intent(String s){action=s;}public String getAction(){return action;}public String getStringExtra(String k){return (String)extras.get(k);}
 public Intent putExtra(String k,Object v){extras.put(k,v);return this;}public Intent addFlags(int f){flags|=f;return this;}}''',
'android/content/IntentFilter.java':'package android.content;public class IntentFilter {public java.util.List<String> actions=new java.util.ArrayList<>();public void addAction(String s){actions.add(s);}}',
'android/provider/Settings.java':'''package android.provider;public class Settings {public static class Global {
 public static String value;public static boolean fail;public static boolean putString(Object r,String k,String v){if(fail)throw new IllegalStateException();value=v;return true;}}}''',
'android/util/AtomicFile.java':'''package android.util;public class AtomicFile {
 java.io.File f;public AtomicFile(java.io.File f){this.f=f;}public java.io.FileOutputStream startWrite()throws java.io.IOException{return new java.io.FileOutputStream(f);}
 public void finishWrite(java.io.FileOutputStream s)throws java.io.IOException{s.close();}public void failWrite(java.io.FileOutputStream s){try{s.close();}catch(Exception e){}}}''',
'android/graphics/Rect.java':'''package android.graphics;public class Rect {public int left,top,right,bottom;
 public Rect(int a,int b,int c,int d){left=a;top=b;right=c;bottom=d;}public boolean isEmpty(){return right<=left||bottom<=top;}
 public boolean equals(Object o){return o instanceof Rect r&&left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}public int hashCode(){return left;}}''',
'android/view/MotionEvent.java':'''package android.view;public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;public int action;public MotionEvent(int a){action=a;}
 public int getActionMasked(){return action;}public int getDisplayId(){return 0;}public float getX(){return 1;}public float getY(){return 2;}
 public float getRawX(){return 3;}public float getRawY(){return 4;}}''',
'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;public class XC_MethodHook {
 protected void beforeHookedMethod(MethodHookParam p){}protected void afterHookedMethod(MethodHookParam p){}
 public static class MethodHookParam {public Object[] args;public Object result;public boolean throwable;public Object getResult(){return result;}public void setResult(Object r){result=r;}public boolean hasThrowable(){return throwable;}}
 public void before(MethodHookParam p){beforeHookedMethod(p);}public void after(MethodHookParam p){afterHookedMethod(p);}}''',
'de/robv/android/xposed/XposedBridge.java':'''package de.robv.android.xposed;public class XposedBridge {
 public static java.util.Map<String,XC_MethodHook> hooks=new java.util.HashMap<>();
 public static void hookAllMethods(Class<?> c,String n,XC_MethodHook h){hooks.put(n,h);}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers {
 public static Class<?> findClass(String n,ClassLoader c){return Object.class;}
 public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new AssertionError(e);}}
 public static Object callMethod(Object o,String n,Object...a){try{return o.getClass().getMethod(n).invoke(o);}catch(Exception e){throw new AssertionError(e);}}}''',
'io/github/zerohomers0/onesteplite/WorkspaceHost.java':'''package io.github.zerohomers0.onesteplite;class WorkspaceHost {
 static java.util.List<String> nav=new java.util.ArrayList<>();static int present;static boolean screen,clamp=true;static android.graphics.Rect main=new android.graphics.Rect(100,600,1000,2400);
 static void onSystemNavigation(String s){nav.add(s);}static void onUserPresent(){present++;}static void onScreenState(boolean on){screen=on;}
 static android.graphics.Rect activeMainBounds(){return main;}static float[] activeMainTransform(){return new float[]{.75f,600,100};}
 static boolean shouldClampAnimationBounds(Object a,Object b){return clamp;}}''',
'io/github/zerohomers0/onesteplite/EntryGuard.java':'package io.github.zerohomers0.onesteplite;class EntryGuard {static boolean unlocked=true;static boolean canOpen(Object c){return unlocked;}}',
'io/github/zerohomers0/onesteplite/InputTrace.java':'package io.github.zerohomers0.onesteplite;class InputTrace {static int stops;static void stop(){stops++;}}',
'io/github/zerohomers0/onesteplite/RecentsWallpaper.java':'''package io.github.zerohomers0.onesteplite;class RecentsWallpaper {
 static int calls;static int argumentOffset(Object[] args){return args.length>=5?0:-1;}
 static boolean stage(Object a,Object b,Object c,Object d,float[] matrix,android.graphics.Rect bounds,java.util.Map<?,?> m){calls++;return true;}}''',
'io/github/zerohomers0/onesteplite/EntryModulesRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.content.*;import android.graphics.Rect;import android.view.MotionEvent;import de.robv.android.xposed.*;import java.nio.file.*;
public class EntryModulesRegression {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static class Target {public Object leash=new Object();}
 static XC_MethodHook.MethodHookParam param(Object result,Object...args){var p=new XC_MethodHook.MethodHookParam();p.result=result;p.args=args;return p;}
 public static void main(String[]args)throws Exception {
  Context c=new Context();c.files=new java.io.File(args[0]);c.files.mkdirs();var log=new java.util.ArrayList<String>();var transport=new ZoomTransport(c,log::add);
  transport.publish("anim .75 600 100 300 at=99",1000);
  check("1000|anim .75 600 100 300 at=99".equals(android.provider.Settings.Global.value),"settings retains leased animation request");
  Intent sent=c.sent.get(0);check((float)sent.extras.get("scale")==.75f&&(float)sent.extras.get("offsetY")==600,"broadcast carries same target geometry");
  check(sent.flags==Intent.FLAG_RECEIVER_REGISTERED_ONLY,"zoom broadcast cannot start stopped receivers");
  Path file=c.files.toPath().resolve("onesteplite-zoom");check(Files.readString(file).startsWith("anim .75"),"atomic-file fallback preserves original command");
  android.provider.Settings.Global.fail=true;transport.publish("1 0 0",1001);check(c.sent.size()==2&&Files.readString(file).equals("1 0 0"),"settings failure cannot suppress broadcast or file");
  android.provider.Settings.Global.fail=false;c.fail=true;transport.publish(".5 800 0",1002);check(Files.readString(file).equals(".5 800 0"),"broadcast failure cannot suppress atomic-file channel");
  c.fail=false;c.files=new java.io.File(args[0],"missing/child");transport.publish("1 0 0",1003);check(c.sent.size()==3,"file failure cannot suppress broadcast channel");
  WorkspaceScreenObserver.install(c,log::add);check(c.filter.actions.size()==4,"screen observer registers all four native signals");
  for(String reason:new String[]{"homekey","fs_gesture","gestureNav","recentapps"})c.receiver.onReceive(c,new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason",reason));
  check(WorkspaceHost.nav.equals(java.util.List.of("homekey","fs_gesture","gestureNav","recentapps")),"navigation aliases route without changing ordering");
  c.receiver.onReceive(c,new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason","assist"));check(WorkspaceHost.nav.size()==4,"unrelated dialog closure leaves workspace intact");
  c.receiver.onReceive(c,new Intent(Intent.ACTION_SCREEN_OFF));check(!WorkspaceHost.screen&&InputTrace.stops==1,"screen-off stops input tracing before host notification");
  c.receiver.onReceive(c,new Intent(Intent.ACTION_SCREEN_ON));check(WorkspaceHost.screen&&InputTrace.stops==1,"unlocked screen-on retains tracing state");
  EntryGuard.unlocked=false;c.receiver.onReceive(c,new Intent(Intent.ACTION_SCREEN_ON));check(InputTrace.stops==2,"locked screen-on stops tracing");
  c.receiver.onReceive(c,new Intent(Intent.ACTION_USER_PRESENT));check(WorkspaceHost.present==1,"authentication callback remains separate from screen-on");
  var hooks=new WorkspaceWindowHooks(log::add);hooks.hookTransitionBounds(EntryModulesRegression.class.getClassLoader());
  var bounds=XposedBridge.hooks.get("getAnimBounds");var p=param(new Rect(0,0,1080,2400),new Object(),new Object());bounds.after(p);
  check(p.result.equals(WorkspaceHost.main),"installed animation hook clips to active main pane");
  WorkspaceHost.clamp=false;Rect original=new Rect(0,0,1080,2400);p=param(original,new Object(),new Object());bounds.after(p);check(p.result==original,"normal native animation remains unchanged");
  WorkspaceHost.clamp=true;WorkspaceHost.main=null;p=param(original,new Object(),new Object());bounds.after(p);check(p.result==original,"inactive workspace does not clamp native animation");
  hooks.hookRecentsWallpaper(EntryModulesRegression.class.getClassLoader());var wallpaper=XposedBridge.hooks.get("newTarget");
  p=param(new Target(),new Object(),new Object(),new Object(),new Object(),new java.util.HashMap<>());wallpaper.after(p);check(RecentsWallpaper.calls==1,"installed recents hook retains native staging arguments");
  p.throwable=true;wallpaper.after(p);check(RecentsWallpaper.calls==1,"failed native transition is never staged");
  boolean[] zoom={false};new BackGestureDiagnostics(()->zoom[0],log::add).install(EntryModulesRegression.class.getClassLoader());var back=XposedBridge.hooks.get("onInputEvent");
  back.before(param(null,new MotionEvent(MotionEvent.ACTION_DOWN)));check(log.get(log.size()-1).contains("hostZoom=false"),"back diagnostics samples live zoom state");
  zoom[0]=true;back.before(param(null,new MotionEvent(MotionEvent.ACTION_UP)));check(log.get(log.size()-1).contains("hostZoom=true"),"back diagnostics keeps current workspace state");
  int entries=log.size();back.before(param(null,new MotionEvent(MotionEvent.ACTION_MOVE)));check(log.size()==entries,"move events do not flood diagnostic logs");
  check(XposedBridge.hooks.containsKey("onMotionEvent"),"second native back receiver remains observed");
  System.out.println("Production entry modules/zoom transport checks="+checks+" passed");
 }
}'''
}
paths=[]
for name,body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'ZoomTransport.java'))
paths.append(str(src/'WorkspaceScreenObserver.java'))
paths.append(str(src/'WorkspaceWindowHooks.java'))
paths.append(str(src/'BackGestureDiagnostics.java'))
paths.append(str(src/'ZoomChannelState.java'))
paths.append(str(src/'ZoomCommand.java'))
paths.append(str(src/'ZoomAnimCommand.java'))
paths.append(str(src/'NavigationPolicy.java'))
paths.append(str(src/'Actions.java'))
paths.append(str(src/'TransitionBounds.java'))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.EntryModulesRegression',str(out/'files')],check=True)
