"""Verify extracted live zoom requests complete once across ACK and timeout paths."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/os/Handler.java':'''package android.os;public class Handler{public java.util.List<Runnable> tasks=new java.util.ArrayList<>();
 public void postDelayed(Runnable task,long delay){tasks.add(task);}public void removeCallbacks(Runnable task){tasks.removeIf(t->t==task);}public void run(){while(!tasks.isEmpty())tasks.remove(0).run();}}''',
'io/github/zerohomers0/onesteplite/LiveSwapZoom.java':'''package io.github.zerohomers0.onesteplite;
public final class LiveSwapZoom{
 public static final class Zoom{public final int generation;public final float scale,x,y;
  public Zoom(int generation,float scale,float x,float y){this.generation=generation;this.scale=scale;this.x=x;this.y=y;}}
 public static final class Ack{public final int generation;public final long transaction;public final float scale,x,y;public final boolean committed;public final String error;
  public Ack(int generation,long transaction,float scale,float x,float y,boolean committed,String error){this.generation=generation;this.transaction=transaction;this.scale=scale;this.x=x;this.y=y;this.committed=committed;this.error=error;}}
}''',
'io/github/zerohomers0/onesteplite/LiveZoomRequestRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.os.Handler;import java.util.*;import java.util.concurrent.atomic.*;
public class LiveZoomRequestRegression{
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static void main(String[] args){
  Handler h=new Handler();AtomicInteger completed=new AtomicInteger(),retired=new AtomicInteger();
  List<LiveSwapZoom.Ack> acks=new ArrayList<>();AtomicBoolean bound=new AtomicBoolean(true);
  LiveZoomRequest timeout=new LiveZoomRequest(new LiveSwapZoom.Zoom(7,1f,2f,3f),
      ack->{completed.incrementAndGet();acks.add(ack);},h,bound::get,value->retired.incrementAndGet());
  h.postDelayed(timeout,1000);h.run();timeout.failed("again");
  check(completed.get()==1&&retired.get()==1&&h.tasks.isEmpty(),"timeout completes once and removes its callback");
  check(acks.get(0).generation==7&&!acks.get(0).committed&&acks.get(0).error.contains("timed out"),"timeout reports the expected ticket");
  LiveZoomRequest ack=new LiveZoomRequest(new LiveSwapZoom.Zoom(8,1f,0f,0f),
      value->completed.incrementAndGet(),h,bound::get,value->retired.incrementAndGet());
  ack.complete(new LiveSwapZoom.Ack(8,99L,1f,0f,0f,true,null));ack.run();ack.failed("late");
  check(completed.get()==2&&retired.get()==2,"ACK completion suppresses late timeout and duplicate ACK");
  boundary=false;
  System.out.println("Production live zoom request checks="+checks+" passed");
 }
}
'''.replace('boundary=false;','bound.set(false);check(!ack.isBound(),"bound callback reflects ticket validity");')
}
paths=[]
for name,text in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'LiveZoomRequest.java'))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-Xmx64m','-cp',str(out),'io.github.zerohomers0.onesteplite.LiveZoomRequestRegression'],check=True)