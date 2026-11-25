"""Replay the real arrow-tip hit conversion across the catchment window boundary."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root)
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
workspace=(src/'ShareWorkspace.java').read_text(encoding='utf-8-sig')
start=workspace.index('    private void locateDragTip(');end=workspace.index('{',start)+1;depth=1
while depth:
    depth+=(workspace[end]=='{')-(workspace[end]=='}');end+=1
locate=workspace[start:end]
files={
 'android/view/View.java':'''package android.view;public class View{
 public int[] screen=new int[]{0,0};public boolean attached=true;
 public boolean isAttachedToWindow(){return attached;}
 public void getLocationOnScreen(int[] out){out[0]=screen[0];out[1]=screen[1];}}''',
 'android/view/DragEvent.java':'''package android.view;public class DragEvent{
 public float x,y;public float getX(){return x;}public float getY(){return y;}
 public DragEvent getClipDescription(){return this;}}''',
 'io/github/zerohomers0/onesteplite/DragTipOriginRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.view.*;
class ContentDragMotion{static boolean aim;static float[] point;
 static boolean physicalTip(Object description,float[] out){if(!aim)return false;out[0]=point[0];out[1]=point[1];return true;}}
public class DragTipOriginRegression{
static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
static class Replay extends View{
 float tipOffset=312;float[] physicalAim=new float[2];int[] railOrigin=new int[2],plateOrigin=new int[2];
 float lastX,lastY;boolean located;
 void refreshTip(DragEvent event){}
 void locate(float x,float y){located=true;lastX=x;lastY=y;}
__LOCATE__
}
public static void main(String[] args){
 DragEvent event=new DragEvent();
 // The catchment is a separate window whose origin sits below the status bar while the rail
 // starts at the top of the screen. A finger at local y=500 is at screen y=622, so the arrow
 // tip is at screen y=310 and rail-local y=310.
 Replay rail=new Replay();rail.screen=new int[]{0,0};
 View plate=new View();plate.screen=new int[]{0,122};
 event.x=40;event.y=500;
 rail.locateDragTip(event,plate);
 check(rail.located,"converted aim recorded");
 check(Math.abs(rail.lastY-310f)<.01f,"tip measured in rail space, not window space");
 check(Math.abs(rail.lastX-40f)<.01f,"x converted through screen space");
 // The same finger reported inside the rail's own window needs no shift.
 Replay own=new Replay();own.screen=new int[]{0,0};
 event.x=40;event.y=622;
 own.locateDragTip(event,null);
 check(Math.abs(own.lastY-310f)<.01f,"own-window event keeps the same tip");
 check(Math.abs(own.lastY-rail.lastY)<.01f,"both windows agree on the tip");
 // A detached plate cannot be measured; the raw event stays authoritative.
 Replay detached=new Replay();detached.screen=new int[]{0,0};
 View gone=new View();gone.attached=false;gone.screen=new int[]{0,122};
 event.x=40;event.y=500;
 detached.locateDragTip(event,gone);
 check(Math.abs(detached.lastY-188f)<.01f,"detached plate falls back to raw coordinates");
 // The physical pointer sample wins over any window conversion.
 Replay physical=new Replay();physical.screen=new int[]{0,0};
 ContentDragMotion.aim=true;ContentDragMotion.point=new float[]{300,120};
 event.x=40;event.y=500;
 physical.locateDragTip(event,plate);
 check(Math.abs(physical.lastX-300f)<.01f&&Math.abs(physical.lastY-120f)<.01f,"physical tip bypasses window conversion");
 ContentDragMotion.aim=false;
 System.out.println("Production drag tip origin checks="+checks+" passed");
}}
'''.replace('__LOCATE__',locate)}
paths=[]
for name,text in files.items():
    path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8');paths.append(str(path))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.DragTipOriginRegression'],check=True)
