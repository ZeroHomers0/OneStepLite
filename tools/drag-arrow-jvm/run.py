"""Replay the real drag-offset compatibility method under source-window scaling."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root)
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=(src/'ContentAccessCompat.java').read_text(encoding='utf-8')
start=source.index('    static int arrowOffset(');end=source.index('{',start)+1;depth=1
while depth:
    depth+=(source[end]=='{')-(source[end]=='}');end+=1
methods=source[start:end]
start=source.index('    static float shadowHalfWidth(');end=source.index('{',start)+1;depth=1
while depth:
    depth+=(source[end]=='{')-(source[end]=='}');end+=1
methods+='\n'+source[start:end]
workspace=(src/'ShareWorkspace.java').read_text(encoding='utf-8-sig')
start=workspace.index('    private void refreshTip(');end=workspace.index('{',start)+1;depth=1
while depth:
    depth+=(workspace[end]=='{')-(workspace[end]=='}');end+=1
refresh=workspace[start:end]
files={
 'android/os/PersistableBundle.java':'''package android.os;public class PersistableBundle{
 public int touch,width;public int getInt(String key,int fallback){return key.contains("half_width")?width:touch;}}''',
 'android/view/DragEvent.java':'''package android.view;public class DragEvent{
 public int declared;public float actual,actualX;public boolean missing;
 public android.os.PersistableBundle extras=new android.os.PersistableBundle();
 public DragEvent getClipDescription(){return this;}
 public android.os.PersistableBundle getExtras(){return extras;}}''',
 'io/github/zerohomers0/onesteplite/DragArrowRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.view.DragEvent;
class ContentDragShadow{static int offset(DragEvent event){return event.declared;}}
class XposedHelpers{static Object callMethod(DragEvent event,String method){
 if(event.missing)throw new IllegalStateException();return method.equals("getOffsetX")?event.actualX:event.actual;}}
class ContentAccessCompat{static int arrowOffset(DragEvent e){return DragArrowRegression.arrowOffset(e);}static float shadowHalfWidth(DragEvent e){return DragArrowRegression.shadowHalfWidth(e);}}
class ShareDragCatchment{int extent,closes;ShareDragCatchment(Object o,int offset,java.util.function.BooleanSupplier live){extent=offset;}
void extend(int value){extent=Math.max(extent,value);}void start(){}void close(){closes++;}}
public class DragArrowRegression {
__METHOD__
static class Replay{int tipOffset=312;float dragHalfWidth;boolean dragging=true;java.util.function.BooleanSupplier ready=()->true;
ShareDragCatchment catchment=new ShareDragCatchment(this,312,()->true);
__REFRESH__
}
static int checks;static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
public static void main(String[] args){
 DragEvent event=new DragEvent();event.declared=312;event.extras.touch=321;
 for(float scale:new float[]{.5f,.748f,1f,1.25f}){
  event.actual=321*scale;int offset=arrowOffset(event);
  check(Math.abs(offset-312*scale)<=.5f);
  float railTop=500,tip=railTop+60,pointer=tip+offset;
  check(Math.abs(pointer-offset-tip)<.01f);
 }
 event.actual=Float.NaN;check(arrowOffset(event)==312);
 event.actual=Float.POSITIVE_INFINITY;check(arrowOffset(event)==312);
 event.actual=-12;check(arrowOffset(event)==312);
 event.actual=6000;check(arrowOffset(event)==312);
 event.actual=0;check(arrowOffset(event)==312);
 event.missing=true;check(arrowOffset(event)==312);event.missing=false;
 event.extras.touch=1;event.actual=4096;check(arrowOffset(event)==312);
 event.extras.touch=0;check(arrowOffset(event)==312);
 event.declared=0;check(arrowOffset(event)==0);
 event.declared=312;event.extras.touch=321;event.actual=0;
 Replay session=new Replay();ShareDragCatchment reach=session.catchment;
 event.actual=240;event.actualX=150;session.refreshTip(event);
 check(session.tipOffset==233&&session.dragHalfWidth==150);
 check(session.catchment==reach&&reach.closes==0);
 event.actual=400;session.refreshTip(event);check(session.tipOffset==389&&reach.extent==389);
 event.actualX=Float.NaN;event.extras.width=252;check(shadowHalfWidth(event)==252);
 event.actualX=300;check(shadowHalfWidth(event)==300);
 event.actualX=9999;check(shadowHalfWidth(event)==252);
 event.declared=0;check(shadowHalfWidth(event)==0);
 System.out.println("Production drag arrow scaling checks="+checks+" passed");
}}
'''.replace('__METHOD__',methods).replace('__REFRESH__',refresh)}
paths=[]
for name,text in files.items():
    path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8');paths.append(str(path))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.DragArrowRegression'],check=True)
