"""Exercise the real one-shot cache with unattached native-shaped views."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2]; src=package_dir(root)
out=Path(sys.argv[1]); out.mkdir(parents=True,exist_ok=True)
files={
'android/os/Looper.java':'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'''package android.os;public class Handler {static java.util.ArrayList<Runnable> queue=new java.util.ArrayList<>();public Handler(Looper l){}public void post(Runnable r){queue.add(r);}public static void drain(){int limit=50;while(!queue.isEmpty()){if(--limit==0)throw new AssertionError("unbounded warmup");queue.remove(0).run();}}}''',
'android/content/res/Configuration.java':'package android.content.res;public class Configuration {public int orientation=1,screenWidthDp=400,screenHeightDp=900,densityDpi=400,uiMode=1;public float fontScale=1;}',
'android/content/res/Resources.java':'package android.content.res;public class Resources {public Configuration config=new Configuration();public Configuration getConfiguration(){return config;}}',
'android/provider/Settings.java':'package android.provider;public class Settings {public static class Global {public static int boot=7;public static final String BOOT_COUNT="boot_count";public static int getInt(Object c,String k,int fallback){return boot;}}}',
'android/content/SharedPreferences.java':'''package android.content;public class SharedPreferences {java.util.Map<String,Integer> values=new java.util.HashMap<>();public int getInt(String k,int f){return values.getOrDefault(k,f);}public Editor edit(){return new Editor();}public class Editor {String key;int value;public Editor putInt(String k,int v){key=k;value=v;return this;}public boolean commit(){values.put(key,value);return true;}}}''',
'android/content/Context.java':'''package android.content;public class Context {public SharedPreferences prefs=new SharedPreferences();public static final int MODE_PRIVATE=0;public Object getContentResolver(){return this;}public Context createDeviceProtectedStorageContext(){return this;}public SharedPreferences getSharedPreferences(String n,int m){return prefs;}public static final String DISPLAY_SERVICE="display";public android.content.res.Resources resources=new android.content.res.Resources();public int contexts;public Object getSystemService(String name){return new android.hardware.display.DisplayManager();}public Context createDisplayContext(android.view.Display d){return this;}public Context createWindowContext(int type,Object options){contexts++;return this;}public android.content.res.Resources getResources(){return resources;}}''',
'android/view/Display.java':'package android.view;public class Display {public static final int DEFAULT_DISPLAY=0;}',
'android/hardware/display/DisplayManager.java':'package android.hardware.display;public class DisplayManager {public android.view.Display getDisplay(int id){return new android.view.Display();}}',
'android/view/WindowManager.java':'package android.view;public class WindowManager {public static class LayoutParams {public static final int TYPE_APPLICATION_OVERLAY=2038;}}',
'io/github/zerohomers0/onesteplite/EntryGuard.java':'package io.github.zerohomers0.onesteplite;class EntryGuard {static boolean open=true;static boolean canOpen(Object c){return open;}}',
'io/github/zerohomers0/onesteplite/CurrentUser.java':'package io.github.zerohomers0.onesteplite;class CurrentUser {static int user;static int id(){return user;}}',
'io/github/zerohomers0/onesteplite/ShellCompat.java':'''package io.github.zerohomers0.onesteplite;class ShellCompat {
 static int creates,releases;static boolean defer,fail;static java.util.ArrayList<Runnable> callbacks=new java.util.ArrayList<>();
 static class NativeView {boolean released;}
 static void createTaskView(Object f,Object c,java.util.concurrent.Executor e,java.util.function.Consumer<Object> done){creates++;if(fail)throw new IllegalStateException();Runnable callback=()->e.execute(()->done.accept(new NativeView()));if(defer)callbacks.add(callback);else callback.run();}
 static Object call(Object view,String name){if(!name.equals("release"))throw new AssertionError("unexpected native operation");((NativeView)view).released=true;releases++;return null;}
}''',
'io/github/zerohomers0/onesteplite/TaskViewWarmupRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.content.Context;import android.os.Handler;
public class TaskViewWarmupRegression {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void prepare(Context c,Object factory){TaskViewWarmup.configure(c,Runnable::run,factory);Handler.drain();TaskViewWarmup.prepare();Handler.drain();}
 public static void main(String[]args){
  Context c=new Context();Object factory=new Object();prepare(c,factory);
  check(ShellCompat.creates==5&&c.contexts==1,"boot creates five empty unattached views using one context");
  for(int i=0;i<20;i++)TaskViewWarmup.prepare();Handler.drain();check(ShellCompat.creates==5,"completed warmup never replenishes or polls");
  for(int i=0;i<5;i++)check(TaskViewWarmup.take(c,factory)!=null,"first host borrows a prepared view");
  check(TaskViewWarmup.take(c,factory)==null,"cache exhaustion returns to native factory path");
  TaskViewWarmup.prepare();Handler.drain();check(ShellCompat.creates==5,"demand permanently retires boot preparation");
  c=new Context();factory=new Object();prepare(c,factory);int releases=ShellCompat.releases;c.resources.config.densityDpi++;
  check(TaskViewWarmup.take(c,factory)==null&&ShellCompat.releases==releases+5,"changed resources discard incompatible views");
  c=new Context();factory=new Object();prepare(c,factory);releases=ShellCompat.releases;CurrentUser.user++;
  check(TaskViewWarmup.take(c,factory)==null&&ShellCompat.releases==releases+5,"foreign user cannot reuse cached views");
  android.provider.Settings.Global.boot++;TaskViewWarmup.userChanged();prepare(c,factory);check(TaskViewWarmup.take(c,factory)!=null,"user change permits a fresh bounded preparation");
  EntryGuard.open=false;int before=ShellCompat.creates;c=new Context();factory=new Object();prepare(c,factory);
  check(ShellCompat.creates==before,"locked boot never constructs a TaskView");EntryGuard.open=true;TaskViewWarmup.prepare();Handler.drain();
  check(ShellCompat.creates==before+5,"authenticated wake enables one preparation");
  ShellCompat.defer=true;c=new Context();factory=new Object();TaskViewWarmup.configure(c,Runnable::run,factory);Handler.drain();TaskViewWarmup.prepare();
  Runnable late=ShellCompat.callbacks.remove(0);TaskViewWarmup.userChanged();releases=ShellCompat.releases;late.run();Handler.drain();
  check(ShellCompat.releases==releases+1,"stale creation callback releases its own view");
  android.provider.Settings.Global.boot++;TaskViewWarmup.prepare();Runnable interrupted=ShellCompat.callbacks.remove(0);EntryGuard.open=false;releases=ShellCompat.releases;interrupted.run();Handler.drain();
  check(ShellCompat.releases==releases+1,"locking during construction prevents caching the new view");
  EntryGuard.open=true;ShellCompat.defer=false;ShellCompat.fail=true;android.provider.Settings.Global.boot++;TaskViewWarmup.userChanged();before=ShellCompat.creates;
  TaskViewWarmup.prepare();for(int i=0;i<20;i++)TaskViewWarmup.prepare();Handler.drain();
  check(ShellCompat.creates==before+1,"factory failure cannot create a retry loop");
  ShellCompat.fail=false;before=ShellCompat.creates;TaskViewWarmup.configure(c,Runnable::run,new Object());Handler.drain();TaskViewWarmup.prepare();Handler.drain();
  check(ShellCompat.creates==before,"same boot is not retried after a simulated SystemUI restart");
  System.out.println("Production one-shot boot TaskView warmup checks="+checks+" passed");
 }
}'''
}
paths=[]
for name,text in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths,str(src/'TaskViewWarmup.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.TaskViewWarmupRegression'],check=True)
