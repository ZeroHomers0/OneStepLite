"""Replay production presentation owners with deterministic native and timer doubles."""
from pathlib import Path
import ast
import os
import subprocess
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir

root=Path(__file__).resolve().parents[2]
src=package_dir(root)
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
tree=ast.parse((root/'tools/top-transition-jvm/run.py').read_text(encoding='utf-8-sig'))
shared=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign)
            and any(isinstance(t,ast.Name) and t.id=='files' for t in n.targets))
files={n:s for n,s in shared.items() if n.startswith('android/animation/')}
files['android/graphics/Rect.java']=shared['android/graphics/Rect.java']
files.update({
'android/content/Context.java':shared['android/content/Context.java'],
'android/graphics/PixelFormat.java':shared['android/graphics/PixelFormat.java'],
'android/os/Handler.java':'''package android.os;public class Handler {
 public java.util.List<Runnable> timers=new java.util.ArrayList<>();public void postDelayed(Runnable r,long ms){timers.add(r);}
 public void removeCallbacks(Runnable r){timers.removeIf(x->x==r);}public void fire(){var q=new java.util.ArrayList<>(timers);timers.clear();for(var r:q)r.run();}}''',
'android/view/Gravity.java':'package android.view;public class Gravity {public static final int TOP=1,START=2;}',
'android/view/View.java':'''package android.view;public class View {
 public static final int VISIBLE=0,GONE=8;public int visibility;public void setVisibility(int v){visibility=v;}
 public int getVisibility(){return visibility;}public void setBackgroundColor(int c){}
 public Object getViewRootImpl(){return new Root();}public static class Root {public Object getSurfaceControl(){return new SurfaceControl();}}}''',
'android/widget/FrameLayout.java':'package android.widget;public class FrameLayout extends android.view.View {public FrameLayout(android.content.Context c){}}',
'android/view/WindowManager.java':'''package android.view;public class WindowManager {
 public int adds;public void addView(View v,LayoutParams p){adds++;}public static class LayoutParams {
 public static final int MATCH_PARENT=-1,TYPE_APPLICATION_OVERLAY=2038,FLAG_NOT_FOCUSABLE=8,FLAG_NOT_TOUCH_MODAL=32,FLAG_NOT_TOUCHABLE=16,LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS=3;
 public int gravity,layoutInDisplayCutoutMode;public LayoutParams(int w,int h,int t,int f,int format){}
 public void setTitle(String s){}public void setFitInsetsTypes(int n){}}}''',
'android/view/animation/PathInterpolator.java':'package android.view.animation;public class PathInterpolator {public PathInterpolator(float a,float b,float c,float d){}}',
'android/view/SurfaceControl.java':'''package android.view;public class SurfaceControl {
 public boolean valid=true,hidden,released,removed;public float alpha=1,x,y,sx=1,sy=1;public android.graphics.Rect crop;
 public boolean isValid(){return valid;}public void release(){released=true;}public static SurfaceControl lastMirror;
 public static SurfaceControl mirrorSurface(Object s){return lastMirror=new SurfaceControl();}
 public static class Transaction {
 public Transaction setPosition(SurfaceControl s,float x,float y){s.x=x;s.y=y;return this;}
 public Transaction setMatrix(SurfaceControl s,float x,float a,float b,float y){s.sx=x;s.sy=y;return this;}
 public Transaction setAlpha(SurfaceControl s,float a){s.alpha=a;return this;}
 public Transaction setCrop(SurfaceControl s,android.graphics.Rect r){s.crop=r;return this;}
 public Transaction hide(SurfaceControl s){s.hidden=true;return this;}public Transaction show(SurfaceControl s){s.hidden=false;return this;}
 public Transaction remove(SurfaceControl s){s.removed=true;return this;}
 public Transaction reparent(Object s,Object p){return this;}public Transaction setLayer(Object s,int l){return this;}
 public void apply(){}public void close(){}}}''',
'de/robv/android/xposed/XposedBridge.java':'package de.robv.android.xposed;public class XposedBridge {public static void log(Throwable t){throw new AssertionError(t);}}',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers {
 public static Object callStaticMethod(Class<?> c,String n,Object...a){return invoke(c,null,n,a);}
 public static Object callMethod(Object o,String n,Object...a){if(a.length>0&&a[0] instanceof Class<?>[])a=java.util.Arrays.copyOfRange(a,1,a.length);return invoke(o.getClass(),o,n,a);}
 static Object invoke(Class<?> c,Object o,String n,Object[]a){try{for(var m:c.getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new AssertionError(e);}}}''',
'io/github/zerohomers0/onesteplite/HostWindowPolicy.java':'package io.github.zerohomers0.onesteplite;class HostWindowPolicy {static void configure(Object p){}}',
'io/github/zerohomers0/onesteplite/TopOverlayControls.java':'''package io.github.zerohomers0.onesteplite;class TopOverlayControls {
 android.view.View view;Runnable exit;int shows;TopOverlayControls(Object c,Object w,Runnable exit){this.exit=exit;}
 android.view.View view(){return view;}void show(int w,int h){if(w!=1080||h!=600)throw new AssertionError();view=new android.view.View();shows++;}
 void close(){view=null;}}''',
'io/github/zerohomers0/onesteplite/VideoPane.java':'''package io.github.zerohomers0.onesteplite;class VideoPane {
 java.util.function.BooleanSupplier allowed;java.util.function.Consumer<Boolean> changed;int releases,refreshes;
 VideoPane(Object c,Object h,java.util.function.BooleanSupplier a,java.util.function.Consumer<Boolean>b){allowed=a;changed=b;}
 void refresh(){refreshes++;}void releaseForPrivacy(){releases++;}}''',
'io/github/zerohomers0/onesteplite/PresentationOwnerRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.content.Context;import android.os.Handler;import android.view.*;import android.graphics.Rect;import android.animation.ValueAnimator;
public class PresentationOwnerRegression {
 static int checks;static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static class Host implements TopPresentationController.Host {
  boolean capture=true,controls;int changes,exits;public boolean mayCaptureVideo(){return capture;}public boolean mayShowTaskControls(){return controls;}
  public int width(){return 1080;}public int height(){return 600;}public void videoChanged(){changes++;}public void exitTask(){exits++;}}
 static class MirrorHost implements MirrorExperiment.Host {
  SurfaceControl leash=new SurfaceControl();boolean direct=true;int restores;
  public int resolveTask(int n){return n;}public Object leash(int n){return leash;}public View root(){return new View();}
  public int width(){return 1080;}public int height(){return 2400;}public boolean direct(Object s,Rect d){return direct;}
  public void restoreDirect(Object s){restores++;}}
 public static void main(String[]args)throws Exception {
  Context c=new Context();Handler timer=new Handler();Host h=new Host();
  var top=new TopPresentationController(c,timer,c.windows,h);check(top.video().allowed.getAsBoolean(),"video admission uses live host state");
  h.capture=false;check(!top.video().allowed.getAsBoolean(),"occupied/hidden host revokes admission");
  top.refreshControls();check(top.controlsView()==null,"idle header has no task exit window");
  h.controls=true;top.refreshControls();check(top.controlsView()!=null,"manual task controls use strip dimensions");
  h.controls=false;top.refreshControls();check(top.controlsView()==null,"transition hides manual controls");
  h.controls=true;top.refreshControls();top.hideControls();check(top.controlsView()==null,"explicit release immediately removes controls");
  top.video().changed.accept(true);check(h.changes==1,"video changes refresh host chrome");
  top.refreshVideo();top.releaseVideo();check(top.video().refreshes==1&&top.video().releases==1,"video resource operations reach the same owner");
  MirrorHost m=new MirrorHost();var mirror=new MirrorExperiment(c,timer,m,s->{});Rect dest=new Rect(10,20,310,220);
  mirror.createMirrorLayer();mirror.createMirrorLayer();check(c.windows.adds==1,"mirror overlay created once");
  for(int mode=0;mode<5;mode++){
   mirror.startMirrorPoc(1,dest,1000,mode);SurfaceControl image=SurfaceControl.lastMirror;
   check(image!=null&&timer.timers.size()==1,"mirror owns one expiry timer "+mode);
   check(mode!=1||m.leash.alpha==0,"alpha source isolation");check(mode!=2||m.leash.x==-4000,"offscreen source isolation");
   check(mode!=3||m.leash.crop!=null,"crop source isolation");check(mode!=4||m.leash.hidden,"visibility source isolation");
   timer.fire();check(image.removed&&image.released,"mirror removed and released "+mode);
   check(m.leash.alpha==1&&m.leash.x==0&&m.leash.crop==null&&!m.leash.hidden,"source restored "+mode);
  }
  mirror.startMirrorPoc(1,dest,1000,5);mirror.startMirrorPoc(2,dest,1000,5);
  check(m.restores==1&&timer.timers.size()==1,"replacing direct experiment cancels old expiry and restores source");
  timer.fire();check(m.restores==2,"current direct source restored once");mirror.stopMirrorPoc();check(m.restores==2,"mirror stop is idempotent");
  final float[][] state={null};final int[] shields={0};
  var leash=new LeashExperiment(timer,(s,x,y,sx,sy)->state[0]=new float[]{x,y,sx,sy},(slot,hide)->shields[0]+=hide?1:-1,s->{});
  leash.start(m.leash,1,42,100,200,.5f,.4f,300);var old=ValueAnimator.all.get(ValueAnimator.all.size()-1);
  old.step(.5f);check(state[0][0]==50&&state[0][2]==.75f,"experimental geometry follows original interpolation");
  old.end();check(timer.timers.size()==1&&shields[0]==1,"finished animation retains source until bounded hold");
  timer.fire();check(state[0][0]==0&&state[0][2]==1&&shields[0]==0,"expiry restores transform and slot chrome");
  leash.start(m.leash,1,42,100,200,.5f,.4f,300);old=ValueAnimator.all.get(ValueAnimator.all.size()-1);
  leash.close();old.step(.8f);old.end();check(timer.timers.isEmpty()&&state[0][0]==0&&shields[0]==0,"cancelled callbacks cannot rewrite restored source");
  System.out.println("Production presentation owner checks="+checks+" passed");
 }
}'''
})
paths=[]
for name,body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'TopPresentationController.java'))
paths.append(str(src/'MirrorExperiment.java'))
paths.append(str(src/'LeashExperiment.java'))
paths.append(str(src/'framework/platform/TransactionCompat.java'))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.PresentationOwnerRegression'],check=True)
