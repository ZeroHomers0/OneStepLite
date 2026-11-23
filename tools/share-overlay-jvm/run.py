"""Replay the real drag-only overlay owner with deterministic window and delivery fixtures."""
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
'android/content/ComponentName.java':'package android.content;public class ComponentName {}',
'android/content/Context.java':'''package android.content;public class Context {
public android.view.WindowManager windows=new android.view.WindowManager();public Context createDisplayContext(Object display){return this;}
public Context createWindowContext(int type,Object options){return this;}public <T>T getSystemService(Class<T> type){return type.cast(windows);}}''',
'android/content/res/Resources.java':'''package android.content.res;public class Resources {
public static class Metrics {public int widthPixels=1080;}public Metrics getDisplayMetrics(){return new Metrics();}}''',
'android/graphics/Rect.java':'''package android.graphics;public class Rect {public int top=300;
public boolean empty;public boolean isEmpty(){return empty;}}''',
'android/graphics/PixelFormat.java':'package android.graphics;public class PixelFormat {public static final int TRANSLUCENT=1;}',
'android/util/Log.java':'package android.util;public class Log {public static int w(String t,String m,Throwable e){return 0;}}',
'android/view/Gravity.java':'package android.view;public class Gravity {public static final int TOP=1,START=2;}',
'android/view/DragEvent.java':'''package android.view;public class DragEvent {
public static final int ACTION_DRAG_STARTED=1,ACTION_DRAG_ENDED=4,ACTION_DROP=3;int action;
public DragEvent(int next){action=next;}public int getAction(){return action;}}''',
'android/view/View.java':'''package android.view;public class View {public android.content.Context context;
public View(android.content.Context value){context=value;}public android.content.Context getContext(){return context;}
public Object getDisplay(){return this;}public android.content.res.Resources getResources(){return new android.content.res.Resources();}}''',
'android/widget/FrameLayout.java':'''package android.widget;public class FrameLayout extends android.view.View {
public FrameLayout(android.content.Context c){super(c);}public void addView(android.view.View child,LayoutParams p){}
public static class LayoutParams {public LayoutParams(int w,int h){}}}''',
'android/view/WindowManager.java':'''package android.view;public class WindowManager {
public int adds,removes;public boolean fail;public LayoutParams last;
public void addView(View view,LayoutParams params){adds++;last=params;if(fail)throw new IllegalStateException("window rejected");}
public void removeViewImmediate(View view){removes++;}
public static class LayoutParams {public static final int FLAG_NOT_FOCUSABLE=1,FLAG_NOT_TOUCH_MODAL=2;
public int gravity,y,width,height,type,flags;public String title;
public LayoutParams(int w,int h,int t,int f,int format){width=w;height=h;type=t;flags=f;}
public void setTitle(String value){title=value;}public void setFitInsetsTypes(int value){}}}''',
'io/github/zerohomers0/onesteplite/HyperOsStyle.java':'''package io.github.zerohomers0.onesteplite;
class HyperOsStyle {static int dp(android.content.Context c,int value){return value;}}''',
'io/github/zerohomers0/onesteplite/WindowTypes.java':'package io.github.zerohomers0.onesteplite;class WindowTypes {static final int SYSTEM_UI_DIALOG=2017;}',
'io/github/zerohomers0/onesteplite/HostWindowPolicy.java':'''package io.github.zerohomers0.onesteplite;
class HostWindowPolicy {static int checks;static void configure(android.view.WindowManager.LayoutParams p){checks++;}}''',
'io/github/zerohomers0/onesteplite/Tags.java':'package io.github.zerohomers0.onesteplite;class Tags {static final String PROBE="test";}',
'io/github/zerohomers0/onesteplite/ModalIslandLease.java':'''package io.github.zerohomers0.onesteplite;
class ModalIslandLease {static int active;boolean started;ModalIslandLease(android.view.View v){}
void start(){if(!started){active++;started=true;}}void close(){if(started){active--;started=false;}}}''',
'io/github/zerohomers0/onesteplite/ShareWorkspace.java':'''package io.github.zerohomers0.onesteplite;
class ShareWorkspace extends android.view.View {
static ShareWorkspace latest;static boolean accepts=true;boolean pending;int closes,events;String target;Runnable settled;
ShareWorkspace(android.content.Context c,android.view.View n,java.util.function.BooleanSupplier ready,
java.util.function.BiConsumer<android.content.ComponentName,Runnable> dispatch,java.util.function.Consumer<String> hint,
java.util.function.Supplier<android.graphics.Rect> bounds,boolean only){super(c);latest=this;if(!only)throw new AssertionError("share-only expected");}
boolean onWindowDrag(android.view.DragEvent e,String name,boolean covered){events++;target=name;return accepts;}
boolean retainDelivery(Runnable done){settled=done;return pending;}
void close(){closes++;pending=false;if(settled!=null)settled.run();}
void deliver(){pending=false;if(settled!=null)settled.run();}
}''',
'io/github/zerohomers0/onesteplite/ShareOverlayRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.content.*;import android.view.*;import android.graphics.*;
public class ShareOverlayRegression {
static int checks;static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
static DragEvent event(int action){return new DragEvent(action);}
public static void main(String[] args){
Context context=new Context();View owner=new View(context);boolean[] ready={true};Rect[] bounds={new Rect()};
ShareRailOverlay overlay=new ShareRailOverlay(owner,()->ready[0],(target,launch)->{},hint->{},()->bounds[0]);
check(!overlay.isOpen(),"no overlay allocated while idle");
ready[0]=false;check(!overlay.onDrag(event(1),null)&&context.windows.adds==0,"inactive host never opens drag window");
ready[0]=true;bounds[0]=null;check(!overlay.onDrag(event(1),null),"missing scene geometry rejects drag overlay");
bounds[0]=new Rect();bounds[0].empty=true;check(!overlay.onDrag(event(1),null),"empty scene geometry rejects drag overlay");bounds[0].empty=false;
check(overlay.onDrag(event(1),null)&&overlay.isOpen(),"first content drag mounts overlay");
ShareWorkspace first=ShareWorkspace.latest;check(context.windows.adds==1&&first.events==1,"root's drag-start initializes the mounted share session");
check(context.windows.last.y==250&&context.windows.last.height==50,"share rail occupies only strip immediately above main");
check(context.windows.last.width==1080&&context.windows.last.type==2017,"strip uses physical display width and existing modal window type");
check(HostWindowPolicy.checks==1&&ModalIslandLease.active==1,"window uses owned overlay and island lease");
overlay.onDrag(event(1),null);check(context.windows.adds==1,"duplicate start never creates a second window");
overlay.onDrag(event(3),"target.pkg");check("target.pkg".equals(first.target),"occupied small-window share remains routed to same session");
overlay.onDrag(event(4),null);check(!overlay.isOpen()&&context.windows.removes==1,"drag end immediately removes visual window");
check(first.closes==1&&ModalIslandLease.active==0,"ordinary cancel releases rail and lease");
check(!overlay.onDrag(event(3),"late.pkg"),"late drop cannot recreate closed overlay");
overlay.onDrag(event(1),null);ShareWorkspace pending=ShareWorkspace.latest;pending.pending=true;
overlay.onDrag(event(4),null);check(!overlay.isOpen()&&pending.closes==0,"accepted async delivery survives visual window removal");
check(ModalIslandLease.active==0,"pending delivery never keeps island hidden");pending.deliver();overlay.close();
check(pending.closes==0,"completed detached delivery is released without double cancellation");
overlay.onDrag(event(1),null);ShareWorkspace stopped=ShareWorkspace.latest;stopped.pending=true;overlay.onDrag(event(4),null);overlay.close();
check(stopped.closes==1,"host privacy close cancels pending detached delivery");
context.windows.fail=true;check(!overlay.onDrag(event(1),null)&&!overlay.isOpen(),"window-add failure leaves no open state");
check(ModalIslandLease.active==0,"window-add failure acquires no surviving modal lease");
context.windows.fail=false;bounds[0].top=20;overlay.onDrag(event(1),null);
check(context.windows.last.y==0,"small top area clamps strip to display origin");overlay.close();
check(!overlay.isOpen()&&ModalIslandLease.active==0,"explicit shutdown releases all UI");
ShareWorkspace.accepts=false;check(!overlay.onDrag(event(1),null),"non-content drag is rejected by production share session");
check(!overlay.isOpen()&&ModalIslandLease.active==0,"rejected app or task drag leaves no overlay window or lease");
System.out.println("Production share overlay checks="+checks+" passed");
}}''',
}
paths = []
for name, body in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf-8')
    paths.append(str(path))
paths.append(str(src / 'ShareRailOverlay.java'))
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), *paths], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.ShareOverlayRegression'], check=True)
