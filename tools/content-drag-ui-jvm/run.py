"""Replay real drag-window hiding/restoration and owned shadow direction without Android rendering."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=(src/'ContentPanel.java').read_text(encoding='utf-8-sig')
def member(signature):
    start=source.index(signature);end=source.index('{',start)+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
methods='\n'.join(member(s) for s in ['    private void setDragWindow(','    void dragEnded('])
style=(src/'WorkspacePopupStyle.java').read_text(encoding='utf-8-sig')
start=style.index('    static void dragWindow(');end=style.index('{',start)+1;depth=1
while depth:
    depth+=(style[end]=='{')-(style[end]=='}');end+=1
window_style=style[start:end]
files={
'android/animation/ValueAnimator.java':'package android.animation;public class ValueAnimator{public static boolean areAnimatorsEnabled(){return true;}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long uptimeMillis(){return 1000;}}',
'android/os/PersistableBundle.java':'package android.os;public class PersistableBundle{public String getString(String k){return null;}public int getInt(String k,int d){return d;}}',
'android/content/ClipDescription.java':'package android.content;public class ClipDescription{public android.os.PersistableBundle getExtras(){return null;}}',
'android/os/Looper.java':'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'''package android.os;public class Handler{public static java.util.List<Runnable> jobs=new java.util.ArrayList<>();public Handler(Looper l){}
public void postDelayed(Runnable r,long delay){if(delay!=60000)throw new AssertionError("bound");jobs.add(r);}public void removeCallbacks(Runnable r){jobs.remove(r);}}''',
'android/view/MotionEvent.java':'''package android.view;public record MotionEvent(int action,float y){public static final int ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;
public int getActionMasked(){return action;}public int getPointerCount(){return 1;}public float getRawY(){return y;}}''',
'android/view/View.java':'''package android.view;public class View{public int updates;public boolean attached=true;public void updateDragShadow(Object s){updates++;}
public WindowInsets getRootWindowInsets(){return null;}
public boolean isAttachedToWindow(){return attached;}public Resources getResources(){return new Resources();}public static class Resources{public Metrics getDisplayMetrics(){return new Metrics();}}
public static class Metrics{public float density=1;public int heightPixels=1000;}}''',
'android/view/WindowManager.java':'package android.view;public class WindowManager{public static class LayoutParams{public static final int FLAG_NOT_TOUCHABLE=16;public float alpha=1;}}',
'android/view/WindowInsets.java':'package android.view;public class WindowInsets{public static class Type{public static int ime(){return 1;}}public boolean isVisible(int t){return false;}public Insets getInsets(int t){return new Insets();}public static class Insets{public int bottom;}}',
'android/view/Window.java':'''package android.view;public class Window{public WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();public int radius=28,flags;
public WindowManager.LayoutParams getAttributes(){return attrs;}public void setAttributes(WindowManager.LayoutParams a){attrs=a;}public void setBackgroundBlurRadius(int v){radius=v;}
public void clearFlags(int f){flags&=~f;}public void addFlags(int f){flags|=f;}public Decor getDecorView(){return new Decor();}public static class Decor{public void setAlpha(float v){}}}''',
'io/github/zerohomers0/onesteplite/ContentDragUiRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.view.*;
class WorkspacePopupStyle{__STYLE__}
class ContentDragShadow{boolean down,recycled;static int offset(android.content.ClipDescription d){return 0;}boolean pointsDown(){return down;}boolean pointDown(boolean v){if(down==v)return false;down=v;return true;}
void recycleThumbnail(){recycled=true;}}
public class ContentDragUiRegression{
static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
static class Dialog{Window window=new Window();Window getWindow(){return window;}}
static class Panel{boolean dragging=true;Dialog dialog=new Dialog(),preview=new Dialog();int closes;int dp(int v){return v;}void close(){closes++;}
__METHODS__
}
public static void main(String[] args){Panel panel=new Panel();Window main=panel.dialog.window,secondary=panel.preview.window;
panel.setDragWindow(main,false);panel.setDragWindow(secondary,false);
check(main.attrs.alpha==0&&main.radius==0,"main window blur hidden with content");
check(secondary.attrs.alpha==0&&secondary.radius==0,"secondary window blur hidden too");
main.flags=secondary.flags=16;panel.dragEnded(false);
check(main.attrs.alpha==1&&main.radius==28&&main.flags==0,"cancel restores main material and input");
check(secondary.attrs.alpha==1&&secondary.radius==28&&secondary.flags==0,"cancel restores secondary material");
panel.dragging=true;panel.dragEnded(true);check(panel.closes==1&&!panel.dragging,"accepted drop closes popup once");
panel.dragEnded(true);check(panel.closes==1,"duplicate end ignored");
View view=new View();ContentDragShadow card=new ContentDragShadow();ContentDragMotion.stage(view,card);
check(android.os.Handler.jobs.size()==1,"one bounded cleanup owned by drag");
ContentDragMotion.motion(new MotionEvent(2,850));check(!card.down&&view.updates==0,"middle of display keeps arrow up");
ContentDragMotion.motion(new MotionEvent(2,870));check(card.down&&view.updates==1,"bottom zone points at native drop coordinate");
ContentDragMotion.motion(new MotionEvent(2,840));check(card.down&&view.updates==1,"boundary hysteresis avoids redraw jitter");
ContentDragMotion.motion(new MotionEvent(2,790));check(!card.down&&view.updates==2,"leaving bottom restores up arrow");
ContentDragMotion.motion(new MotionEvent(1,790));check(card.recycled&&android.os.Handler.jobs.isEmpty(),"release frees owned preview and timer");
ContentDragMotion.motion(new MotionEvent(2,990));check(view.updates==2,"idle input never redraws");
card=new ContentDragShadow();ContentDragMotion.stage(view,card);view.attached=false;ContentDragMotion.motion(new MotionEvent(2,990));
check(card.recycled&&android.os.Handler.jobs.isEmpty(),"detached source cannot retain shadow");
System.out.println("Production content drag UI checks="+checks+" passed");}}
'''.replace('__METHODS__',methods).replace('__STYLE__',window_style)
}
paths=[]
for name,body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'ContentDragMotion.java'));jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.ContentDragUiRegression'],check=True)
