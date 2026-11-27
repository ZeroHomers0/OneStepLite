"""Run actual Host compaction visual leases with an independent TOP pane."""
import os
from pathlib import Path
import subprocess
import sys
import sys as _sys, pathlib as _pathlib; _sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1])); from source_paths import package_dir

root = Path(__file__).resolve().parents[2]
host = (package_dir(root)/'WorkspaceHost.java').read_text(encoding='utf-8-sig')
cell = (package_dir(root)/'WorkspaceCellMotionController.java').read_text(encoding='utf-8-sig')
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)


def member(signature, source=host):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
        end += 1
    return source[start:end]


cell_source = (package_dir(root)/'CellMovePlan.java').read_text(encoding='utf-8-sig')
cell_start = cell_source.index('final class CellMovePlan {')
cell_brace = cell_source.index('{', cell_start)
cell_depth = 1
cell_end = cell_brace + 1
while cell_depth:
    if cell_source[cell_end] == '{': cell_depth += 1
    elif cell_source[cell_end] == '}': cell_depth -= 1
    cell_end += 1
cell_class = cell_source[cell_start:cell_end].replace('final class CellMovePlan','static final class CellMovePlan',1)
methods = cell_class + '\n' + '\n'.join(member(s, cell) for s in [
    '    private static int compactionHintMask(',
    '    void animateCellMoves(',
]).replace('host.', '').replace('WorkspaceHost::log', 'CompactionChromeRegression::log').replace('WorkspaceHost.log', 'log').replace('WorkspaceHost.getSurfaceField', 'getSurfaceField')
body = r'''
package io.github.zerohomers0.onesteplite;
import java.util.*;
import java.util.function.*;
public class CompactionChromeRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class View extends android.view.View {
  static final int GONE=8;float alpha=1;int visibility;
  public float getAlpha(){return alpha;}public void setAlpha(float v){alpha=v;}
  public int getVisibility(){return visibility;}void invalidate(){}
 }
 static class FrameLayout extends View {}
 static class Rect {int left,top;Rect(int l,int t){left=l;top=t;}}
 static class Slot {
  int index,taskId;long taskEpoch;Object taskToken=new Object(),controller=new Object();
  View view=new View(),hint=new View();int[]rect={0,100,100,200};boolean occupied;
  Slot(int i){index=i;taskId=20+i;}
 }
 static class WorkspaceLayout {}
 static class XposedHelpers {static Object callMethod(Object o,String n,Object...args){return true;}}
 static class LiveBackdrop {
  static boolean fail;static LiveBackdrop latest;int closes;
  static LiveBackdrop create(Object s,Object b,int w,int h,Rect a,Rect z,Consumer<Object> draw,Consumer<String> log){
   if(fail)throw new IllegalStateException("backdrop");
   return latest=new LiveBackdrop();
  }
  void closeAfterFrame(View v){closes++;}
 }
 static class CellMotion {
  record Move(View view,int x,int y,int toX,int toY,BooleanSupplier current){}
  Runnable commit,release;boolean started;
  CellMotion(FrameLayout root,List<Move> moves,Runnable c,Runnable r,Consumer<String> log){commit=c;release=r;}
  void start(){started=true;}
  void finish(){commit.run();release.run();}
 }
 static final int SLOT_MAIN=0,SLOT_COUNT=6;int CELL_SLOT_COUNT=4;
 FrameLayout root=new FrameLayout();long sceneEpoch=7;Slot[]slots=new Slot[6];
 View[]sideBackingViews=new View[6];WorkspaceLayout layout=new WorkspaceLayout();
 Object wallpaperBackdrop=new Object();int screenW=1080,screenH=2460;
 CellMotion cellMotion;boolean transferBusy,compactPending;int layouts;
 CompactionChromeRegression(){for(int i=0;i<6;i++){slots[i]=new Slot(i);sideBackingViews[i]=new View();}}
 Object getSurfaceField(Object c,String n){return c;}
 boolean canRender(Slot s){return true;}
 boolean cellMoveCurrent(Slot s,FrameLayout r,long e,long b,int id,Object t,Object c,View v,Object surface){return true;}
 Object liveHostRootSurface(){return new Object();}
 Rect liveSideStrip(WorkspaceLayout l){return new Rect(0,100);}
 void drawLiveEmptyHints(Object c,WorkspaceLayout a,WorkspaceLayout b,int mask){}
 void finishCellLayout(){layouts++;}
 void updateCellSurfaceVisibility(Slot s){}
 void updateTopBarState(){}
 void compactCells(){compactPending=false;}
 void finishPendingSuspend(){}
 static void log(String message){}
 __METHODS__
 void checkRange(int cells,boolean occupiedTop){
  CELL_SLOT_COUNT=cells;slots[5].occupied=occupiedTop;slots[1].occupied=true;
  sideBackingViews[5].alpha=.73f;slots[5].hint.alpha=.61f;
  sideBackingViews[4].alpha=.42f;slots[4].hint.alpha=.32f;
  animateCellMoves(List.of(new CellMovePlan(slots[1],new Rect(0,300))),1<<1,false);
  check(cellMotion!=null&&cellMotion.started&&transferBusy,"ordinary compaction started");
  check(sideBackingViews[5].alpha==.73f&&slots[5].hint.alpha==.61f,
    "ordinary compaction must never hide independent TOP wallpaper or empty hint");
  if(cells==4)check(sideBackingViews[4].alpha==.42f&&slots[4].hint.alpha==.32f,
    "inactive fourth cell is outside phone compaction");
  for(int i=1;i<cells;i++)check(sideBackingViews[i].alpha==0&&slots[i].hint.alpha==0,
    "ordinary strip has one temporary wallpaper/hint owner");
  LiveSwapVisualLease.writeAlpha(slots[1].hint,.35f);
  CellMotion running=cellMotion;running.finish();
  check(cellMotion==null&&!transferBusy&&layouts==1,"commit and release settle once");
  check(slots[1].hint.alpha==.35f,"restoration keeps the latest workspace fade");
  check(sideBackingViews[5].alpha==.73f&&slots[5].hint.alpha==.61f,"TOP remains unchanged after commit");
  check(LiveBackdrop.latest.closes==2,"temporary wallpaper retirement is requested at both lifecycle gates");
 }
 public static void main(String[] args){
  for(int cells:new int[]{4,5})for(boolean top:new boolean[]{false,true})
   new CompactionChromeRegression().checkRange(cells,top);
  var h=new CompactionChromeRegression();LiveBackdrop.fail=true;
  h.animateCellMoves(List.of(new CellMovePlan(h.slots[1],new Rect(0,300))),0,false);
  check(h.cellMotion==null&&!h.transferBusy&&h.layouts==1,"failed backdrop retains live owner layout");
  check(h.sideBackingViews[5].alpha==1&&h.slots[5].hint.alpha==1,"failure leaves TOP untouched");
  h=new CompactionChromeRegression();LiveBackdrop.fail=false;h.slots[2].controller=null;
  h.animateCellMoves(List.of(new CellMovePlan(h.slots[1],new Rect(0,300)),
    new CellMovePlan(h.slots[2],new Rect(0,500))),0,true);
  check(h.cellMotion==null&&!h.transferBusy&&h.layouts==1,"a missing exchange surface settles both windows without a one-sided animation");
  System.out.println("actual compaction chrome checks="+checks+" passed");
 }
}
'''.replace('__METHODS__', methods.replace('cellSurfaces.','').replace('resumes.',''))
source = out / 'CompactionChromeRegression.java'
source.write_text(body, encoding='utf-8')
view_stub = out / 'android/view/View.java'
view_stub.parent.mkdir(parents=True, exist_ok=True)
view_stub.write_text('package android.view; public class View {public static final int VISIBLE=0,INVISIBLE=4,GONE=8; float alpha=1; int visibility; public float getAlpha(){return alpha;} public void setAlpha(float v){alpha=v;} public int getVisibility(){return visibility;}}',encoding='utf-8')
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), str(view_stub), str(source), str(package_dir(root)/'LiveSwapVisualLease.java')], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.CompactionChromeRegression'], check=True)
