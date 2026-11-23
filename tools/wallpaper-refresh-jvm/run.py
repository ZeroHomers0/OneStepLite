"""Execute the actual asynchronous Host wallpaper loader: stale, locked, resize and failed captures."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
s=(src/'WorkspaceHost.java').read_text('utf-8');a=s.index('    private void loadWallpaperBackdrop()');b=s.index('{',a)+1;depth=1
while depth:depth+=(s[b]=='{')-(s[b]=='}');b+=1
method=s[a:b]
a=s.index('    private void finishWallpaperLoad(');b=s.index('{',a)+1;depth=1
while depth:depth+=(s[b]=='{')-(s[b]=='}');b+=1
method+='\n'+s[a:b]
java=r'''package io.github.zerohomers0.onesteplite;
import java.util.*;
public class WallpaperRefreshRegression {
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static class Bitmap {boolean recycled;int width=360,height=800;void recycle(){recycled=true;}boolean isRecycled(){return recycled;}int getWidth(){return width;}int getHeight(){return height;}}
 static class Context {boolean interactive=true;}
 static class Handler {List<Runnable>queue=Collections.synchronizedList(new ArrayList<>());int retries;Runnable delayed;void post(Runnable r){queue.add(r);}void removeCallbacks(Runnable r){}void postDelayed(Runnable r,long d){retries++;delayed=r;}void drain(){while(!queue.isEmpty())queue.remove(0).run();}}
 static class HyperOsStyle {static boolean fail;static List<Bitmap>produced=Collections.synchronizedList(new ArrayList<>());static Bitmap blurredWallpaper(Context c,int w,int h){if(fail)return null;Bitmap b=new Bitmap();produced.add(b);return b;}static Bitmap blurredBitmap(Bitmap b,int w,int h){Bitmap next=new Bitmap();produced.add(next);return next;}}
 static class WorkspaceFrost {static Bitmap finish(Bitmap b){return b;}}
 static class CurrentUser {static int id(){return 0;}}
 static class Warmup {static Bitmap[] ready;static Bitmap[] takePreparedWallpaper(int u,int w,int h){Bitmap[] result=ready;ready=null;return result;}}
 static class WallpaperSnapshot {static boolean canCapture(Context c){return c.interactive;}}
 static class Clipboard {static Clipboard get(Context c){return new Clipboard();}void close(){}}
 Bitmap wallpaperBackdrop=new Bitmap(),wellWallpaperBackdrop=new Bitmap();boolean wallpaperBackdropDirty=true,wallpaperBackdropRequested;long wallpaperLoadGeneration;Object mainSideAnimator,workspaceAnimator,promotion,cellMotion;int screenW=1080,screenH=2400,applies;Object root=new Object();Context ctx=new Context();Handler main=new Handler();Runnable wallpaperRetry=()->{};
 void applyWallpaperBackdrops(){applies++;check(!wallpaperBackdrop.isRecycled(),"published wallpaper was recycled");}void log(String s){}
 METHOD
 static void waitQueued(WallpaperRefreshRegression h,int count)throws Exception {for(int i=0;i<1000;i++){if(h.main.queue.size()>=count)return;Thread.sleep(2);}throw new AssertionError("capture did not complete");}
 public static void main(String[]args)throws Exception {
  var h=new WallpaperRefreshRegression();Bitmap old=h.wallpaperBackdrop,well=h.wellWallpaperBackdrop;
  h.loadWallpaperBackdrop();waitQueued(h,1);h.main.drain();check(h.applies==1&&!h.wallpaperBackdropDirty&&!h.wallpaperBackdropRequested,"completed refresh publishes once");check(!old.recycled&&!well.recycled,"old drawables and in-flight animations retain live bitmap ownership");
  int count=HyperOsStyle.produced.size();h.loadWallpaperBackdrop();check(HyperOsStyle.produced.size()==count&&h.applies==2,"steady cache hit performs no capture");
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;count=HyperOsStyle.produced.size();h.loadWallpaperBackdrop();waitQueued(h,1);h.wallpaperLoadGeneration++;h.wallpaperBackdropRequested=false;h.loadWallpaperBackdrop();waitQueued(h,2);
  h.main.queue.remove(0).run();check(h.wallpaperBackdrop==old&&h.wallpaperBackdropRequested&&h.applies==0,"stale capture cannot publish or clear the newer request");check(HyperOsStyle.produced.get(count).recycled&&HyperOsStyle.produced.get(count+1).recycled,"only unpublished stale results are recycled");h.main.drain();check(h.applies==1,"newest capture wins");
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;h.ctx.interactive=false;h.loadWallpaperBackdrop();check(!h.wallpaperBackdropRequested&&h.main.retries==1&&h.wallpaperBackdrop==old,"locked state keeps desktop cache without capturing");
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;h.loadWallpaperBackdrop();waitQueued(h,1);h.ctx.interactive=false;h.main.drain();check(h.applies==0&&h.wallpaperBackdrop==old&&!old.recycled&&!h.wallpaperBackdropRequested&&h.main.retries==1,"lock between capture and publish rejects the result");
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;h.loadWallpaperBackdrop();waitQueued(h,1);h.screenW=2400;h.main.drain();check(h.applies==0&&h.wallpaperBackdrop==old&&h.main.retries==1,"rotation rejects wrong-size result and schedules the new geometry");
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;h.loadWallpaperBackdrop();waitQueued(h,1);h.root=null;h.main.drain();check(h.applies==0&&h.main.retries==0&&!old.recycled,"detached Host never publishes or retains a retry loop");
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;HyperOsStyle.fail=true;h.loadWallpaperBackdrop();waitQueued(h,1);h.main.drain();check(h.applies==0&&h.wallpaperBackdrop==old&&!old.recycled&&h.main.retries==1,"capture failure keeps the last texture instead of flashing blank");HyperOsStyle.fail=false;
  h=new WallpaperRefreshRegression();old=h.wallpaperBackdrop;h.mainSideAnimator=new Object();h.loadWallpaperBackdrop();waitQueued(h,1);h.main.drain();check(h.applies==0&&h.wallpaperBackdrop==old&&h.wallpaperBackdropRequested,"active side transition keeps a single wallpaper texture");h.mainSideAnimator=null;h.main.delayed.run();check(h.applies==1&&!h.wallpaperBackdropRequested,"deferred texture publishes after the motion owner releases");
  h=new WallpaperRefreshRegression();h.wallpaperBackdrop=null;h.wallpaperBackdropDirty=false;Bitmap pre=new Bitmap(),preWell=new Bitmap();Warmup.ready=new Bitmap[]{pre,preWell};count=HyperOsStyle.produced.size();h.loadWallpaperBackdrop();
  check(h.wallpaperBackdrop==pre&&h.wellWallpaperBackdrop==preWell&&h.applies==1,"boot glass is published synchronously before workspace entry");
  check(HyperOsStyle.produced.size()==count&&h.main.queue.isEmpty()&&Warmup.ready==null,"warm cache is consumed once without capture, worker or heartbeat");
  System.out.println("Production async wallpaper refresh: "+checks+" checks passed");
 }
}'''.replace('METHOD',method)
path=out/'WallpaperRefreshRegression.java';path.write_text(java,'utf-8');jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(path)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.WallpaperRefreshRegression'],check=True)
