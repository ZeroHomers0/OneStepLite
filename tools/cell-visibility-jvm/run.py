"""Run the actual Host visibility/preparation methods across native ownership handoffs."""
import os
import sys as _sys, pathlib as _pathlib; _sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1])); from source_paths import package_dir
from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parents[2]
host=(package_dir(root)/'WorkspaceHost.java').read_text(encoding='utf-8-sig')
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
def method(signature):
    a=host.index(signature);b=host.index('{',a)+1;depth=1
    while depth:
        depth+=(host[b]=='{')-(host[b]=='}');b+=1
    return host[a:b]
body=r'''
package io.github.zerohomers0.onesteplite;
import android.view.SurfaceControl;import android.graphics.Rect;
import de.robv.android.xposed.XposedHelpers;
public class CellVisibilityRegression {
 Rect workspaceCellCrop(Slot slot){return null;}
 Rect logicalBounds(Slot slot){return new Rect(0,0,1080,2460);}
 static int checks;static final int SLOT_TOP=4,CELL_SLOT_COUNT=4;boolean topFace,taskCellsCovered;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 float workspaceAlpha=1f;Object cellMotion;Promotion promotion;boolean owns=true,sideScrolling,writable=true;
 static class TopTaskTransition {boolean collecting;boolean ownsTopOpacity(){return !collecting;}boolean ownsTaskOpacity(){return collecting;}}TopTaskTransition topTransition;
 static class Promotion{boolean nativeSwap=true,cancelled;}
 static class View{float alpha;void setAlpha(float a){alpha=a;}void setClipBounds(Rect r){}}
 static class Slot{int index=1,sideRow=-1;boolean occupied,parked,promoting,launching,sideShown,sidePrepared,frozen;int taskId=42;Object taskToken=new Object();View view=new View();Object controller=new SurfaceControl();}
 boolean mayWriteSideSurface(Slot s){return writable;}
 Object getSurfaceField(Object c,String f){SurfaceControl p=(SurfaceControl)c;if(f.equals("mTaskLeash")){if(p.child==null)p.child=new SurfaceControl();return p.child;}return p;}
 boolean controllerOwns(Slot s,int id,Object token){return owns;}
 static void closeSurfaceObject(Object o,String m){if(o!=null)XposedHelpers.callMethod(o,m);}
 static void log(String s){throw new AssertionError(s);}
 __METHODS__
 public static void main(String[]args){
  CellVisibilityRegression h=new CellVisibilityRegression();Slot s=new Slot();SurfaceControl surface=(SurfaceControl)s.controller;
  s.view.alpha=1;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==0&&surface.alpha==0&&!surface.shown,"settled empty still hides resize white board");
  SurfaceControl.ops.clear();h.prepareCellSurface(s);
  check(s.view.alpha==1&&surface.alpha==1,"destination hole and parent ready before native handoff");
  check(!surface.shown&&!SurfaceControl.ops.contains("show")&&!SurfaceControl.ops.contains("hide"),"preparation leaves native visibility ownership intact");
  SurfaceControl.ops.clear();s.launching=true;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==1&&SurfaceControl.ops.isEmpty(),"pending launcher keeps preopened destination");
  s.launching=false;s.promoting=true;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==1&&SurfaceControl.ops.isEmpty(),"adoption owns visibility until settled");
  s.promoting=false;h.promotion=new Promotion();h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==1&&SurfaceControl.ops.isEmpty(),"native clear phase cannot hide just committed parent");
  s.occupied=true;h.owns=false;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==1&&SurfaceControl.ops.isEmpty(),"temporarily borrowed peer is not mistaken for removed task");
  h.owns=true;h.promotion.cancelled=true;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==1&&surface.shown&&surface.alpha==1,"final binding reconciles visible task");
  check(!SurfaceControl.ops.contains("hide"),"clear-bind replacement emitted no intermediate hide");
  SurfaceControl.ops.clear();s.occupied=false;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==0&&!surface.shown,"retired empty destination hidden after native ownership ends");
  h.workspaceAlpha=.45f;h.prepareCellSurface(s);check(surface.alpha==1f&&s.view.alpha==1f,"preparation keeps live tasks opaque throughout workspace transition");
  SurfaceControl.ops.clear();surface.valid=false;s.view.alpha=0;h.prepareCellSurface(s);
  check(s.view.alpha==1&&!SurfaceControl.ops.contains("alpha")&&!SurfaceControl.ops.contains("show"),"future surface can open hole without invalid native surface operations");
  surface.valid=true;s.occupied=true;s.index=SLOT_TOP;h.workspaceAlpha=1f;
  h.updateCellSurfaceVisibility(s);check(s.view.alpha==0&&!surface.shown,"closed upper face hides actual task surface");
  h.topFace=true;h.updateCellSurfaceVisibility(s);check(s.view.alpha==1&&surface.shown,"settled upper face shows live task surface");
  s.index=1;h.taskCellsCovered=true;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==0&&!surface.shown&&s.occupied,"expanded controls hide real cell presentation without releasing its task");
  h.taskCellsCovered=false;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==1&&surface.shown,"fade close restores the same live task");
  s.index=SLOT_TOP;h.topFace=true;h.taskCellsCovered=true;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==1&&surface.shown,"independent interactive pane is not hidden by top-row controls");
  h.topTransition=new TopTaskTransition();surface.alpha=.6f;surface.child.alpha=.4f;s.view.alpha=1f;
  h.updateCellSurfaceVisibility(s);
  check(surface.alpha==.6f&&surface.child.alpha==.4f&&s.view.alpha==1f,"visibility repair preserves the active top fade");
  h.topTransition.collecting=true;surface.child.alpha=0f;h.updateCellSurfaceVisibility(s);
  check(surface.alpha==1f&&surface.child.alpha==0f,"admission prepares the parent without flashing the transparent canonical task");
  h.workspaceAlpha=0;h.updateCellSurfaceVisibility(s);
  check(surface.alpha==0&&surface.child.alpha==0,"privacy hiding still overrides a top fade");
  h.topTransition=null;h.workspaceAlpha=1f;
  h.taskCellsCovered=false;s.index=8;s.sideRow=5;h.updateCellSurfaceVisibility(s);
  check(!surface.shown&&s.occupied,"offscreen list row hides the surface without clearing its task");
  s.sideShown=true;h.updateCellSurfaceVisibility(s);
  check(surface.shown&&s.taskId==42,"partly exposed list row shows the original task");
  h.sideScrolling=true;s.sideShown=false;s.sidePrepared=true;h.updateCellSurfaceVisibility(s);
  check(surface.shown&&s.taskId==42,"viewport parent clips a warm task before it appears");
  s.sidePrepared=false;h.updateCellSurfaceVisibility(s);
  check(!surface.shown&&s.taskId==42,"a distant row can retire its surface without losing the task");
  h.sideScrolling=false;s.sideRow=-1;s.index=1;s.sideShown=true;s.occupied=true;
  for(float progress:new float[]{.1f,.5f,.9f}){h.workspaceAlpha=progress;h.updateCellSurfaceVisibility(s);
   check(surface.alpha==1f&&s.view.alpha==1f,"RenderThread and module agree on opaque live content at every entry frame");}
  h.workspaceAlpha=0;h.updateCellSurfaceVisibility(s);check(surface.alpha==0&&s.view.alpha==0,"first visible Host frame cannot resurrect the first retained app");
  surface.alpha=1;check(surface.child.alpha==0,"RenderThread parent rewrite cannot reveal zero-gated task leash");
  h.workspaceAlpha=.5f;s.frozen=true;surface.child.alpha=.37f;h.updateCellSurfaceVisibility(s);check(surface.child.alpha==.37f,"native-frozen task retains its own leash opacity");
  s.frozen=false;h.writable=false;h.updateCellSurfaceVisibility(s);check(surface.child.alpha==.37f,"external native task cannot be pulled back by entry opacity gate");
  h.writable=true;h.workspaceAlpha=.5f;h.updateCellSurfaceVisibility(s);
  check(surface.child.crop!=null&&surface.child.crop.left==0&&surface.child.crop.top==0&&surface.child.crop.right==1080&&surface.child.crop.bottom==2460,"task leash keeps the logical screen crop so the buffer is never cut to a cell corner");
  System.out.println("Actual Host cell visibility checks="+checks+" passed");
 }
}
'''.replace('__METHODS__','\n'.join(method(s) for s in ['    private boolean presentsSlot(', '    private boolean ownsCellPresentation(', '    private boolean cellPresentationVisible(', '    private void updateCellSurfaceVisibility(', '    private void prepareCellSurface(', '    private void stageCellPresentation(']))
files={
'android/graphics/Rect.java':'package android.graphics;public class Rect {public int left,top,right,bottom;public Rect(){}public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}public boolean isEmpty(){return right<=left||bottom<=top;}}',
'io/github/zerohomers0/onesteplite/CellVisibilityRegression.java':body,
'android/view/SurfaceControl.java':r'''package android.view;import java.util.*;import android.graphics.Rect;
public class SurfaceControl {public SurfaceControl child;public boolean valid=true,shown;public float alpha;public Rect crop;public boolean isValid(){return valid;}public static final List<String> ops=new ArrayList<>();
 public static class Transaction implements AutoCloseable {
  public Transaction setAlpha(SurfaceControl s,float a){s.alpha=a;ops.add("alpha");return this;}
  public Transaction setWindowCrop(SurfaceControl s,Rect r){s.crop=r;ops.add("crop");return this;}
  public Transaction show(SurfaceControl s){s.shown=true;ops.add("show");return this;}
  public Transaction hide(SurfaceControl s){s.shown=false;ops.add("hide");return this;}
  public void apply(){ops.add("apply");}public void close(){}
 }}''',
'de/robv/android/xposed/XposedHelpers.java':r'''package de.robv.android.xposed;public class XposedHelpers{
 public static Object callMethod(Object o,String name,Object...args){try{for(java.lang.reflect.Method m:o.getClass().getMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length)return m.invoke(o,args);throw new NoSuchMethodException(name);}catch(Exception e){throw new RuntimeException(e);}}
}'''}
for p,s in files.items():
    path=out/p;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(s,encoding='utf-8')
for sig,anchor in [('    private void startLiveCollect(','statusBar.runAfterHiddenFrame('),('    private void adoptExisting(Slot slot, LiveTask task, boolean collectFromMain,','if (!executeOnShell('),('    private void startSlot(','PendingIntent pi')]:
    source=method(sig)
    assert 'prepareCellSurface(slot);' in source
    assert source.index('prepareCellSurface(slot);')<source.index(anchor)
assert 'for (Slot cell : slots)' in method('    private void restoreLiveSwapVisual(')
created=method('    private void onTaskViewCreated(')
assert 'slot.hint.setOnClickListener(v -> { if (!taskCellsCovered)' in created
assert 'view.setOnClickListener(v -> { if (!taskCellsCovered)' in created
# Explicit shortcut/settings commands must still reuse the existing task dispatcher.
assert 'taskCellsCovered' not in method('    private void onSlotTapped(')
jdk=Path(os.environ["ONESTEPLITE_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',str(out),*[str(out/p) for p in files]],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.CellVisibilityRegression'],check=True)
print('Actual entry/retirement ordering checks=4 passed')
