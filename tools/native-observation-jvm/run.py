"""Verify extracted native commit observation runs or cancels exactly once."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/os/Handler.java':'''package android.os;public class Handler{public java.util.List<Runnable> tasks=new java.util.ArrayList<>();
 public void postDelayed(Runnable task,long delay){tasks.add(task);}public void removeCallbacks(Runnable task){tasks.removeIf(t->t==task);}public void run(){while(!tasks.isEmpty())tasks.remove(0).run();}}''',
'io/github/zerohomers0/onesteplite/NativeObservationRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.os.Handler;import java.util.concurrent.atomic.AtomicInteger;
public class NativeObservationRegression{
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static void main(String[] args){
  Handler h=new Handler();AtomicInteger checksRun=new AtomicInteger(),retired=new AtomicInteger();
  NativeObservation first=new NativeObservation("token",checksRun::incrementAndGet,h,value->retired.incrementAndGet());
  h.postDelayed(first,350);h.run();first.run();check(checksRun.get()==1&&retired.get()==1,"commit and watchdog share one completion");
  check(h.tasks.isEmpty(),"completed observation removes its watchdog");
  NativeObservation second=new NativeObservation("token",checksRun::incrementAndGet,h,value->retired.incrementAndGet());
  h.postDelayed(second,350);second.cancel();second.cancel();second.run();h.run();
  check(checksRun.get()==1&&retired.get()==2,"cancel retires without running verification");
  check(h.tasks.isEmpty(),"cancel removes the pending watchdog");
  System.out.println("Production native observation checks="+checks+" passed");
 }
}'''
}
paths=[]
for name,text in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'NativeObservation.java'))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.NativeObservationRegression'],check=True)