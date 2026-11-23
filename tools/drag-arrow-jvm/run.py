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
files={
 'android/os/PersistableBundle.java':'''package android.os;public class PersistableBundle{
 public int touch;public int getInt(String key,int fallback){return touch;}}''',
 'android/view/DragEvent.java':'''package android.view;public class DragEvent{
 public int declared;public float actual;public boolean missing;
 public android.os.PersistableBundle extras=new android.os.PersistableBundle();
 public DragEvent getClipDescription(){return this;}
 public android.os.PersistableBundle getExtras(){return extras;}}''',
 'io/github/zerohomers0/onesteplite/DragArrowRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.view.DragEvent;
class ContentDragShadow{static int offset(DragEvent event){return event.declared;}}
class XposedHelpers{static Object callMethod(DragEvent event,String method){
 if(event.missing)throw new IllegalStateException();return event.actual;}}
public class DragArrowRegression {
__METHOD__
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
 System.out.println("Production drag arrow scaling checks="+checks+" passed");
}}
'''.replace('__METHOD__',source[start:end])}
paths=[]
for name,text in files.items():
    path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8');paths.append(str(path))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.DragArrowRegression'],check=True)
