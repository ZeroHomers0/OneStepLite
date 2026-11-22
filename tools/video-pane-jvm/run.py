"""Replay the actual transparent video host, fades, native release and empty top presentation."""
import ast
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
tree = ast.parse((root / 'tools/top-transition-jvm/run.py').read_text(encoding='utf-8-sig'))
shared = next(ast.literal_eval(node.value) for node in tree.body if isinstance(node, ast.Assign)
              and any(isinstance(target, ast.Name) and target.id == 'files' for target in node.targets))
files = {name: body for name, body in shared.items() if name.startswith('android/animation/')}
files.update({
    'android/Manifest.java': 'package android;public class Manifest {public static class permission {public static final String WRITE_SECURE_SETTINGS="secure";}}',
    'android/content/Context.java': '''package android.content;public class Context {
        public static final int RECEIVER_EXPORTED=2;public BroadcastReceiver receiver;public int registrations,unregistrations;
        public java.util.List<Intent> sent=new java.util.ArrayList<>();
        public String getPackageName(){return "com.android.systemui";}public android.content.res.Resources getResources(){return new android.content.res.Resources();}
        public void registerReceiver(BroadcastReceiver r,IntentFilter f,String p,Object h,int flags){receiver=r;registrations++;}
        public void unregisterReceiver(BroadcastReceiver r){receiver=null;unregistrations++;}
        public void sendBroadcast(Intent i,String permission){sent.add(i);}}
    ''',
    'android/content/res/Resources.java': 'package android.content.res;public class Resources {public static class Metrics {public float density=1;}public Metrics getDisplayMetrics(){return new Metrics();}}',
    'android/content/BroadcastReceiver.java': 'package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
    'android/content/IntentFilter.java': 'package android.content;public class IntentFilter {public IntentFilter(String s){}}',
    'android/content/Intent.java': '''package android.content;public class Intent {
        public String action;public java.util.Map<String,Object> values=new java.util.HashMap<>();public Intent(String s){action=s;}
        public Intent setPackage(String s){return this;}public Intent putExtras(android.os.Bundle b){values.putAll(b.values);return this;}
        public Intent putExtra(String k,Object v){values.put(k,v);return this;}
        public android.os.Bundle getBundleExtra(String k){return (android.os.Bundle)values.get(k);}
        public boolean getBooleanExtra(String k,boolean d){return (boolean)values.getOrDefault(k,d);}
        public long getLongExtra(String k,long d){return ((Number)values.getOrDefault(k,d)).longValue();}}
    ''',
    'android/app/PendingIntent.java': 'package android.app;public class PendingIntent {public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;public static PendingIntent getBroadcast(Object c,int n,Object i,int f){return new PendingIntent();}}',
    'android/os/IBinder.java': 'package android.os;public interface IBinder {int FIRST_CALL_TRANSACTION=1;boolean transact(int c,Parcel d,Parcel r,int f);}',
    'android/os/Binder.java': 'package android.os;public class Binder implements IBinder {public int releases;public boolean fail;public boolean transact(int c,Parcel d,Parcel r,int f){releases++;if(fail)throw new IllegalStateException("return pending");return true;}}',
    'android/os/Parcel.java': 'package android.os;public class Parcel {public static Parcel obtain(){return new Parcel();}public void writeInterfaceToken(String s){}public void writeStrongBinder(IBinder b){}public void readException(){}public void recycle(){}}',
    'android/os/Bundle.java': 'package android.os;public class Bundle {public java.util.Map<String,Object> values=new java.util.HashMap<>();public void putBinder(String k,IBinder b){values.put(k,b);}public IBinder getBinder(String k){return (IBinder)values.get(k);}}',
    'android/os/Handler.java': 'package android.os;public class Handler {public java.util.List<Runnable> timers=new java.util.ArrayList<>();public void postDelayed(Runnable r,long d){timers.add(r);}public void removeCallbacks(Runnable r){timers.remove(r);}}',
    'android/graphics/Color.java': 'package android.graphics;public class Color {public static final int TRANSPARENT=0;}',
    'android/graphics/PixelFormat.java': 'package android.graphics;public class PixelFormat {public static final int TRANSLUCENT=-3;}',
    'android/graphics/PorterDuff.java': 'package android.graphics;public class PorterDuff {public enum Mode {CLEAR}}',
    'android/graphics/Canvas.java': 'package android.graphics;public class Canvas {public void drawColor(int c,Object mode){}}',
    'android/graphics/drawable/ColorDrawable.java': 'package android.graphics.drawable;public class ColorDrawable {public ColorDrawable(int c){}}',
    'android/util/Log.java': 'package android.util;public class Log {public static void w(String t,String s,Throwable e){}}',
    'android/view/MotionEvent.java': 'package android.view;public class MotionEvent {public static final int ACTION_DOWN=0,ACTION_UP=1;public int action;public MotionEvent(int a){action=a;}public int getActionMasked(){return action;}public float getX(){return 0;}public float getY(){return 0;}}',
    'android/view/Gravity.java': 'package android.view;public class Gravity {public static final int TOP=1,START=2,CENTER=4;}',
    'android/view/View.java': '''package android.view;public class View {
        public static final int VISIBLE=0,INVISIBLE=4,GONE=8,IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS=4;
        public int backgroundColor;public int visibility;public float alpha=1f;public boolean enabled=true,attached=true,clickable;protected android.content.Context context;
        public View(android.content.Context c){context=c;}public android.content.Context getContext(){return context;}
        public android.content.res.Resources getResources(){return context.getResources();}
        public float getAlpha(){return alpha;}public void setAlpha(float f){alpha=f;}public void setVisibility(int v){visibility=v;}
        public void setEnabled(boolean b){enabled=b;}public boolean isShown(){return visibility==VISIBLE&&attached;}
        public int getWidth(){return 1080;}public int getHeight(){return 600;}public void setImportantForAccessibility(int n){}
        public void setBackground(Object d){}public void setTooltipText(String s){}public void setBackgroundColor(int c){backgroundColor=c;}
        public interface Touch {boolean onTouch(View v,MotionEvent event);}public Touch touch;
        public void setOnTouchListener(Touch listener){touch=listener;}
        public void setContentDescription(String s){}public void setClickable(boolean b){clickable=b;}public java.util.function.Consumer<View> click;public void setOnClickListener(java.util.function.Consumer<View> c){click=c;clickable=true;}public boolean performClick(){if(click!=null)click.accept(this);return true;}
        public boolean isAttachedToWindow(){return attached;}public void getLocationOnScreen(int[] a){a[0]=a[1]=0;}
        public void post(Runnable r){r.run();}protected void onAttachedToWindow(){attached=true;}
        protected void onDetachedFromWindow(){attached=false;}protected void onWindowVisibilityChanged(int v){visibility=v;}
        protected void onSizeChanged(int a,int b,int c,int d){}}
    ''',
    'android/view/SurfaceControl.java': '''package android.view;public class SurfaceControl {
        public float alpha=1f;public SurfaceControl parent;public boolean valid=true,released;public boolean isValid(){return valid;}public void release(){released=true;}
        public static class Builder {SurfaceControl parent;public Builder setName(String n){return this;}public Builder setParent(SurfaceControl p){parent=p;return this;}public Builder setContainerLayer(){return this;}public SurfaceControl build(){SurfaceControl s=new SurfaceControl();s.parent=parent;return s;}}
        public static class Transaction implements AutoCloseable {
        public Transaction setAlpha(SurfaceControl s,float f){s.alpha=f;return this;}public Transaction show(SurfaceControl s){return this;}public Transaction reparent(SurfaceControl s,SurfaceControl p){s.parent=p;return this;}public Transaction remove(SurfaceControl s){s.valid=false;return this;}public void apply(){}public void close(){}}}
    ''',
    'android/view/SurfaceHolder.java': '''package android.view;public interface SurfaceHolder {
        interface Callback {void surfaceCreated(SurfaceHolder h);void surfaceChanged(SurfaceHolder h,int f,int w,int n);void surfaceDestroyed(SurfaceHolder h);}
        void setFormat(int f);void addCallback(Callback c);android.graphics.Canvas lockCanvas();void unlockCanvasAndPost(android.graphics.Canvas c);}
    ''',
    'android/view/SurfaceView.java': '''package android.view;public class SurfaceView extends View {
        public SurfaceControl surface=new SurfaceControl();public SurfaceView(android.content.Context c){super(c);}
        public SurfaceControl getSurfaceControl(){return surface;}public void setZOrderOnTop(boolean b){}
        public SurfaceHolder getHolder(){return new SurfaceHolder(){public void setFormat(int f){}public void addCallback(Callback c){}
        public android.graphics.Canvas lockCanvas(){return new android.graphics.Canvas();}public void unlockCanvasAndPost(android.graphics.Canvas c){}};}}
    ''',
    'android/widget/FrameLayout.java': '''package android.widget;public class FrameLayout extends android.view.View {
        public int children;public FrameLayout(android.content.Context c){super(c);}public void setClipChildren(boolean b){}
        public void addView(android.view.View v,LayoutParams p){children++;}public static class LayoutParams {public int rightMargin;public LayoutParams(int w,int h){}}}
    ''',
    'android/widget/LinearLayout.java': 'package android.widget;public class LinearLayout extends FrameLayout {public LinearLayout(android.content.Context c){super(c);}public void setGravity(int i){}}',
    'android/widget/PopupWindow.java': '''package android.widget;public class PopupWindow {
        public static final int INPUT_METHOD_NOT_NEEDED=1;public boolean showing;
        public PopupWindow(Object c,int w,int h,boolean f){}public boolean isShowing(){return showing;}
        public void showAtLocation(Object v,int g,int x,int y){showing=true;}public void dismiss(){showing=false;}
        public void setBackgroundDrawable(Object d){}public void setOutsideTouchable(boolean b){}public void setTouchModal(boolean b){}public void setInputMethodMode(int i){}}
    ''',
    'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;public class XposedHelpers {
        public static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
        public static Object callMethod(Object o,String n,Object... a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new AssertionError(e);}}}
    ''',
    'io/github/zerohomers0/onesteplite/VideoDock.java': 'package io.github.zerohomers0.onesteplite;class VideoDock {static final String HOST="host",STATE="state",COMMAND="command";}',
    'io/github/zerohomers0/onesteplite/EntryGuard.java': 'package io.github.zerohomers0.onesteplite;class EntryGuard {static boolean allowed=true;static boolean canOpen(Object c){return allowed;}}',
    'io/github/zerohomers0/onesteplite/HyperOsStyle.java': 'package io.github.zerohomers0.onesteplite;class HyperOsStyle {static Object controlButton(Object c,boolean s,float r){return null;}}',
    'io/github/zerohomers0/onesteplite/IconButton.java': 'package io.github.zerohomers0.onesteplite;class IconButton extends android.view.View {static final int PLAY=1,PAUSE=2,VIDEO_RETURN=3,VIDEO_FIT=4,VIDEO_FILL=5;IconButton(android.content.Context c,int i,String t){super(c);}void setIcon(int i){}}',
    'io/github/zerohomers0/onesteplite/VideoPaneRegression.java': '''package io.github.zerohomers0.onesteplite;
        import android.animation.ValueAnimator;import android.os.*;import android.view.*;import android.widget.*;import android.content.*;
        import de.robv.android.xposed.XposedHelpers;
        public class VideoPaneRegression {
            static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
            static Object field(Object o,String n){return XposedHelpers.getObjectField(o,n);}
            static void call(Object o,String n)throws Exception{var m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);m.invoke(o);}
            static Intent state(Object owner,Binder release,long source,boolean embedded){Bundle b=new Bundle();b.putBinder("owner",(IBinder)owner);b.putBinder("release",release);
                return new Intent("state").putExtra("identity",b).putExtra("source",source).putExtra("embedded",embedded);}
            public static void main(String[]args)throws Exception {
                Context ctx=new Context();Handler handler=new Handler();boolean[] allowed={true};int[] changes={0};
                VideoPane pane=new VideoPane(ctx,handler,()->allowed[0],v->changes[0]++);
                SurfaceView video=(SurfaceView)field(pane,"video");
                pane.onAttachedToWindow();pane.surfaceCreated(video.getHolder());
                SurfaceControl surface=(SurfaceControl)field(pane,"carrier");
                check(surface.alpha==0f&&video.alpha==0f&&!pane.embedded(),"idle carrier starts transparent");
                check(!video.touch.onTouch(video,null),"idle header controls retain their touch access");
                check(!video.clickable,"idle SurfaceView never intercepts clock, title or app rail taps");
                int published=ctx.sent.size();pane.refresh();check(ctx.sent.size()==published,"steady host state is deduplicated");
                check(Boolean.TRUE.equals(ctx.sent.get(ctx.sent.size()-1).values.get("active")),"surface creation advertises automatic video admission");
                Binder release=new Binder();Object owner=((VideoHostLease)field(pane,"lease")).owner;
                ctx.receiver.onReceive(ctx,state(new Binder(),release,1,true));check(!pane.embedded(),"foreign video binding is ignored");
                ctx.receiver.onReceive(ctx,state(owner,release,1,true));check(pane.embedded()&&surface.alpha==0f,"admitted video fades in from zero");
                ValueAnimator incoming=ValueAnimator.all.get(ValueAnimator.all.size()-1);incoming.step(.5f);
                check(surface.alpha==.5f&&video.alpha==.5f,"view and carrier agree on the video fade");incoming.end();
                check(surface.alpha==1f&&((PopupWindow)field(pane,"popup")).isShowing(),"ready video and exit controls are visible");
                check(pane.backgroundColor==0xff000000,"fitted player letterboxing covers the original header for nonstandard aspect ratios");
                check(handler.timers.isEmpty()&&((PopupWindow)field(pane,"popup")).isShowing(),"video controls remain reachable without a fallible native touch wakeup");
                video.performClick();check(((PopupWindow)field(pane,"popup")).isShowing(),"single tap restores video controls");
                call(pane,"toggleFit");check("fill".equals(ctx.sent.get(ctx.sent.size()-1).values.get("command")),"fill action uses the same bound source command");
                call(pane,"toggleFit");check("fit".equals(ctx.sent.get(ctx.sent.size()-1).values.get("command")),"complete display can be restored");
                check(video.touch.onTouch(video,new MotionEvent(MotionEvent.ACTION_DOWN)),"visible video blocks accidental touches on covered header actions");
                call(pane,"dismiss");ValueAnimator outgoing=ValueAnimator.all.get(ValueAnimator.all.size()-1);outgoing.step(.5f);
                check(surface.alpha==.5f&&((LinearLayout)field(pane,"controls")).alpha==.5f,"exit controls fade with the native video");
                outgoing.end();Intent command=ctx.sent.get(ctx.sent.size()-1);
                check(!((PopupWindow)field(pane,"popup")).isShowing()&&"release".equals(command.values.get("command")),"exit controls disappear before native release");
                check(surface.alpha==0f&&pane.embedded(),"native return wait reveals the unchanged original header");
                ctx.receiver.onReceive(ctx,state(owner,release,1,false));check(!pane.embedded()&&handler.timers.isEmpty(),"confirmed native return retires the exit watchdog");
                ctx.receiver.onReceive(ctx,state(owner,release,2,true));incoming=ValueAnimator.all.get(ValueAnimator.all.size()-1);incoming.end();
                ctx.receiver.onReceive(ctx,state(owner,release,1,false));check(pane.embedded(),"late previous source cannot clear the current video");
                allowed[0]=false;pane.releaseForPrivacy();check(release.releases==1&&!pane.embedded()&&surface.alpha==0f,"privacy returns native ownership and hides the carrier");
                check(Boolean.FALSE.equals(ctx.sent.get(ctx.sent.size()-1).values.get("active")),"manual task priority revokes automatic capture");
                check(pane.backgroundColor==0,"privacy and exit restore the original header without a black placeholder");
                float before=surface.alpha;incoming.step(.3f);check(surface.alpha==before,"retired video animator never overwrites privacy state");
                pane.onDetachedFromWindow();check(ctx.unregistrations==1,"detached host unregisters its state receiver");
                allowed[0]=true;pane.onAttachedToWindow();check(ctx.registrations==2,"reattached host restores its receiver");
                check(pane.children==1,"top carrier adds no cell, add button or opaque placeholder");
                check(video.surface!=surface,"borrowed video is isolated from the framework SurfaceView parent");
                VideoHostLease failed=new VideoHostLease();failed.create(video.surface);SurfaceControl retained=failed.surface;
                failed.published=true;failed.releaseToken=release;release.fail=true;failed.retire(handler);
                check(retained.parent==null&&!retained.released&&retained.alpha==0f,"failed return detaches and retains our hidden carrier");
                VideoHostLease fresh=new VideoHostLease();fresh.create(video.surface);fresh.published=true;
                release.fail=false;for(Runnable timer:new java.util.ArrayList<>(handler.timers)){handler.timers.remove(timer);timer.run();}
                check(retained.released&&fresh.published&&fresh.surface.parent==video.surface,"old retry releases only its old carrier, never a new capture");
                check(video.surface.valid&&!video.surface.released,"native return cleanup never destroys the framework surface");
                Context closingContext=new Context();VideoPane[] closing={null};
                closing[0]=new VideoPane(closingContext,handler,()->true,v->closing[0].refresh());
                VideoPane host=closing[0];host.onAttachedToWindow();SurfaceView closingView=(SurfaceView)field(host,"video");host.surfaceCreated(closingView.getHolder());
                Object closingOwner=((VideoHostLease)field(host,"lease")).owner;closingContext.receiver.onReceive(closingContext,state(closingOwner,new Binder(),1,true));
                host.surfaceDestroyed(closingView.getHolder());
                check(field(host,"carrier")==null&&!Boolean.TRUE.equals(closingContext.sent.get(closingContext.sent.size()-1).values.get("active")),"teardown callbacks cannot reopen automatic capture");
                System.out.println("Production video host fade/privacy/empty-top checks="+checks+" passed");
            }
        }
    ''',
})
paths = []
for name, body in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf-8')
    paths.append(str(path))
# Compile each production type explicitly; this suite exercises the real host, not a copied model.
paths += [str(src / 'VideoPane.java'), str(src / 'VideoHostLease.java'), str(src / 'OverlayMotionSpec.java'), str(src / 'Tags.java')]
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), *paths], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.VideoPaneRegression'], check=True)
