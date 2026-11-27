"""Execute the actual asynchronous Host wallpaper loader: stale, locked, resize and failed captures."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
s=(src/'WorkspaceWallpaperController.java').read_text('utf-8');a=s.index('    void loadWallpaperBackdrop()');b=s.index('{',a)+1;depth=1
while depth:depth+=(s[b]=='{')-(s[b]=='}');b+=1
method=s[a:b]
a=s.index('    void finishWallpaperLoad(');b=s.index('{',a)+1;depth=1
while depth:depth+=(s[b]=='{')-(s[b]=='}');b+=1
method+='\n'+s[a:b]
a=s.index('    void scheduleWallpaperRetry(');b=s.index('{',a)+1;depth=1
while depth:depth+=(s[b]=='{')-(s[b]=='}');b+=1
method+='\n'+s[a:b]
method=method.replace('host.','').replace('slotRegistry.','').replace('sideRows.','').replace('windowPresentation.','').replace('liveGeometry.','').replace('liveHandoff.','').replace('promotionController.','').replace('topPresentationHost.','').replace('topAdmission.','').replace('taskTransfer.','').replace('keyguard.','').replace('resumes.','').replace('carriers.','').replace('retainedSurfaces.','').replace('chrome.','').replace('mainTaskActions.','').replace('taskStates.','').replace('slotLifecycle.','').replace('sideInteraction.','').replace('displays.','').replace('wallpapers.','').replace('memory.','').replace('debug.','').replace('scales.','').replace('cells.','').replace('motion()','cells.motion()').replace('exits.','').replace('mainLaunch.','').replace('transitionWindow.','').replace('contentDrag.','').replace('sideCloses.','')
method=method.replace('WorkspaceHost.','').replace('log.accept(','log(')
method+='\n int wallpaperRetryAttempt;'
method+='\n final Runnable wallpaperRetry=this::loadWallpaperBackdrop;'
java=r'''package io.github.zerohomers0.onesteplite;
import java.util.*;
public class WallpaperRefreshRegression {
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static class Bitmap {boolean recycled;int width=360,height=800;void recycle(){recycled=true;}boolean isRecycled(){return recycled;}int getWidth(){return width;}int getHeight(){return height;}}
 static class Context {boolean interactive=true;}
 static class Handler {List<Runnable>queue=Collections.synchronizedList(new ArrayList<>());int retries;long delay;Runnable delayed;void post(Runnable r){queue.add(r);}void removeCallbacks(Runnable r){if(delayed==r)delayed=null;}void postDelayed(Runnable r,long d){retries++;delay=d;delayed=r;}void drain(){while(!queue.isEmpty())queue.remove(0).run();}}
 static class HyperOsStyle {static boolean fail;static List<Bitmap>produced=Collections.synchronizedList(new ArrayList<>());static Bitmap blurredWallpaper(Context c,int w,int h){if(fail)return null;Bitmap b=new Bitmap();produced.add(b);return b;}static Bitmap blurredBitmap(Bitmap b,int w,int h){Bitmap next=new Bitmap();produced.add(next);return next;}}
 static class WorkspaceFrost {static Bitmap last;static Bitmap finish(Bitmap b){last=b;return b;}}
 static class WorkspaceBackground {static Bitmap custom;static Bitmap loadBlurred(Context c,int w,int h){return custom;}}
 static class LiveBackdrop {Bitmap texture;void replaceWallpaper(Bitmap bitmap,int tint){texture=bitmap;}}
 static class CurrentUser {static int id(){return 0;}}
 static class Warmup {static Bitmap[] ready;static Bitmap[] takePreparedWallpaper(int u,int w,int h){Bitmap[] result=ready;ready=null;return result;}}
 static class WallpaperSnapshot {static boolean canCapture(Context c){return c.interactive;}}
 static class Clipboard {static Clipboard get(Context c){return new Clipboard();}void close(){}}
 Bitmap wallpaperBackdrop=new Bitmap(),wellWallpaperBackdrop=new Bitmap();boolean wallpaperBackdropDirty=true,wallpaperBackdropRequested;long wallpaperLoadGeneration;Object mainSideAnimator,workspaceAnimator,promotion,cellMotion;int screenW=1080,screenH=2400,applies,scene=1;static final int ACTIVE=1,RESUMING=2;boolean screenOn=true,keyguardLocked;Object root=new Object();Context ctx=new Context();Handler main=new Handler();LiveBackdrop workspaceBackdrop; final class Cells { Object motion(){return cellMotion;} } Cells cells=new Cells();
 void applyWallpaperBackdrops(){if(workspaceBackdrop!=null)workspaceBackdrop.replaceWallpaper(wallpaperBackdrop,0);applies++;check(!wallpaperBackdrop.isRecycled(),"published wallpaper was recycled");}void log(String s){}
 METHOD
 static void waitQueued(WallpaperRefreshRegression h,int count)throws Exception {for(int i=0;i<1000;i++){if(h.main.queue.size()>=count)return;Thread.sleep(2);}throw new AssertionError("capture did not complete");}
 public static void main(String[]args)throws Exception {
  var h=new WallpaperRefreshRegression();Bitmap old=h.wallpaperBackdrop,well=h.wellWallpaperBackdrop;
  h.loadWallpaperBackdrop();waitQueued(h,1);h.main.drain();check(h.applies==1&&!h.wallpaperBackdropDirty&&!h.wallpaperBackdropRequested,"completed refresh publishes once");check(!old.recycled&&!well.recycled,"old drawables and in-flight animations retain live bitmap ownership");
  int count=HyperOsStyle.produced.size();h.loadWallpaperBackdrop();check(HyperOsStyle.produced.size()==count&&h.applies==2,"steady cache hit performs no capture");
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;count=HyperOsStyle.produced.size();h.loadWallpaperBackdrop();waitQueued(h,1);h.wallpaperLoadGeneration++;h.wallpaperBackdropRequested=false;h.loadWallpaperBackdrop();waitQueued(h,2);
  h.main.queue.remove(0).run();check(h.wallpaperBackdrop==old&&h.wallpaperBackdropRequested&&h.applies==0,"stale capture cannot publish or clear the newer request");check(HyperOsStyle.produced.get(count).recycled&&HyperOsStyle.produced.get(count+1).recycled,"only unpublished stale results are recycled");h.main.drain();check(h.applies==1,"newest capture wins");
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;h.ctx.interactive=false;h.screenOn=false;h.loadWallpaperBackdrop();check(!h.wallpaperBackdropRequested&&h.main.retries==0&&h.wallpaperBackdrop==old,"locked state keeps desktop cache without polling");
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;h.loadWallpaperBackdrop();waitQueued(h,1);h.ctx.interactive=false;h.screenOn=false;h.main.drain();check(h.applies==0&&h.wallpaperBackdrop==old&&!old.recycled&&!h.wallpaperBackdropRequested&&h.main.retries==0,"lock between capture and publish cancels retry");
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;h.loadWallpaperBackdrop();waitQueued(h,1);h.screenW=2400;h.main.drain();check(h.applies==0&&h.wallpaperBackdrop==old&&h.main.retries==1&&h.main.delay==1500,"rotation schedules bounded first retry");
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;h.loadWallpaperBackdrop();waitQueued(h,1);h.root=null;h.main.drain();check(h.applies==0&&h.main.retries==0&&!old.recycled,"detached Host never publishes or retains a retry loop");
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;HyperOsStyle.fail=true;h.loadWallpaperBackdrop();waitQueued(h,1);h.main.drain();check(h.applies==0&&h.wallpaperBackdrop==old&&!old.recycled&&h.main.retries==1,"capture failure keeps the last texture instead of flashing blank");HyperOsStyle.fail=false;
  check(h.main.delay==1500,"first failure uses initial retry interval");
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;h.mainSideAnimator=new Object();h.loadWallpaperBackdrop();waitQueued(h,1);h.main.drain();check(h.applies==0&&h.wallpaperBackdrop==old&&h.wallpaperBackdropRequested,"active side transition keeps a single wallpaper texture");h.mainSideAnimator=null;h.main.delayed.run();check(h.applies==1&&!h.wallpaperBackdropRequested,"deferred texture publishes after the motion owner releases");
  h=new WallpaperRefreshRegression();h.wallpaperBackdrop=null;h.wallpaperBackdropDirty=false;Bitmap pre=new Bitmap(),preWell=new Bitmap();Warmup.ready=new Bitmap[]{pre,preWell};count=HyperOsStyle.produced.size();h.loadWallpaperBackdrop();
  waitQueued(h,1);h.main.drain();check(h.wallpaperBackdrop==pre&&h.wellWallpaperBackdrop==preWell&&h.applies==1,"boot glass is published after checking the selected background");
  check(HyperOsStyle.produced.size()==count&&h.main.queue.isEmpty()&&Warmup.ready==null,"warm cache is consumed once without another capture");
  h=new WallpaperRefreshRegression();h.wallpaperBackdropDirty=false;h.wallpaperBackdrop=null;h.workspaceBackdrop=new LiveBackdrop();
  Warmup.ready=new Bitmap[]{pre,preWell};Bitmap imported=new Bitmap();WorkspaceBackground.custom=imported;
  h.loadWallpaperBackdrop();waitQueued(h,1);h.main.drain();
  check(WorkspaceFrost.last==imported&&h.wallpaperBackdrop==imported,"custom image passes through the same ink-glass shader");
  check(h.workspaceBackdrop.texture==imported&&Warmup.ready!=null,"live backing updates and the system cache cannot replace the custom texture");
  WorkspaceBackground.custom=null;h.wallpaperBackdropDirty=true;h.loadWallpaperBackdrop();waitQueued(h,1);h.main.drain();
  check(h.workspaceBackdrop.texture!=imported&&h.wallpaperBackdrop==h.workspaceBackdrop.texture,"reset restores the live system material");
  System.out.println("Production async wallpaper refresh: "+checks+" checks passed");
 }
}'''.replace('METHOD',method)
path=out/'WallpaperRefreshRegression.java';path.write_text(java,'utf-8');jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(path),str(src/'BackgroundExecutor.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.WallpaperRefreshRegression'],check=True)
