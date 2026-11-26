"""Run the actual two-phase side switch, including cancellation before and after native geometry changes."""
from pathlib import Path
import os,subprocess,sys
import sys as _sys, pathlib as _pathlib; _sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1])); from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
s=(src/'WorkspaceHost.java').read_text(encoding='utf-8')
def member(n):
 a=s.index('    private void '+n+'(');i=s.index('{',a)+1;d=1
 while d:d+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[a:i]
methods='\n'.join(member(n) for n in ['updateMainSideFrame','translateSidePresentation','settleMainSidePresentation','cancelMainSideSwitch','switchMainSideAnimated'])
files={
'android/animation/Animator.java':'package android.animation;public class Animator {}',
'android/animation/AnimatorListenerAdapter.java':'package android.animation;public class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}}',
'android/animation/ValueAnimator.java':'''package android.animation;import java.util.*;import java.util.function.*;public class ValueAnimator extends Animator {public static boolean enabled=true;public float value;public long duration,delay;List<Consumer<ValueAnimator>> updates=new ArrayList<>();List<AnimatorListenerAdapter> ends=new ArrayList<>();public static boolean areAnimatorsEnabled(){return enabled;}public static float getDurationScale(){return 0;}public static ValueAnimator ofFloat(float...v){return new ValueAnimator();}public void setDuration(long d){duration=d;}public void setCurrentPlayTime(long time){tick(time/300f);}public void setStartDelay(long d){delay=d;}public void setInterpolator(Object i){}public Object getAnimatedValue(){return value;}public void addUpdateListener(Consumer<ValueAnimator> u){updates.add(u);}public void addListener(AnimatorListenerAdapter l){ends.add(l);}public void start(){tick(0);}public void tick(float p){value=p;for(var u:updates)u.accept(this);}public void end(){tick(1);for(var l:ends)l.onAnimationEnd(this);}public void cancel(){for(var l:ends)l.onAnimationEnd(this);}}''',
'android/view/animation/AnimationUtils.java':'package android.view.animation;public class AnimationUtils{public static long currentAnimationTimeMillis(){return android.os.SystemClock.uptimeMillis();}}',
'android/view/animation/PathInterpolator.java':'package android.view.animation;public class PathInterpolator {public PathInterpolator(float a,float b,float c,float d){}public float getInterpolation(float p){return p;}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long now=1000;public static long uptimeMillis(){return now;}}',
'io/github/zerohomers0/onesteplite/SideSwitchRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.animation.ValueAnimator;import android.os.SystemClock;
public class SideSwitchRegression {
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static final int SLOT_MAIN=0,SLOT_TOP=4,ACTIVE=1,ZOOM_ANIM_MS=300;
 static class View {float x,left,translation;int layouts,invalidates;void setX(float value){translation=value-left;x=value;}void setTranslationX(float value){translation=value;x=left+value;}void relayout(float value){left=value;x=left+translation;}void requestLayout(){layouts++;}void invalidate(){invalidates++;}boolean hit(float point){return point>=x&&point<x+270;}}
 static class Slot {int index;int[] rect={0,600,270,1200};boolean touchBlocked;View view=new View(),hint=new View();Slot(int i){index=i;}}
 static class Layout {boolean mainOnRight;int sideWidth=270,cellX,mainOffsetX;Layout(boolean r){mainOnRight=r;cellX=r?0:810;mainOffsetX=r?270:0;}}
 View root=new View();Object promotion,cellMotion;int scene=ACTIVE;boolean suspendRequested,mainOnRight=true,liveSwapInputBlocked,workspaceEntering,transferBusy,screenOn=true,keyguardLocked,motion,flip;
 Layout layout=new Layout(true);Slot[] slots={new Slot(0),new Slot(1),new Slot(2),new Slot(3),new Slot(4),null};View[] sideBackingViews={new View(),new View(),new View(),new View(),new View()};
 float mainSideFrameX=Float.NaN;Object ctx;int screenW=1080,screenH=2400;
 static class Bitmap {boolean isRecycled(){return false;}} Bitmap wallpaperBackdrop=new Bitmap();
 static class WallpaperCropDrawable {WallpaperCropDrawable(Bitmap b,int w,int h,int x,int y,int tint){}} WallpaperCropDrawable mainSideBackdrop;
 static class HyperOsStyle {static int topWallpaperTint(Object c){return 0;}}
 void updateWorkspaceBackdropVisibility(){}
 ValueAnimator mainSideAnimator;long mainSideSwitchGeneration,sceneEpoch=1,workspaceZoomStart;int layouts,zoomWrites,saved,refreshes;boolean remembered;
 boolean isSideMotionRunning(){return motion;}boolean holdsLiveFlipZoom(){return flip;}
 Layout makeAdaptiveLayout(boolean right){return new Layout(right);}void applyMainLayout(Layout value){layout=value;mainOnRight=value.mainOnRight;layouts++;for(int i=1;i<=3;i++){slots[i].rect[0]=value.cellX;slots[i].view.setX(value.cellX);slots[i].hint.setX(value.cellX);sideBackingViews[i].relayout(value.cellX);updateTouchRegion(slots[i]);}}
 void configure(boolean right){applyMainLayout(new Layout(right));layouts=0;}
 void updateTouchRegion(Slot slot){slot.touchBlocked=liveSwapInputBlocked;}void updateChromeClip(){}
 void rememberMainSide(){saved++;remembered=mainOnRight;}void writeZoomAnimated(boolean on){check(on,"side switch never restores fullscreen");zoomWrites++;workspaceZoomStart=1032;}
 void setMainSide(boolean right){applyMainLayout(makeAdaptiveLayout(right));rememberMainSide();zoomWrites++;}
 int topBarRefreshes;void updateTopBarState(){check(mainSideAnimator==null&&!transferBusy,"bar refresh runs after the side switch unlocks");topBarRefreshes++;}
 void finishPendingSuspend(){}void log(String s){}
'''+methods+'''
 public static void main(String[]args){
  for(boolean old:new boolean[]{true,false}){
   var h=new SideSwitchRegression();h.configure(old);h.switchMainSideAnimated(!old);var exit=h.mainSideAnimator;
   check(exit!=null&&h.liveSwapInputBlocked&&h.layouts==0,"old geometry held while cells leave outer edge");exit.tick(.5f);check(Math.signum(h.slots[1].view.x-h.slots[1].rect[0])==(old?-1:1)&&h.zoomWrites==0,"outgoing cells do not cross native main");
   check(h.sideBackingViews[1].x==(old?0:810)&&h.mainSideFrameX==(old?270:0)&&h.mainSideBackdrop!=null,"outgoing content always leaves stationary wallpaper beneath it");
   h.switchMainSideAnimated(!old);check(h.mainSideAnimator==exit,"duplicate click cannot restart switch");exit.end();var enter=h.mainSideAnimator;
   check(enter!=exit&&h.layouts==1&&h.zoomWrites==1&&enter.delay==32&&enter.duration==300,"native main and incoming cells share animation start and duration");
   check(h.remembered==!old&&h.saved==1,"last chosen side persisted after geometry changes");enter.tick(.5f);check(Math.signum(h.slots[1].view.x-h.slots[1].rect[0])==(!old?-1:1),"incoming cells enter from outside target edge");
   check(h.mainSideFrameX==135&&h.sideBackingViews[1].x==h.layout.cellX,"halfway main hole follows native slide while target wallpaper stays anchored");
   for(int step=0;step<=20;step++) {float progress=step/20f;enter.tick(progress);float expected=(old?270:0)+((old?0:270)-(old?270:0))*progress;check(Math.abs(h.mainSideFrameX-expected)<.001f&&h.mainSideBackdrop!=null,"both edge gaps covered throughout main slide");}
   enter.end();check(Float.isNaN(h.mainSideFrameX)&&h.mainSideBackdrop==null,"settled layout releases temporary substrate and geometry");
   check(h.mainSideAnimator==null&&!h.liveSwapInputBlocked&&h.slots[1].view.x==h.layout.cellX&&h.slots[1].hint.x==h.layout.cellX&&h.sideBackingViews[1].x==h.layout.cellX,"completion keeps actual target layout for TaskView, hint and backing");
   check(!h.slots[1].touchBlocked&&h.slots[1].hint.hit(h.layout.cellX+135)&&!h.slots[1].hint.hit((old?0:810)+135),"only target-side add button receives clicks after unblock");
   check(h.topBarRefreshes==1,"settling refreshes the bar once");
   check(h.root.layouts==1&&h.root.invalidates>1,"native input insets recalculated after animation completion");
   check(h.slots[0].view.x==0&&h.slots[4].view.x==0,"native main and video parents never translated by cell animation");
  }
  var h=new SideSwitchRegression();h.switchMainSideAnimated(false);var stale=h.mainSideAnimator;stale.tick(.6f);h.cancelMainSideSwitch();stale.end();check(h.layouts==0&&h.saved==0&&h.slots[1].view.x==0&&!h.liveSwapInputBlocked,"cancel before switch preserves previous remembered geometry");
  h=new SideSwitchRegression();h.switchMainSideAnimated(false);h.mainSideAnimator.end();stale=h.mainSideAnimator;h.cancelMainSideSwitch();stale.end();check(h.layouts==1&&h.remembered==false&&h.slots[1].view.x==810&&!h.liveSwapInputBlocked,"cancel after switch preserves committed side without late callbacks");
  for(int gate=0;gate<10;gate++){h=new SideSwitchRegression();switch(gate){case 0:h.keyguardLocked=true;break;case 1:h.screenOn=false;break;case 2:h.workspaceEntering=true;break;case 3:h.promotion=new Object();break;case 4:h.transferBusy=true;break;case 5:h.cellMotion=new Object();break;case 6:h.motion=true;break;case 7:h.flip=true;break;case 8:h.scene=0;break;case 9:h.liveSwapInputBlocked=true;break;}h.switchMainSideAnimated(false);check(h.mainSideAnimator==null&&h.layouts==0,"native ownership/lock gate "+gate);}
  ValueAnimator.enabled=false;h=new SideSwitchRegression();h.switchMainSideAnimated(false);check(h.mainSideAnimator==null&&h.layouts==1&&h.saved==1&&!h.remembered,"system reduced-motion setting respected");ValueAnimator.enabled=true;
  android.os.SystemClock.now=1100;h=new SideSwitchRegression();h.switchMainSideAnimated(false);h.mainSideAnimator.end();check(Math.abs(h.mainSideFrameX-(270-270*68/300f))<.01f,"late Host frame catches the already-published native WM clock");h.cancelMainSideSwitch();android.os.SystemClock.now=1000;
  System.out.println("Production remembered side-switch animation: "+checks+" checks passed");
 }
}'''}
for name,body in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8')
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN']);subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*[str(out/n) for n in files],str(src/'WorkspaceMotionSpec.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.SideSwitchRegression'],check=True)
