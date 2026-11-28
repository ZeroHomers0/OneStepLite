"""Execute actual Host entry/exit methods with real-surface and deterministic animator doubles."""
import os
import sys as _sys, pathlib as _pathlib; _sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1])); from source_paths import package_dir
from pathlib import Path
import subprocess
import sys
root=Path(__file__).resolve().parents[2]; src=package_dir(root)
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
host=(src/'WorkspaceHost.java').read_text(encoding='utf-8-sig')
wallpaper=(src/'WorkspaceWallpaperController.java').read_text(encoding='utf-8-sig')
side=(src/'WorkspaceSideRowController.java').read_text(encoding='utf-8-sig')
cell=(src/'WorkspaceCellSurfaceController.java').read_text(encoding='utf-8-sig')
chrome=(src/'WorkspaceChromeController.java').read_text(encoding='utf-8-sig')
presentation=(src/'WorkspaceWindowPresentation.java').read_text(encoding='utf-8-sig').replace('host.','')
resume=(src/'WorkspaceResumeController.java').read_text(encoding='utf-8-sig').replace('host.','').replace('WorkspaceHost.','')
def member(signature, text=host):
 a=text.index(signature);b=text.index('{',a);d=1;i=b+1
 while d:
  if text[i]=='{':d+=1
  elif text[i]=='}':d-=1
  i+=1
 return text[a:i]
base_signatures=['    private void prepareWorkspaceBackdrop(', '    void releaseWorkspaceBackdrop(', '    void retireWorkspaceBackdrop(', '    void beginWorkspaceEntry(', '    private void awaitWorkspaceFrame(', '    void setWorkspaceAlpha(', '    Rect workspaceCellCrop(Slot slot)', '    Rect workspaceCellCrop(int[] rect,', '    android.graphics.RectF workspaceMainHole(', '    private android.graphics.RectF workspaceMainHoleAt(', '    android.graphics.RectF workspaceTopHole(', '    android.graphics.RectF workspaceSideHole(', '    void playWorkspaceFade(',  ]
methods='\n'.join(member(s,presentation) for s in base_signatures)+'\n'+member('    void suspendSession(', resume)
methods=(methods.replace('sideRows.isEmpty()','sideOrder.isEmpty()').replace('sideRows.layoutAll()','layoutSideViewport(true)')
    .replace('sideRows.gestureActive()','sideArmed || sideCaptured || isSideMotionRunning()')
    .replace('sideRows.finishGesture()','finishSideScroll()').replace('sideRows.isScrolling()','sideScrolling').replace('cellSurfaces.','').replace('chrome.','').replace('exits.','').replace('resumes.','').replace('scales.',''))
cell_methods='\n'.join(
    member(s,cell).replace('lifecycle.cells().motion()','cellMotion')
    .replace('lifecycle.promotion()','promotion')
    .replace('lifecycle.scales().mayWriteSideSurface','mayWriteSideSurface')
    .replace('lifecycle.slotRegistry().controllerOwns','controllerOwns')
    .replace('lifecycle.slotRegistry().logicalBounds','logicalBounds')
    .replace('lifecycle.sideRows().isScrolling()','sideScrolling')
    .replace('lifecycle.sideRows().closeSurfaceObject','closeSurfaceObject')
    .replace('lifecycle.taskCellsCovered()','taskCellsCovered')
    .replace('lifecycle.topFace()','topFace')
    .replace('lifecycle.workspaceAlpha()','workspaceAlpha')
    .replace('layers.windowPresentation().workspaceCellCrop','workspaceCellCrop')
    .replace('layers.topTransitionOwnsTopOpacity()','topTransition != null && topTransition.ownsTopOpacity()')
    .replace('layers.topTransitionOwnsTaskOpacity()','topTransition != null && topTransition.ownsTaskOpacity()')
    .replace('WorkspaceNativeHooks.getSurfaceField','getSurfaceField')
    .replace('ModuleLog.info("cell-surface", ','log(')
    for s in ['    boolean ownsCellPresentation(', '    boolean cellPresentationVisible(', '    void stageCellPresentation(', '    void updateCellSurfaceVisibility('])
methods+='\n'+cell_methods
chrome_methods='\n'.join(member(s,chrome).replace('host.','').replace('WorkspaceHost.log','log').replace('workspaceAlpha()','workspaceAlpha').replace('mainSideAnimator()','mainSideAnimator') for s in ['    void updateChromeClip(', '    private void clipChromeBesideMain(', '    private int[] sideBackingBounds(', '    void updateWorkspaceBacking(', '    int sideSegmentTop(', '    int sideSegmentHeight('])
methods+='\n'+chrome_methods
side_place=member('    private void placeSideView(', side)
side_place=(side_place.replace('workspace.layout','layout').replace('workspace.workspaceAlpha()','workspaceAlpha')
    .replace('workspace.mainSideAnimator()','mainSideAnimator').replace('workspace.workspaceMainHole()','workspaceMainHole()')
    .replace('workspace.workspaceCellCrop(','workspaceCellCrop('))
methods+='\n'+side_place
wallpaper_method=member('    void updateWorkspaceBackdropVisibility() {', wallpaper)
wallpaper_method=wallpaper_method.replace('host.','').replace('WorkspaceHost.','').replace('workspaceMainHole()','workspaceMainHole()').replace('chrome.updateWorkspaceBacking(','updateWorkspaceBacking(').replace('SLOT_COUNT','SLOT_COUNT')
methods+='\n'+wallpaper_method
methods=(methods.replace('memory.schedule()','scheduleDormantTrim()').replace('memory.cancel()','cancelDormantTrim()')
    .replace('topPresentationHost.','').replace('liveHandoff.','').replace('promotionController.','').replace('carriers.','')
    .replace('zoomSession.','').replace('mainSide.','').replace('slotRegistry.','').replace('sideRows.','')
    .replace('windowPresentation.','').replace('liveGeometry.','').replace('WorkspaceHost.ACTIVE','ACTIVE')
    .replace('WorkspaceHost.RESUMING','RESUMING').replace('WorkspaceHost.SUSPENDED','SUSPENDED')
    .replace('WorkspaceHost.SUSPENDING','SUSPENDING').replace('WorkspaceHost.SLOT_MAIN','SLOT_MAIN')
    .replace('WorkspaceHost.SLOT_TOP','SLOT_TOP').replace('WorkspaceHost.SLOT_COUNT','SLOT_COUNT')
    .replace('windowLayout.','').replace('WorkspaceNativeHooks.getSurfaceField','getSurfaceField').replace('workspace.','').replace('WorkspaceHost.log','log').replace('WorkspaceHost::log','WorkspaceRegression::log'))

files={
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long uptimeMillis(){return 10000;}}',
'android/animation/Animator.java':'package android.animation;public class Animator {}',
'android/animation/AnimatorListenerAdapter.java':'package android.animation;public class AnimatorListenerAdapter {public void onAnimationCancel(Animator a){}public void onAnimationEnd(Animator a){}}',
'android/animation/ValueAnimator.java':r'''package android.animation;
import java.util.*;import java.util.function.*;public class ValueAnimator extends Animator {
 float from,to,value;List<Consumer<ValueAnimator>>updates=new ArrayList<>();List<AnimatorListenerAdapter>listeners=new ArrayList<>();
 public static ValueAnimator ofFloat(float...f){ValueAnimator v=new ValueAnimator();v.from=f[0];v.to=f[1];v.value=v.from;return v;}
 public long delay;public void setStartDelay(long d){delay=d;}public void setCurrentPlayTime(long t){tick(t/300f);}public void setDuration(long d){}public void setInterpolator(Object i){}public void addUpdateListener(Consumer<ValueAnimator>u){updates.add(u);}public void addListener(AnimatorListenerAdapter l){listeners.add(l);}
 private float fraction;public static boolean areAnimatorsEnabled(){return true;}public static float getDurationScale(){return 0;}
 public Object getAnimatedValue(){return value;}public float getAnimatedFraction(){return fraction;}public void tick(float p){fraction=p;value=from+(to-from)*p;for(var u:updates)u.accept(this);}public void start(){tick(0f);}
 public void cancel(){for(var l:listeners)l.onAnimationCancel(this);for(var l:listeners)l.onAnimationEnd(this);}public void end(){tick(1f);for(var l:listeners)l.onAnimationEnd(this);}
}''',
'android/view/animation/AnimationUtils.java':'package android.view.animation;public class AnimationUtils{public static long currentAnimationTimeMillis(){return android.os.SystemClock.uptimeMillis();}}',
'android/view/animation/PathInterpolator.java':'package android.view.animation;public class PathInterpolator {public PathInterpolator(float a,float b,float c,float d){}public float getInterpolation(float p){return p;}}',
'android/view/ViewGroup.java':'package android.view;public class ViewGroup {public static class LayoutParams {public int width,height;}}',
'android/graphics/RectF.java':'package android.graphics;public class RectF {public float left,top,right,bottom;public RectF(){}public RectF(float a,float b,float c,float d){left=a;top=b;right=c;bottom=d;}public void set(float a,float b,float c,float d){left=a;top=b;right=c;bottom=d;}}',
'android/graphics/Rect.java':'package android.graphics;public class Rect {public int left,top,right,bottom;public Rect(int a,int b,int c,int d){left=a;top=b;right=c;bottom=d;}public boolean isEmpty(){return right<=left||bottom<=top;}public String toString(){return left+","+top+","+right+","+bottom;}}',
'android/view/SurfaceControl.java':r'''package android.view;
import java.util.*;public class SurfaceControl {public boolean valid=true,visible=true;public float alpha=1;public android.graphics.Rect crop;public boolean isValid(){return valid;}
 public static class Transaction {List<Runnable>ops=new ArrayList<>();public Transaction setAlpha(Object o,float a){ops.add(()->((SurfaceControl)o).alpha=a);return this;}public Transaction setWindowCrop(Object o,android.graphics.Rect r){ops.add(()->((SurfaceControl)o).crop=r);return this;}public Transaction show(Object o){ops.add(()->((SurfaceControl)o).visible=true);return this;}public Transaction hide(Object o){ops.add(()->((SurfaceControl)o).visible=false);return this;}public void apply(){ops.forEach(Runnable::run);}public void close(){}}
}''',
'de/robv/android/xposed/XposedHelpers.java':r'''package de.robv.android.xposed;
public class XposedHelpers {public static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new IllegalStateException(e);}}}''',
'io/github/zerohomers0/onesteplite/WorkspaceRegression.java':r'''package io.github.zerohomers0.onesteplite;
import java.util.*;import android.view.SurfaceControl;import android.graphics.Rect;import de.robv.android.xposed.XposedHelpers;
public class WorkspaceRegression {
 void cancelMainSideSwitch(){}boolean lockExitPending;
 int exitPlateReleases;void releaseTopExitWindow(){exitPlateReleases++;}
 void updateTopBarState(){}
 static class TopPresentationController {void refreshVideo(){}void releaseVideo(){}}TopPresentationController topPresentation;
 static class TopTaskTransition {boolean ownsTopOpacity(){return true;}boolean ownsTaskOpacity(){return true;}}TopTaskTransition topTransition;
 static class LiveSwapVisualLease {static void writeAlpha(View v,float a){v.setAlpha(a);}}
 static class SideDismiss {boolean isTracking(){return false;}void settle(boolean b){}}
 SideDismiss sideDismiss;
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static class Drawable {int alpha=255;void setAlpha(int a){alpha=a;}}
 static class WallpaperCropDrawable extends Drawable {android.graphics.RectF hole;void setMainHole(android.graphics.RectF r){hole=r==null?null:new android.graphics.RectF(r.left,r.top,r.right,r.bottom);}}
 static class View {interface OnAttachStateChangeListener {void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}OnAttachStateChangeListener listener;void addOnAttachStateChangeListener(OnAttachStateChangeListener l){listener=l;}void removeOnAttachStateChangeListener(OnAttachStateChangeListener l){if(listener==l)listener=null;}android.view.ViewGroup.LayoutParams params=new android.view.ViewGroup.LayoutParams();android.view.ViewGroup.LayoutParams getLayoutParams(){return params;}void setLayoutParams(android.view.ViewGroup.LayoutParams p){params=p;}Object getWindowToken(){return this;}static final int VISIBLE=0,GONE=8;float alpha=1,x,y,translationY;int contentCloses;Drawable background=new WallpaperCropDrawable();android.graphics.Rect clip;Drawable getBackground(){return background;}void setAlpha(float v){alpha=v;}void setX(float v){x=v;}void setY(float v){y=v;}void setTranslationY(float v){translationY=v;}void setClipBounds(android.graphics.Rect r){clip=r;}void invalidate(){}void setWorkspaceMainHole(android.graphics.RectF r){if(background instanceof WallpaperCropDrawable)((WallpaperCropDrawable)background).setMainHole(r);}void closeContentUi(){contentCloses++;}}
 static class Observer {Runnable frame;void registerFrameCommitCallback(Runnable r){frame=r;}}
 static class FrameLayout extends View {Observer observer=new Observer();boolean attached=true,hardware=true;int invalidates;
  boolean isAttachedToWindow(){return attached;}boolean isHardwareAccelerated(){return hardware;}Observer getViewTreeObserver(){return observer;}void invalidate(){invalidates++;}void draw(){Runnable r=observer.frame;observer.frame=null;if(r!=null)r.run();}}
 static class Queue {List<Runnable>q=new ArrayList<>();void post(Runnable r){q.add(r);}void postDelayed(Runnable r,long d){q.add(r);}void flush(){while(!q.isEmpty())q.remove(0).run();}}
 static class Controller {SurfaceControl surface=new SurfaceControl();Object token=new Object();}
 static class Slot {int index=1,sideRow=-1;int taskId=1;Object taskToken;boolean occupied=true,parked,promoting,launching,sidePrepared,frozen;Controller controller=new Controller();View hint=new View(),view=new View();int[] rect=new int[]{0,508,762,900};Slot(){taskToken=controller.token;}}
 static class Bitmap {void recycle(){}}
 static class StatusBar {void restore(){}}
 static class Clipboard {void close(){}static Clipboard get(Object c){return new Clipboard();}}
 static class SystemClock {static long uptimeMillis(){return 10000;}}
 static class Promotion {boolean nativeSwap,cancelled;}
 static final int ACTIVE=1,RESUMING=2,SUSPENDING=3,SUSPENDED=4,SLOT_COUNT=4,CELL_SLOT_COUNT=4,SLOT_MAIN=0,SLOT_TOP=4;static final long ZOOM_ANIM_MS=300;
 FrameLayout root=new FrameLayout();FrameLayout topLiveClip=new FrameLayout();View topBar=new View(),tabletTools=new View(),topStripBacking=new View(),mainGapBacking=new View(),topTaskPane=new View();boolean topFace,taskCellsCovered;View[]sideBackingViews={null,new View(),new View(),new View()};Slot[]slots={new Slot(),new Slot(),new Slot(),new Slot()};
 boolean sideArmed,sideCaptured,sideScrolling;Object sideScrollAnimator;
 boolean isSideMotionRunning(){return sideScrollAnimator!=null;}
 void finishSideScroll(){sideCaptured=false;sideScrollAnimator=null;}
 final List<Slot> sideOrder=new ArrayList<>();
 int sideLayouts;
 void layoutSideViewport(boolean all){
  check(all&&zoomWrites==0,"restore the real side viewport before starting the zoom");
  sideLayouts++;
 }
 boolean mayWriteSideSurface(Slot s){return true;}boolean presentsSlot(Slot s){return true;}
 Rect logicalBounds(Slot s){return new Rect(0,0,screenW,screenH);}
 static class CellMotion{void finish(){}}
 Queue main=new Queue();Object ctx=this;CellMotion cellMotion;StatusBar statusBar=new StatusBar();Promotion promotion; final class Cells { Object motion(){return cellMotion;} void finishMotion(){if(cellMotion!=null)cellMotion.finish();} } Cells cells=new Cells();Bitmap mainBackdrop,wallpaperBackdrop=new Bitmap();
  boolean screenOn=true,zoomed=true,suspendRequested,landscapeSuspended,transferBusy,workspaceEntering,workspaceExiting,keyguardLocked;int scene=ACTIVE,zoomWrites,parks,returns,ready;long sceneEpoch=1,workspaceGeneration,zoomAnimUntil;String pendingSuspendReason;
 static class Layout {boolean tabletLandscape,mainOnRight;float mainScale=.7017717f;int mainOffsetX,mainOffsetY=606;int mainWidth=2286,sideWidth=762,topStrip=508,mainDispWidth=2139,gap=8;
  int[] cellBounds(int i){return new int[]{0,topStrip+i*400,762,topStrip+(i+1)*400};}}
 Layout layout=new Layout();int screenW=3048,screenH=2032;long workspaceZoomStart=10032;
 final android.graphics.RectF workspaceHole=new android.graphics.RectF();
 final android.graphics.RectF workspaceTopHole=new android.graphics.RectF();
 final android.graphics.RectF workspaceSideHole=new android.graphics.RectF();
 static class LiveBackdrop {boolean closed;void close(){closed=true;}}
 static class WorkspaceBackdrop {static boolean defer;static java.util.function.Consumer<LiveBackdrop> pending;static java.util.function.BooleanSupplier exitCurrent;static Runnable exit;
  static void acquire(Object ctx,Queue handler,Object token,Bitmap wallpaper,int w,int h,java.util.function.Consumer<LiveBackdrop> ready,java.util.function.Consumer<String> log){((WorkspaceRegression)ctx).backdropAcquires++;if(defer)pending=ready;else ready.accept(new LiveBackdrop());}
  static void retire(Object ctx,Queue handler,Object token,java.util.function.BooleanSupplier current,Runnable release,java.util.function.Consumer<String> log){exitCurrent=current;exit=release;}}
 int backdropAcquires;LiveBackdrop workspaceBackdrop;View workspaceBackdropOwner;View.OnAttachStateChangeListener workspaceBackdropDetach;
 android.animation.ValueAnimator workspaceAnimator;float workspaceAlpha=1f;float mainSideFrameX=Float.NaN;Object mainSideAnimator;
 void writeZoomAnimated(boolean on){zoomWrites++;}void writeZoom(boolean on){zoomWrites++;}void setZoomState(boolean on){zoomed=on;}void scheduleMainCropClear(long d){}void publishDesktopSideTasks(){}void scheduleDormantTrim(){}void cancelDormantTrim(){}void cancelAllScaleRepairs(){}void clearAllFrameRateHints(){}
 void resetTopForSuspend(){}
 boolean retentionAllowed,retainedOnExit;long retainedExitSourceEpoch=-1;int retainedHides;
 boolean canRetainBoundTasks(){return retentionAllowed;}
 void finishRetainedSuspend(long epoch){retainedHides++;}
 void closeSwapPreview(){}void closeCellMovePreview(){}void restoreCarrierToMain(){}void hideIme(){}static void log(String s){}void failPromotion(Promotion p,String s){}
 void returnUnboundOwnedCandidates(long epoch,Runnable r){returns++;r.run();}void parkNext(int i){parks++;}void finishPendingSuspend(){if(suspendRequested&&scene==ACTIVE&&promotion==null&&!transferBusy){String reason=pendingSuspendReason;pendingSuspendReason=null;suspendRequested=false;suspendSession(reason==null?"deferred":reason);}}
 Object getSurfaceField(Object o,String n){return o==null?null:((Controller)o).surface;}
 boolean controllerOwns(Slot s,int id,Object token){return s.taskId==id&&s.controller.token==token;}
 void closeSurfaceObject(Object t,String n){if(t!=null)((SurfaceControl.Transaction)t).close();}
 __METHODS__
 public static void main(String[]args){
  var kept=new WorkspaceRegression();kept.retentionAllowed=true;kept.suspendSession("exit");
  check(kept.exitPlateReleases==1,"retained workspace exit removes the independent top exit plate before zooming");
  check(kept.retainedHides==0&&kept.returns==0,"retained task hiding waits for real exit fade");
  kept.workspaceAnimator.end();check(kept.retainedHides==1&&kept.returns==0&&kept.parks==0,"normal exit retains bindings without native handback");
  kept=new WorkspaceRegression();kept.retentionAllowed=true;kept.suspendSession("homekey");kept.workspaceAnimator.end();
  check(kept.retainedHides==1&&kept.returns==0,"ordinary Home exit retains live binding");
  kept=new WorkspaceRegression();kept.retentionAllowed=true;kept.suspendSession("screen-lock");kept.workspaceAnimator.end();
  check(kept.retainedHides==0&&kept.returns==1,"screen lock keeps its separate lifecycle");
  kept=new WorkspaceRegression();kept.retentionAllowed=true;kept.slots[1].launching=true;kept.suspendSession("exit");
  check(kept.suspendRequested&&"exit".equals(kept.pendingSuspendReason),"exit reason survives an in-flight app collection");
  kept.slots[1].launching=false;kept.finishPendingSuspend();kept.workspaceAnimator.end();
  check(kept.retainedHides==1&&kept.returns==0,"settled collection exits into retained live bindings");
  var h=new WorkspaceRegression();h.beginWorkspaceEntry(()->h.ready++);
  check(h.backdropAcquires==0,"native backdrop acquisition must wait for the first committed Host frame");
  check(h.workspaceAlpha==0&&h.topBar.alpha==1&&h.mainGapBacking.alpha==1&&((WallpaperCropDrawable)h.mainGapBacking.background).hole!=null&&h.slots[1].controller.surface.alpha==0,"entry keeps the top bar opaque and hides it by geometry while wallpaper excludes the full main");
  check(h.tabletTools.alpha==0,"permanent tablet title is hidden before the first workspace frame");
  check(h.zoomWrites==0&&h.root.observer.frame!=null,"main zoom waits for committed Host buffer");h.root.draw();h.main.flush();
  check(h.backdropAcquires==1,"one native backdrop acquisition follows committed visibility");
  check(h.zoomWrites==1&&h.workspaceAnimator!=null,"one real main zoom starts after frame");h.workspaceAnimator.tick(.5f);
  check(h.workspaceAlpha==.5f&&(h.slots[2].controller.surface.alpha==0f||h.slots[2].controller.surface.alpha==1f)&&h.slots[2].view.alpha==h.slots[2].controller.surface.alpha,"actual side containers reveal by crop with consistent opaque render-node and surface state");h.workspaceAnimator.end();
  check(h.tabletTools.alpha==1,"permanent tablet title reaches full opacity with the real workspace");
  check(h.workspaceAlpha==1&&h.ready==1,"entry continuation follows end frame");
  h=new WorkspaceRegression();h.beginWorkspaceEntry(null);h.scene=SUSPENDING;h.root.draw();h.main.flush();check(h.zoomWrites==0,"stale entry cannot shrink a closing workspace");
  h=new WorkspaceRegression();h.beginWorkspaceEntry(null);h.keyguardLocked=true;h.root.draw();h.main.flush();check(h.zoomWrites==0,"late entry frame cannot magnify keyguard");
  h=new WorkspaceRegression();h.wallpaperBackdrop=null;h.beginWorkspaceEntry(null);check(h.root.observer.frame==null&&!h.main.q.isEmpty(),"entry waits for wallpaper instead of showing a black surround");h.wallpaperBackdrop=new Bitmap();h.main.flush();check(h.root.observer.frame!=null,"wallpaper readiness schedules real frame gate");
  h=new WorkspaceRegression();h.suspendSession("test");check(h.scene==SUSPENDING&&h.zoomWrites==1&&h.returns==0,"exit does not hand tasks back during real zoom");
  check(h.topBar.contentCloses==1,"exit closes content UI before task handback");
  Object owner=h.slots[1].taskToken;h.workspaceAnimator.tick(.5f);check((h.slots[1].controller.surface.alpha==0f||h.slots[1].controller.surface.alpha==1f)&&h.slots[1].taskToken==owner,"exit fades real content without changing owner");
  check(h.tabletTools.alpha==.5f,"permanent tablet title follows exit opacity instead of remaining visible");
  h.workspaceAnimator.end();check(h.returns==1&&h.parks==1&&h.workspaceAlpha==0,"task handback starts only after fade finishes");
  check(h.tabletTools.alpha==0,"permanent tablet title is fully hidden before task handback");
  h=new WorkspaceRegression();h.playWorkspaceFade(true,0,()->{throw new AssertionError("stale entry completed");});var old=h.workspaceAnimator;old.tick(.4f);h.workspaceGeneration++;h.playWorkspaceFade(false,1,null);float beforeLate=h.workspaceAlpha;old.tick(.8f);old.end();check(h.workspaceAlpha==beforeLate,"cancelled animator cannot alter new exit");
  h.slots[1].occupied=false;h.updateCellSurfaceVisibility(h.slots[1]);check(!h.slots[1].controller.surface.visible,"empty TaskView cannot leave its old resize background visible");
  h.slots[1].occupied=true;h.updateCellSurfaceVisibility(h.slots[1]);check(h.slots[1].controller.surface.visible,"same live binding reveals the actual container");
  h.slots[1].occupied=false;h.updateCellSurfaceVisibility(h.slots[1]);h.setWorkspaceAlpha(.6f);
  check(h.slots[1].view.alpha==0&&h.slots[1].controller.surface.alpha==0,"workspace fade cannot resurrect an empty native resize board");
  h.slots[1].occupied=true;h.updateCellSurfaceVisibility(h.slots[1]);
  h.slots[1].promoting=true;h.slots[1].occupied=false;h.updateCellSurfaceVisibility(h.slots[1]);check(h.slots[1].controller.surface.visible,"Shell owns container visibility during a role transition");
  h.slots[1].controller.surface.alpha=.37f;h.setWorkspaceAlpha(.2f);
  check(h.slots[1].controller.surface.alpha==.37f,"workspace fade respects Shell presentation ownership during handoff");
  h=new WorkspaceRegression();h.taskCellsCovered=true;h.setWorkspaceAlpha(.8f);
  check(h.slots[1].controller.surface.alpha==0&&h.slots[1].hint.alpha==0,"workspace callback cannot expose covered cells or plus hints");
  h=new WorkspaceRegression();h.scene=RESUMING;h.sideOrder.add(h.slots[1]);h.beginWorkspaceEntry(null);
  check(h.sideLayouts==0,"wait for the newly visible Host surface");
  h.root.draw();h.main.flush();
  check(h.sideLayouts==1&&h.zoomWrites==1,"retained entry reconnects its viewport before zoom");
  h=new WorkspaceRegression();h.scene=RESUMING;h.beginWorkspaceEntry(null);h.suspendSession("exit-before-first-frame");
  check(h.scene==SUSPENDING&&!h.workspaceEntering,"exit during pending resume frame cannot strand RESUMING");h.root.draw();h.main.flush();check(h.zoomWrites==1,"late entry frame cannot restart zoom after exit");h.workspaceAnimator.end();check(h.parks==1,"pending-entry exit returns tasks");
  h=new WorkspaceRegression();h.scene=RESUMING;h.beginWorkspaceEntry(null);h.screenOn=false;h.suspendSession("screen-lock");
  check(h.scene==SUSPENDING&&h.parks==1&&h.workspaceAnimator==null,"screen lock restores immediately without waiting for hidden display frames");h.root.draw();h.main.flush();check(h.zoomWrites==1&&!h.zoomed,"late unlocked entry never re-enables zoom");
  h=new WorkspaceRegression();h.lockExitPending=true;h.retentionAllowed=true;h.suspendSession("unlock-exit");
  check(h.workspaceAnimator==null&&h.retainedHides==1&&h.returns==0&&h.parks==0,"authenticated hidden lock exit has no fade and no unnecessary task handback");
  h=new WorkspaceRegression();h.tabletTools=null;h.beginWorkspaceEntry(null);
  check(h.workspaceAlpha==0,"portrait workspace remains valid without a permanent tablet title");
  for(boolean mainRight:new boolean[]{false,true}) {
   h=new WorkspaceRegression();h.layout.tabletLandscape=true;h.layout.mainOffsetX=mainRight?909:0;
   h.playWorkspaceFade(false,0,null);check(h.workspaceAnimator.delay==32,"Host shares the published monotonic start");
   check(h.workspaceExiting,"exit fade mirrors the reveal curves");
   h.workspaceExiting=false;
   for(float p:new float[]{0f,.25f,.5f,.75f,1f}) {
    h.setWorkspaceAlpha(p);var hole=((WallpaperCropDrawable)h.topStripBacking.background).hole;
    check(h.topStripBacking.alpha==1&&h.mainGapBacking.alpha==1,"wallpaper stays opaque outside moving main");
    check(Math.abs(hole.left-h.layout.mainOffsetX*p)<.01&&Math.abs(hole.top-606*p)<.01,"hole follows actual main offset");
    check(Math.abs(hole.right-hole.left-3048*(1+(.7017717f-1)*p))<.01,"hole uses the same main scale");
    check((h.slots[1].controller.surface.alpha==0f||h.slots[1].controller.surface.alpha==1f)&&h.slots[1].view.alpha==h.slots[1].controller.surface.alpha&&((WallpaperCropDrawable)h.slots[1].hint.background).hole!=null,"real task remains opaque or hidden while wallpaper exclusion follows geometry");
    var topHole=h.workspaceTopHole();
    var sideHole=h.workspaceSideHole();
    check(topHole!=null&&sideHole!=null,"top bar and side cells each read their own reveal hole");
    check(topHole.top>=hole.top-.01&&sideHole.top<=hole.top+.01,"top bar leads the main edge while side cells trail it");
    check(Math.abs(topHole.top-606*(1-(1-p)*(1-p)))<.01,"top bar follows the ease-out reveal curve");
    check(Math.abs(sideHole.top-606*(p*Math.sqrt(p)))<.01,"side cells follow the ease-in reveal curve");
   }
   h.workspaceExiting=true;
   for(float p:new float[]{0f,.25f,.5f,.75f,1f}) {
    h.setWorkspaceAlpha(p);var hole=((WallpaperCropDrawable)h.topStripBacking.background).hole;
    var topHole=h.workspaceTopHole();
    var sideHole=h.workspaceSideHole();
    check(topHole.top>=hole.top-.01&&sideHole.top>=hole.top-.01,"exit keeps both bands ahead of the advancing main");
    check(topHole.top>=sideHole.top-.01,"exit keeps the bar ahead of the cells");
    check(Math.abs(sideHole.top-606*(p*(1.5f-.5f*p)))<.01,"exit side cells follow the leading reveal curve");
   }
   h.workspaceExiting=false;
   h.workspaceBackdrop=new LiveBackdrop();h.setWorkspaceAlpha(.5f);
   check(h.topStripBacking.alpha==1&&h.mainGapBacking.alpha==1,"cutout wallpaper remains opaque while the native buffer reaches its first composition");
   check(h.slots[1].hint.background.alpha==255&&(h.slots[1].controller.surface.alpha==0f||h.slots[1].controller.surface.alpha==1f),"hint wallpaper and real task never acquire fractional opacity");
   h.setWorkspaceAlpha(1f);check(h.slots[1].hint.background.alpha==255,"settled hint wallpaper restores opacity");
   h.setWorkspaceAlpha(.5f);h.workspaceBackdrop=null;h.setWorkspaceAlpha(.5f);
   check(h.slots[1].hint.background.alpha==255,"failed or released native wallpaper restores original hint background");
   h.layout.tabletLandscape=false;h.setWorkspaceAlpha(.5f);
   check(((WallpaperCropDrawable)h.topStripBacking.background).hole!=null&&h.topStripBacking.alpha==1f,"portrait background excludes moving main without a translucent color band");
  }
  // Portrait chrome rides the main edge: the bar and the rail keep their inner
  // edges glued to the shrinking window instead of being cut by it.
  for(boolean mainRight:new boolean[]{false,true}) {
   h=new WorkspaceRegression();h.layout.mainOnRight=mainRight;h.layout.mainOffsetX=mainRight?835:0;
   for(float p:new float[]{0f,.25f,.5f,.75f,1f}) {
    h.slots[1].sideRow=0;h.setWorkspaceAlpha(p);
    check(Math.abs(h.topLiveClip.translationY-(606*p-606))<.01,"top strip rides the main edge");
    float gap=8*(1-p);
    float expected=mainRight?(835*p-835)+gap:(3048*(1+(.7017717f-1)*p)-2139)-gap;
    check(Math.abs(h.slots[1].view.x-expected)<.01,"side cells ride the main edge");
    check(h.slots[1].view.clip!=null&&h.slots[1].view.clip.left==0&&h.slots[1].view.clip.right==762,"riding cells keep their full crop");
    check(h.slots[1].hint.x==expected,"side placeholders ride with their cells");
    h.placeSideView(h.slots[1].view,h.slots[1].rect,new Rect(0,0,762,392));
    h.placeSideView(h.slots[1].hint,h.slots[1].rect,new Rect(0,0,762,392));
    check(Math.abs(h.slots[1].view.x-expected)<.01&&Math.abs(h.slots[1].hint.x-expected)<.01,"row relayout cannot reset the entry/exit offset");
   }
   h.setWorkspaceAlpha(1f);
   check(h.topLiveClip.translationY==0&&h.slots[1].view.x==0,"settled chrome returns to its layout position");
  }
  h=new WorkspaceRegression();h.layout.tabletLandscape=true;h.wallpaperBackdrop=null;
  h.topStripBacking.background=new Drawable();h.mainGapBacking.background=new Drawable();
  for(View backing:h.sideBackingViews)if(backing!=null)backing.background=new Drawable();
  Object coldTask=h.slots[1].taskToken;h.beginWorkspaceEntry(null);
  check(h.topStripBacking.alpha==0&&h.mainGapBacking.alpha==0,"cold tablet entry hides uncut placeholder strip and gap");
  for(int i=1;i<CELL_SLOT_COUNT;i++)check(h.sideBackingViews[i].alpha==0,"cold tablet entry cannot expose white page placeholders slot="+i);
  check(h.zoomWrites==0&&h.root.observer.frame==null,"cold entry keeps the real main unscaled until wallpaper is ready");
  h.mainGapBacking=new View();h.mainGapBacking.background=new Drawable();h.updateWorkspaceBackdropVisibility();
  check(h.mainGapBacking.alpha==0,"an asynchronously created placeholder obeys the same cold-entry gate");
  h.wallpaperBackdrop=new Bitmap();h.topStripBacking.background=new WallpaperCropDrawable();h.mainGapBacking.background=new WallpaperCropDrawable();
  for(View backing:h.sideBackingViews)if(backing!=null)backing.background=new WallpaperCropDrawable();
  float coldSurfaceAlpha=h.slots[1].controller.surface.alpha;h.updateWorkspaceBackdropVisibility();
  check(h.topStripBacking.alpha==1&&h.mainGapBacking.alpha==1,"real cutout wallpaper becomes drawable after replacement");
  check(((WallpaperCropDrawable)h.topStripBacking.background).hole.right==3048&&((WallpaperCropDrawable)h.topStripBacking.background).hole.bottom==2032,"wallpaper replacement excludes the entire still-fullscreen main");
  check(h.slots[1].controller.surface.alpha==coldSurfaceAlpha&&h.slots[1].taskToken==coldTask,"asynchronous wallpaper replacement never changes live task visibility or identity");
  h.main.flush();check(h.root.observer.frame!=null&&h.zoomWrites==0,"wallpaper readiness still waits for a real Host frame");
  h.root.draw();h.main.flush();check(h.zoomWrites==1,"cold entry starts one zoom after the actual frame gate");
  for(boolean tablet:new boolean[]{false,true}) {
   h=new WorkspaceRegression();h.layout.tabletLandscape=tablet;h.prepareWorkspaceBackdrop(0,0,()->{});
   check(h.workspaceBackdrop!=null,"phone and tablet both acquire physical wallpaper below the moving main");
   LiveBackdrop backing=h.workspaceBackdrop;h.zoomed=false;h.retireWorkspaceBackdrop();
   check(!backing.closed&&h.workspaceBackdrop==backing,"Host animation completion cannot remove backing before native commit");
   h.workspaceGeneration++;check(!WorkspaceBackdrop.exitCurrent.getAsBoolean(),"a resumed scene cancels the old backing retirement");
   h.workspaceGeneration--;if(WorkspaceBackdrop.exitCurrent.getAsBoolean())WorkspaceBackdrop.exit.run();
   check(backing.closed&&h.workspaceBackdrop==null,"native settled frame releases only its own wallpaper lease");
  }
  h=new WorkspaceRegression();WorkspaceBackdrop.defer=true;h.prepareWorkspaceBackdrop(0,0,()->{});
  h.workspaceGeneration++;LiveBackdrop staleBacking=new LiveBackdrop();WorkspaceBackdrop.pending.accept(staleBacking);
  check(staleBacking.closed&&h.workspaceBackdrop==null,"late parent acquisition cannot attach to a newer transition");WorkspaceBackdrop.defer=false;
  h=new WorkspaceRegression();h.playWorkspaceFade(true,0,()->{throw new AssertionError("cancel completed");});h.workspaceAnimator.tick(.4f);float cancelled=h.workspaceAlpha;
  h.workspaceAnimator.cancel();h.workspaceAnimator.tick(.8f);h.workspaceAnimator.end();check(h.workspaceAlpha==cancelled,"cancelled same-scene controller never writes another frame");
  System.out.println("actual workspace entry/exit/real-surface/late-frame checks="+checks+" passed");
 }
}'''.replace('__METHODS__',methods)
}
# Java closures in the fixture must capture an immutable owner, just as production does.
files['io/github/zerohomers0/onesteplite/TopControlsCreationRegression.java']=r'''package io.github.zerohomers0.onesteplite;
import java.util.function.Consumer;
public class TopControlsCreationRegression {
 static class View {float alpha=1f;boolean visible;View(){}View(Object context){}void setAlpha(float a){alpha=a;}void setOnClickListener(Consumer<View> c){}void bringToFront(){}}
 static class FrameLayout extends View {float attachedAlpha=-1;View child;static class LayoutParams{LayoutParams(int w,int h){}}void addView(View v,LayoutParams p){child=v;attachedAlpha=v.alpha;}}
 static class Slot {View hint,view=new View();Object controller=new Object(),taskToken=new Object();}
 static class Layout {boolean tabletLandscape;int topStrip=450;}
 static final int SLOT_TOP=5;Slot[] slots={new Slot(),new Slot(),new Slot(),new Slot(),new Slot(),new Slot()};
 Object ctx=new Object();float workspaceAlpha;FrameLayout root=new FrameLayout(),topLiveClip=new FrameLayout();View topBar=new View();
 Layout layout=new Layout();boolean topFace;int geometryUpdates;
 View makeEmptyHint(){return new View();}void captureMainIntoTop(){}void settleTopFace(boolean taskFace){}
 void releaseTopTask(boolean front,boolean dismissed,Runnable after){}
 void updateTopTaskGeometry(){geometryUpdates++;}
 __CREATE__
 public static void main(String[] args){int checks=0;for(boolean tablet:new boolean[]{true,false})for(float alpha:new float[]{0f,.4f,1f}){
  var h=new TopControlsCreationRegression();h.layout.tabletLandscape=tablet;h.workspaceAlpha=alpha;
  Slot upper=h.slots[SLOT_TOP];View liveView=upper.view;Object controller=upper.controller,token=upper.taskToken;
  h.createTopTaskControls();FrameLayout parent=tablet?h.root:h.topLiveClip;
  if(parent.child!=null)throw new AssertionError("empty top attaches a placeholder");checks++;
  if(h.geometryUpdates!=1)throw new AssertionError("top geometry was not initialized");checks++;
  if(upper.hint==null)throw new AssertionError("missing task accessibility hint");checks++;
  if(upper.view!=liveView||upper.controller!=controller||upper.taskToken!=token)throw new AssertionError("creation changes the real task owner");checks++;
 }System.out.println("actual asynchronous TOP controls creation checks="+checks+" passed");}
}'''.replace('__CREATE__',member('    void createTopTaskControls()', (src/'WorkspaceTopPresentationController.java').read_text(encoding='utf-8').replace('host.', '').replace('slotRegistry.','').replace('sideRows.','').replace('windowPresentation.','').replace('liveGeometry.','').replace('liveHandoff.','').replace('promotionController.','').replace('topPresentationHost.','').replace('topAdmission.','').replace('taskTransfer.','').replace('keyguard.','').replace('resumes.','').replace('carriers.','').replace('retainedSurfaces.','').replace('chrome.','').replace('mainTaskActions.','').replace('taskStates.','').replace('slotLifecycle.','').replace('sideInteraction.','').replace('displays.','').replace('wallpapers.','').replace('memory.','').replace('debug.','').replace('scales.','').replace('cells.','').replace('exits.','').replace('mainLaunch.','').replace('transitionWindow.','').replace('contentDrag.','').replace('sideCloses.','').replace('WorkspaceHost.', '')))
files['io/github/zerohomers0/onesteplite/WorkspaceRegression.java']=files['io/github/zerohomers0/onesteplite/WorkspaceRegression.java'].replace('h.beginWorkspaceEntry(()->h.ready++);','WorkspaceRegression initial=h;h.beginWorkspaceEntry(()->initial.ready++);')
for name,text in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8')
jdk=Path(os.environ["ONESTEPLITE_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out)]+[str(out/n)for n in files]+[str(package_dir(root)/'WorkspaceCellReveal.java'),str(package_dir(root)/'WorkspaceMotionSpec.java'),str(package_dir(root)/'WorkspaceRevealCurve.java'),str(src/'WorkspaceTransition.java'),str(src/'TransitionSession.java'),str(src/'framework/platform/TransactionCompat.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.WorkspaceRegression'],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.TopControlsCreationRegression'],check=True)
