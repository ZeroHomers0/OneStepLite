"""Replay the actual authenticated snapshot after a late fullscreen native finish."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root = Path(__file__).resolve().parents[2]
src = package_dir(root)
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
host = (src / 'WorkspaceKeyguardController.java').read_text(encoding='utf-8').replace('host.', '').replace('slotRegistry.','').replace('sideRows.','').replace('windowPresentation.','').replace('liveGeometry.','').replace('liveHandoff.','').replace('promotionController.','').replace('topPresentationHost.','').replace('topAdmission.','').replace('taskTransfer.','').replace('keyguard.','').replace('resumes.','').replace('carriers.','').replace('retainedSurfaces.','').replace('chrome.','').replace('mainTaskActions.','').replace('taskStates.','').replace('slotLifecycle.','').replace('sideInteraction.','').replace('displays.','').replace('wallpapers.','').replace('memory.','').replace('debug.','').replace('scales.','').replace('cells.','').replace('exits.','').replace('mainLaunch.','').replace('transitionWindow.','').replace('contentDrag.','').replace('sideCloses.','').replace('WorkspaceHost.', '')
start = host.index('    void appendUnlockGeometry(')
end = host.index('{', start) + 1
depth = 1
while depth:
    depth += (host[end] == '{') - (host[end] == '}'); end += 1
files = {
'android/os/Parcel.java': r'''package android.os;
public class Parcel {
 public Object data;public boolean recycled;public static int live;
 public static Parcel obtain(){live++;return new Parcel();}
 public void setDataPosition(int p){if(recycled)throw new IllegalStateException();}
 public void recycle(){if(recycled)throw new AssertionError("double recycle");recycled=true;live--;}
}''',
'android/view/SurfaceControl.java': r'''package android.view;
import android.os.Parcel;import java.util.*;
public class SurfaceControl {
 public static class Transaction implements AutoCloseable {
  public static class Creator {
   @SuppressWarnings("unchecked") public Transaction createFromParcel(Parcel p){Transaction t=new Transaction();t.ops.putAll((Map<String,Float>)p.data);return t;}
  }
  public static final Creator CREATOR=new Creator();public static int live;
  public Map<String,Float> ops=new HashMap<>();public boolean closed,failWrite;
  public Transaction(){live++;}public Transaction put(String key,float value){ops.put(key,value);return this;}
  public void writeToParcel(Parcel p,int flags){if(failWrite)throw new IllegalStateException("capture failed");p.data=new HashMap<>(ops);if(flags!=0)ops.clear();}
  public Transaction merge(Transaction other){if(closed||other.closed)throw new AssertionError("closed transaction");ops.putAll(other.ops);other.ops.clear();return this;}
  public void close(){if(closed)throw new AssertionError("double close");closed=true;live--;}
 }
}''',
'io/github/zerohomers0/onesteplite/UnlockGeometryRegression.java': r'''package io.github.zerohomers0.onesteplite;
import android.os.Parcel;import android.view.SurfaceControl.Transaction;import java.util.*;
public class UnlockGeometryRegression {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static Transaction geometry(){return new Transaction().put("task.scale",.75f).put("task.y",620f).put("activity.scale",1f);}
 public static void main(String[] args){
  UnlockGeometryReplay replay=new UnlockGeometryReplay();
  try(Transaction ack=geometry()){
   replay.capture(ack,7,.75f,0,620);check(ack.ops.size()==3,"snapshot consumed the acknowledgement before Host merge");
   try(Transaction host=new Transaction()){host.merge(ack);check(ack.ops.isEmpty(),"fixture merge must consume its source");}
   for(int i=0;i<3;i++)try(Transaction finish=new Transaction().put("task.scale",1f).put("task.y",0f).put("statusbar.scale",.75f)){
    check(replay.append(finish,7,.75f,0,620),"fresh decode required for each merged native start/finish");
    check(finish.ops.get("task.scale")==.75f&&finish.ops.get("task.y")==620f,"late fullscreen cleanup won over committed geometry");
    check(finish.ops.get("statusbar.scale")==.75f,"correct independent status bar must remain unchanged while the task is repaired");
   }
   for(int i=0;i<4;i++)try(Transaction finish=new Transaction().put("task.scale",1f)){
    check(!replay.append(finish,i==0?8:7,i==1?.8f:.75f,i==2?1:0,i==3?621:620),"stale scene or geometry must not replay");
    check(finish.ops.get("task.scale")==1f,"stale replay touched another scene");
   }
  }
  check(Transaction.live==0&&Parcel.live==1,"snapshot must own only one Parcel, no native transactions");
  replay.clear();replay.clear();check(Parcel.live==0,"clear must release snapshot exactly once");
  try(Transaction finish=new Transaction()){check(!replay.append(finish,7,.75f,0,620),"exit/relock revived stale geometry");}
  try(Transaction ack=geometry()){replay.capture(ack,7,.75f,0,620);ack.failWrite=true;
   try{replay.capture(ack,8,.75f,0,620);throw new AssertionError("capture failure hidden");}catch(IllegalStateException expected){}
   check(Parcel.live==0,"failed replacement leaked old/new snapshot");
  }
  WorkspaceHost h=new WorkspaceHost();try(Transaction ack=geometry()){h.unlockGeometryReplay.capture(ack,7,.75f,0,620);}
  for(int gate=0;gate<13;gate++){
   h.reset();if(gate==1)h.screenOn=false;if(gate==2)h.keyguardLocked=true;if(gate==3)h.keyguardWindowHidden=true;
   if(gate==4)h.zoomed=false;if(gate==5)h.workspaceEntering=true;if(gate==6)h.workspaceAnimator=new Object();
   if(gate==7)h.mainSideAnimator=new Object();if(gate==8)h.scene=0;if(gate==9)h.root=null;
   if(gate==10)h.layout=null;if(gate==11)h.sceneEpoch=8;
   try(Transaction finish=new Transaction().put("task.scale",1f)){
    h.apply(finish,gate==12?List.of(9L):List.of(7L));
    check(finish.ops.get("task.scale")== (gate==0?.75f:1f),"Host admission gate "+gate+" allowed a stale unlock replay");
   }
  }
  h.reset();h.keyguardWindowHidden=true;h.zoomed=false;h.retainedForLock=true;h.unlockHandoff=new Object();
  try(Transaction finish=new Transaction().put("task.scale",1f)){
   h.apply(finish,List.of(7L));
   check(finish.ops.get("task.scale")==.75f,"native unlock can finish after geometry confirmation but before Host reveal");
  }
  h.unlockGeometryReplay.clear();check(Parcel.live==0&&Transaction.live==0,"native replay resources leaked");
  System.out.println("Authenticated native unlock geometry: "+checks+" checks passed (transaction doubles; no device rendering)");
 }
}''',
'io/github/zerohomers0/onesteplite/WorkspaceHost.java': r'''package io.github.zerohomers0.onesteplite;
import java.util.*;
class WorkspaceHost {
 static final int ACTIVE=2,RESUMING=1;Object root,workspaceAnimator,mainSideAnimator,unlockHandoff;
 boolean screenOn,keyguardLocked,keyguardWindowHidden,zoomed,workspaceEntering,retainedForLock;long sceneEpoch;int scene;
 static class Geometry {float mainScale=.75f,mainOffsetX=0,mainOffsetY=620;}Geometry layout;
 final UnlockGeometryReplay unlockGeometryReplay=new UnlockGeometryReplay();WorkspaceHost(){reset();}
 void reset(){root=new Object();layout=new Geometry();workspaceAnimator=mainSideAnimator=null;screenOn=zoomed=true;
 keyguardLocked=keyguardWindowHidden=workspaceEntering=false;sceneEpoch=7;scene=ACTIVE;}
 void log(String s){}void apply(Object t,List<Long> epochs){appendUnlockGeometry(t,epochs);}
 ACTUAL
}'''.replace('ACTUAL', host[start:end])
}
paths=[]
for name, body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'UnlockGeometryReplay.java'))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.UnlockGeometryRegression'],check=True)
