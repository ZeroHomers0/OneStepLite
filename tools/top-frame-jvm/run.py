"""Exercise real TOP native/layout/frame readiness, cancellation and draw timeouts."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/graphics/Rect.java':'''package android.graphics;public record Rect(int left,int top,int right,int bottom){public Rect(Rect r){this(r.left,r.top,r.right,r.bottom);}}''',
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long uptimeMillis(){return 100;}}',
'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {public List<Runnable> now=new ArrayList<>(),later=new ArrayList<>(),timeouts=new ArrayList<>();public boolean post(Runnable r){now.add(r);return true;}public boolean postDelayed(Runnable r,long d){(d>=2000?timeouts:later).add(r);return true;}public void removeCallbacks(Runnable r){now.remove(r);later.remove(r);timeouts.remove(r);}public void flush(){while(!now.isEmpty())now.remove(0).run();}public void tick(){var copy=List.copyOf(later);later.clear();copy.forEach(Runnable::run);flush();}}''',
'android/view/View.java':'''package android.view;public class View {public int width=1080,height=622;public boolean attached=true,hardware=true;public Observer observer=new Observer();public int getWidth(){return width;}public int getHeight(){return height;}public boolean isAttachedToWindow(){return attached;}public boolean isHardwareAccelerated(){return hardware;}public Observer getViewTreeObserver(){return observer;}public void invalidate(){}public static class Observer {public Runnable frame;public void registerFrameCommitCallback(Runnable r){frame=r;}public boolean unregisterFrameCommitCallback(Runnable r){if(frame!=r)return false;frame=null;return true;}}public void commit(){Runnable r=observer.frame;observer.frame=null;if(r!=null)r.run();}}''',
'android/window/WindowContainerTransaction.java':'''package android.window;public class WindowContainerTransaction {public void setBounds(Object token,Object bounds){}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers {public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}public static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}}''',
'io/github/zerohomers0/onesteplite/ShellCompat.java':'''package io.github.zerohomers0.onesteplite;class ShellCompat {static Object currentTaskInfo(Object c,int id){return ((TopFrameRegression.Controller)c).info;}static Object call(Object o,String n){return de.robv.android.xposed.XposedHelpers.callMethod(o,n);}}''',
'io/github/zerohomers0/onesteplite/SyncPreparation.java':'''package io.github.zerohomers0.onesteplite;class SyncPreparation {static Runnable committed;static void submit(Object c,Object w,java.util.concurrent.Executor e,Runnable ready,java.util.function.Consumer<Throwable> failed){committed=()->e.execute(ready);}}''',
'io/github/zerohomers0/onesteplite/TopFrameRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.os.*;import android.view.*;import android.graphics.*;import java.util.*;
public class TopFrameRegression {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static class Window {public Rect bounds=new Rect(0,0,1800,1036);public Object getBounds(){return bounds;}}
 public static class Configuration {public Window windowConfiguration=new Window();}
 public static class Info {public Object token="task";public int mode=6;public Configuration configuration=new Configuration();public int getWindowingMode(){return mode;}}
 public static class Controller {public Info info=new Info();}
 public static class Idle {public boolean idle=true;public boolean isIdle(){return idle;}}
 public static class Transitions {public Idle mTransitions=new Idle();}
 Handler ui=new Handler();View view=new View();Controller controller=new Controller();Transitions transitions=new Transitions();
 List<Runnable> shell=new ArrayList<>();boolean valid=true;int ready,failed;
 void start(){SyncPreparation.committed=null;TopTaskFrame.await(ui,view,shell::add,new TopTaskFrame.Target(controller,transitions,"task",42,new Rect(0,0,1800,1036),1080,622),()->valid,()->ready++,s->failed++);}
 void nativePass(){while(!shell.isEmpty())shell.remove(0).run();ui.flush();}
 void committed(){SyncPreparation.committed.run();ui.flush();}
 public static void main(String[]args){
  var h=new TopFrameRegression();h.start();check(h.ready==0,"ownership must not imply draw readiness");h.nativePass();check(SyncPreparation.committed!=null&&h.ready==0,"native draw sync must commit before reveal");h.committed();check(h.ready==0&&h.view.observer.frame!=null,"wait for hidden Host frame after native commit");h.view.commit();check(h.ready==1&&h.failed==0&&h.ui.timeouts.isEmpty(),"both commits reveal exactly once");h.view.commit();check(h.ready==1,"duplicate Host frame cannot reveal twice");
  h=new TopFrameRegression();h.transitions.mTransitions.idle=false;h.start();h.nativePass();check(SyncPreparation.committed==null,"native transition still owns its old-sized frame");h.transitions.mTransitions.idle=true;h.ui.tick();h.nativePass();check(SyncPreparation.committed!=null,"native idle can begin frame synchronization");
  h=new TopFrameRegression();h.controller.info.configuration.windowConfiguration.bounds=new Rect(0,0,1080,2460);h.start();h.nativePass();check(SyncPreparation.committed==null,"main-sized buffers cannot enter top reveal");h.controller.info.configuration.windowConfiguration.bounds=new Rect(0,0,1800,1036);h.ui.tick();h.nativePass();check(SyncPreparation.committed!=null,"exact logical top bounds permit synchronization");
  h=new TopFrameRegression();h.view.height=600;h.start();check(h.shell.isEmpty(),"destination layout must have final physical size");h.view.height=622;h.ui.tick();h.nativePass();check(SyncPreparation.committed!=null,"layout completion can resume admission");
  h=new TopFrameRegression();h.start();h.nativePass();h.valid=false;h.committed();h.view.commit();check(h.ready==0&&h.failed==0&&h.ui.timeouts.isEmpty(),"stale native callback cannot revive a retired scene");
  h=new TopFrameRegression();h.start();h.nativePass();h.committed();h.valid=false;h.view.commit();check(h.ready==0&&h.failed==0,"late Host commit must not reveal after cancellation");
  h=new TopFrameRegression();h.start();h.nativePass();h.ui.timeouts.get(0).run();h.committed();h.view.commit();check(h.ready==0&&h.failed==1,"timeout returns task without exposing an unready buffer");
  h=new TopFrameRegression();h.start();h.nativePass();h.committed();h.view.height=600;h.view.commit();h.ui.flush();check(h.ready==0&&!h.ui.later.isEmpty(),"resize during Host draw requires another exact-size frame");
  h=new TopFrameRegression();h.controller.info.token="replacement";h.start();h.nativePass();check(SyncPreparation.committed==null,"same task id with different token cannot pass readiness");
  System.out.println("Production TOP native/layout/frame readiness checks="+checks+" passed");
 }
}'''
}
paths=[]
for name,body in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths,str(src/'TopTaskFrame.java'),str(src/'TransitionSession.java'),str(src/'framework/platform/TransactionCompat.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.TopFrameRegression'],check=True)
