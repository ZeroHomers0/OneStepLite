"""Replay modal island drawing ownership, root replacement and dismissal restoration."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/view/WindowManager.java':'''package android.view;public class WindowManager {public static class LayoutParams {public int type=2009;public String title="DynamicIslandWindow";public String getTitle(){return title;}}}''',
'android/view/Display.java':'package android.view;public class Display {public int id;public int getDisplayId(){return id;}}',
'android/view/ViewTreeObserver.java':'''package android.view;import java.util.*;public class ViewTreeObserver {public interface OnPreDrawListener {boolean onPreDraw();}public List<OnPreDrawListener> listeners=new ArrayList<>();public boolean isAlive(){return true;}public void addOnPreDrawListener(OnPreDrawListener l){listeners.add(l);}public void removeOnPreDrawListener(OnPreDrawListener l){listeners.remove(l);}public void draw(){for(var l:List.copyOf(listeners))l.onPreDraw();}}''',
'android/view/View.java':'''package android.view;public class View {public static final int VISIBLE=0,INVISIBLE=4,GONE=8;public int visibility;public int getVisibility(){return visibility;}public void setVisibility(int v){visibility=v;}public interface OnAttachStateChangeListener {void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}public OnAttachStateChangeListener attachment;public float alpha=1;public Display display=new Display();public WindowManager.LayoutParams params=new WindowManager.LayoutParams();public ViewTreeObserver observer=new ViewTreeObserver();public Display getDisplay(){return display;}public Object getLayoutParams(){return params;}public float getAlpha(){return alpha;}public void setAlpha(float f){alpha=f;}public ViewTreeObserver getViewTreeObserver(){return observer;}public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){attachment=l;}public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l){if(attachment==l)attachment=null;}public boolean post(Runnable r){r.run();return true;}}''',
'android/view/inspector/WindowInspector.java':'package android.view.inspector;public class WindowInspector {public static java.util.List<android.view.View> roots=new java.util.ArrayList<>();public static java.util.List<android.view.View> getGlobalWindowViews(){return roots;}}',
'android/util/Log.java':'package android.util;public class Log {public static void w(String t,String s,Throwable e){throw new AssertionError(s,e);}}',
'io/github/zerohomers0/onesteplite/WindowRootsCompat.java':'''package io.github.zerohomers0.onesteplite;class WindowRootsCompat {static java.util.function.Consumer<java.util.List<android.view.View>> listener;static void add(java.util.concurrent.Executor e,java.util.function.Consumer<java.util.List<android.view.View>> l){listener=l;}static void remove(java.util.function.Consumer<java.util.List<android.view.View>> l){if(listener==l)listener=null;}}''',
'io/github/zerohomers0/onesteplite/ModalIslandRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.view.*;import android.view.inspector.*;import java.util.*;
public class ModalIslandRegression {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static void main(String[]args){
  View modal=new View(),island=new View(),status=new View(),foreign=new View();island.alpha=.7f;status.params.type=2000;foreign.display.id=1;
  WindowInspector.roots=new ArrayList<>(List.of(island,status,foreign));var lease=new ModalIslandLease(modal);lease.start();
  check(island.alpha==0&&island.visibility==View.INVISIBLE,"modal hides island drawing and removes its native input target");check(status.alpha==1&&foreign.alpha==1,"native status bar and other displays remain intact");
  island.alpha=1;island.observer.draw();check(island.alpha==0,"native state refresh cannot paint the island above the modal");
  View replacement=new View();WindowInspector.roots.add(replacement);var callback=WindowRootsCompat.listener;callback.accept(WindowInspector.roots);
  check(replacement.alpha==0,"rebuilt island roots are covered by the same modal lease");
  lease.close();check(island.alpha==.7f&&replacement.alpha==1&&island.visibility==View.VISIBLE&&replacement.visibility==View.VISIBLE,"dismiss restores exact original alpha per root");
  check(island.observer.listeners.isEmpty()&&replacement.observer.listeners.isEmpty()&&WindowRootsCompat.listener==null,"dismiss removes frame and root listeners");
  View late=new View();callback.accept(List.of(late));check(late.alpha==1,"late callbacks cannot hide a new island after dismissal");
  lease.close();check(island.alpha==.7f,"close is idempotent");
  island.alpha=0;WindowInspector.roots=List.of(island);lease=new ModalIslandLease(modal);lease.start();modal.attachment.onViewDetachedFromWindow(modal);
  check(island.alpha==0&&modal.attachment==null,"detach cleans up without resurrecting an originally hidden island");
  System.out.println("Production modal island visibility lease checks="+checks+" passed");
 }
}'''
}
paths=[]
for name,body in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths,str(src/'ModalIslandLease.java'),str(src/'Tags.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.ModalIslandRegression'],check=True)
