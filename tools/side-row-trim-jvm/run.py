from pathlib import Path
import os, subprocess, sys, re
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir

root = Path(__file__).resolve().parents[2]
src = package_dir(root)
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
text = (src/'WorkspaceSideRowController.java').read_text(encoding='utf-8-sig')

def member(sig):
    start=text.index(sig); brace=text.index('{',start); depth=1; end=brace+1
    while depth:
        depth += (text[end]=='{')-(text[end]=='}'); end+=1
    return text[start:end]

def adapt_side(text):
 text=text.replace('workspace.','').replace('WorkspaceHost.log','log').replace('WorkspaceHost.ACTIVE','ACTIVE').replace('cellSurfaces.removeCellBackground(slot)','removeCellBackground(slot)')
 for old,new in [('order','sideOrder'),('compacting','sideCompacting'),('captured','sideCaptured'),('desktopRevealing','sideDesktopRevealing'),('creatingView','creatingSideView'),('attached','sideAttached'),('compactionShift','sideCompactionShift'),('savedOffset','savedSideOffset'),('offset','sideOffset')]:
  text=re.sub(r'\b'+old+r'\b',new,text)
 return text

body='''package io.github.zerohomers0.onesteplite;
import java.util.*;
import android.view.*;
public class SideRowTrimRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static final class WorkspaceLayout {static final int CELL_COUNT=3;}
 static final class Slot {
  int index;View view,hint;Object controller;int taskId=-1;Object taskToken;
  boolean occupied,parked,promoting,launching,frozen,closing,nativeReleasePending,carrier;
 }
 static int releases;static final class ShellCompat {static Object call(Object o,String n,Object...a){if("release".equals(n))releases++;return null;}}
 View root=new View();int scene=ACTIVE;static final int ACTIVE=1,SLOT_COUNT=6;
 boolean sideScrolling,sideCompacting,sideDesktopRevealing,creatingSideView,transferBusy,sideCaptured;Object promotion;
 float sideOffset,savedSideOffset;Slot[] slots=new Slot[8];
 final SideRowOrder<Slot> sideOrder=new SideRowOrder<>();final List<Slot> sideAttached=new ArrayList<>();
 final IdentityHashMap<Slot,Float> sideCompactionShift=new IdentityHashMap<>();
 void removeCellBackground(Slot slot){}boolean isSideMotionRunning(){return false;}static void log(String s){}
 __METHODS__
 public static void main(String[] args){
  SideRowTrimRegression h=new SideRowTrimRegression();
  for(int i=0;i<8;i++){h.slots[i]=new Slot();h.slots[i].index=i;h.slots[i].controller=new Object();h.slots[i].view=new View();h.slots[i].hint=new View();}
  for(int i=1;i<=5;i++)h.sideOrder.add(h.slots[i]);
  for(int i=1;i<=3;i++)h.slots[i].occupied=true;
  h.trimTrailingEmptySideRows();
  check(h.sideOrder.size()==3&&h.slots.length==6&&releases==2,"empty tail rows release TaskViews and shrink slots");
  releases=0;SideRowTrimRegression scrolled=new SideRowTrimRegression();
  scrolled.slots=new Slot[10];for(int i=0;i<10;i++){scrolled.slots[i]=new Slot();scrolled.slots[i].index=i;scrolled.slots[i].controller=new Object();scrolled.slots[i].view=new View();scrolled.slots[i].hint=new View();}
  for(int i=1;i<=8;i++)scrolled.sideOrder.add(scrolled.slots[i]);for(int i=1;i<=4;i++)scrolled.slots[i].occupied=true;
  scrolled.sideScrolling=true;scrolled.sideOffset=scrolled.savedSideOffset=2f;scrolled.trimTrailingEmptySideRows();
  check(scrolled.sideOrder.size()==5&&scrolled.slots.length==8&&releases==3&&scrolled.sideOffset==2f,"scrolled views reclaim only empty rows below the viewport");
  releases=0;SideRowTrimRegression busy=new SideRowTrimRegression();
  for(int i=0;i<7;i++){busy.slots[i]=new Slot();busy.slots[i].index=i;busy.slots[i].controller=new Object();busy.slots[i].view=new View();busy.slots[i].hint=new View();}
  for(int i=1;i<=4;i++)busy.sideOrder.add(busy.slots[i]);for(int i=1;i<=4;i++)busy.slots[i].occupied=true;
  busy.trimTrailingEmptySideRows();check(busy.sideOrder.size()==4&&busy.slots.length==8,"occupied rows are never trimmed");
  System.out.println("SideRowTrim checks="+checks+" passed");
 }
}
'''.replace('__METHODS__', adapt_side(member('    private void trimTrailingEmptySideRows() {'))+'\n'+adapt_side(member('    private void releaseDetachedSideRow(Slot slot) {')))
(out/'android/view/View.java').parent.mkdir(parents=True,exist_ok=True)
(out/'android/view/View.java').write_text('package android.view;public class View {public ViewGroup parent;public ViewGroup getParent(){return parent;}}',encoding='utf-8')
(out/'android/view/ViewGroup.java').write_text('package android.view;public class ViewGroup extends View {public void removeView(View v){v.parent=null;}}',encoding='utf-8')
p=out/'io/github/zerohomers0/onesteplite/SideRowTrimRegression.java';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8')
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(out/'android/view/View.java'),str(out/'android/view/ViewGroup.java'),str(src/'SideRowOrder.java'),str(src/'WorkspaceRuntimeCounters.java'),str(p)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.SideRowTrimRegression'],check=True)
