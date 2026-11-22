"""Replay the production top admission callbacks and readiness gate across interrupted scenes."""
from pathlib import Path
import os,subprocess,sys
import sys as _sys, pathlib as _pathlib; _sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1])); from source_paths import package_dir
root=Path(__file__).resolve().parents[2]
src=package_dir(root)
host=(src/'WorkspaceHost.java').read_text(encoding='utf-8-sig')
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
def member(signature,occurrence=0):
    start=-1
    for _ in range(occurrence+1):start=host.index(signature,start+1)
    end=host.index('{',start)+1;depth=1
    while depth:
        depth+=(host[end]=='{')-(host[end]=='}');end+=1
    return host[start:end]
body='''package io.github.zerohomers0.onesteplite;
import java.util.function.Consumer;import android.content.*;import android.os.*;import android.graphics.Rect;import de.robv.android.xposed.XposedHelpers;
public final class TopAdmissionRegression {
 static final int ACTIVE=1,SLOT_TOP=4;int scene=ACTIVE;long sceneEpoch=1;
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class Slot {int index;boolean occupied;Slot(int i){index=i;}}
 static class Task {int id=42;Object token=new Binder();}
 static class TopCandidate {Task task=new Task();TopTaskState.Key key=new TopTaskState.Key(42,0,"token");Slot source;boolean landscape=true;}
 static class View {static final int VISIBLE=0,INVISIBLE=4;}
 static class Pane {boolean occupied;void occupied(boolean v){occupied=v;}void message(String s){}void setVisibility(int v){}}
 static class Root {void requestLayout(){}}
 Root root=new Root();Pane topTaskPane=new Pane();Object promotion,cellMotion;
 boolean screenOn=true,keyguardLocked,transferBusy,workspaceEntering,suspendRequested,topFromMain,topPortrait;
 boolean fail=true,defer;int adopted,restored,finished,released;Runnable pending;
 Slot[]slots={new Slot(0),new Slot(1),new Slot(2),new Slot(3),new Slot(4)};Slot topOriginalSlot;
 Context ctx=new Context();Handler main=new Handler();IBinder desktopSceneOwner=new Binder();Rect logicalBounds(Slot s){return new Rect(0,0,2964,1707);}
 TopTaskState topTaskState=new TopTaskState();
 void adoptExisting(Slot slot,Task task,boolean fromMain,Runnable success,Consumer<String> failure){
  adopted++;if(defer)pending=success;else if(fail)failure.accept("not visible");else success.run();
 }
 void adoptExisting(Slot slot,Task task,Runnable success,Consumer<String> failure){restored++;success.run();}
 void releaseTopTask(boolean front,boolean dismiss,Runnable after){released++;}
 void updateTopTaskGeometry(){}void applyFullscreenBounds(Slot slot){}void applySlotScale(Slot slot){}void updateCellSurfaceVisibility(Slot slot){}
 void publishDesktopSideTasks(){}void preferMainAfterTopChange(){}void revealTopTaskTransition(){}void cancelTopTaskTransition(){}
 void restoreTabletCoveredMain(TopTaskState.Key key){}
 void finishPendingSuspend(){finished++;}static void log(String text){}
'''+member('    private boolean topReady(')+member('    private void adoptTopTask(')+member('    private void prepareTopViewport(')+member('    private void adoptTopTaskPrepared(')+member('    private void topAdoptionFailed(')+'''
 static TopCandidate candidate(TopAdmissionRegression h){TopCandidate c=new TopCandidate();c.source=h.slots[1];return c;}
 static TopTaskState.Ticket ticket(TopAdmissionRegression h,TopCandidate c){return h.topTaskState.request(c.key,1,true,true);}
 public static void main(String[]args){
  var h=new TopAdmissionRegression();var c=candidate(h);h.adoptTopTask(c,ticket(h,c),false);
  check(h.restored==1&&h.finished==1&&!h.topTaskState.busy(),"awake failure restores original empty side once");
  for(int state=0;state<7;state++){
   h=new TopAdmissionRegression();c=candidate(h);var t=ticket(h,c);
   switch(state){case 0:h.screenOn=false;break;case 1:h.keyguardLocked=true;break;case 2:h.suspendRequested=true;break;
    case 3:h.scene=3;break;case 4:h.transferBusy=true;break;case 5:h.root=null;break;case 6:c.source.occupied=true;break;}
   h.adoptTopTask(c,t,false);check(h.restored==0&&h.finished==1,"unsafe restore retry for state "+state);
  }
  h=new TopAdmissionRegression();c=candidate(h);var stale=ticket(h,c);h.topTaskState.sceneChanged();h.adoptTopTask(c,stale,false);
  check(h.adopted==0,"stale scene starts no transfer");
  h=new TopAdmissionRegression();c=candidate(h);h.defer=true;h.adoptTopTask(c,ticket(h,c),false);h.sceneEpoch++;h.topTaskState.sceneChanged();h.pending.run();
  check(h.released==1&&h.topTaskState.owner()==null&&!h.topTaskState.busy(),"late completion returns task instead of publishing owner");
  h=new TopAdmissionRegression();c=candidate(h);h.fail=false;h.adoptTopTask(c,ticket(h,c),false);
  check(h.topTaskState.owner().equals(c.key)&&h.topOriginalSlot==c.source&&h.topTaskState.owner()!=null&&h.finished==1,"confirmed same task retains origin");
  h=new TopAdmissionRegression();c=candidate(h);h.ctx.auto=false;h.fail=false;h.adoptTopTask(c,ticket(h,c),false);
  check(h.adopted==0&&h.topTaskState.busy(),"WCT started before native viewport acknowledgement");h.ctx.ack(-1);
  check(h.adopted==1&&h.topTaskState.owner()!=null&&h.main.queued.isEmpty(),"acknowledged viewport did not start exactly once");h.ctx.ack(-1);check(h.adopted==1,"duplicate viewport ack starts another WCT");
  h=new TopAdmissionRegression();c=candidate(h);h.ctx.auto=false;h.adoptTopTask(c,ticket(h,c),false);h.ctx.ack(0);
  check(h.adopted==0&&h.restored==1&&!h.topTaskState.busy(),"rejected viewport changed canonical task");
  h=new TopAdmissionRegression();c=candidate(h);h.ctx.auto=false;h.adoptTopTask(c,ticket(h,c),false);h.main.fire();h.ctx.ack(-1);
  check(h.adopted==0&&h.restored==1&&!h.topTaskState.busy(),"late ack after timeout changes native task");
  h=new TopAdmissionRegression();c=candidate(h);h.ctx.auto=false;h.adoptTopTask(c,ticket(h,c),false);h.sceneEpoch++;h.topTaskState.sceneChanged();h.ctx.ack(-1);
  check(h.adopted==0,"old viewport ack crosses scene epoch");
  System.out.println("Production top admission/lock/late-callback checks="+checks+" passed");
 }
}'''
files={
'android/Manifest.java':'package android;public class Manifest{public static class permission{public static final String WRITE_SECURE_SETTINGS="secure";}}',
'android/os/IBinder.java':'package android.os;public interface IBinder{}',
'android/os/Binder.java':'package android.os;public class Binder implements IBinder{}',
'android/os/Bundle.java':'package android.os;public class Bundle{public void putBinder(String k,IBinder b){}public void putInt(String k,int v){}public void putParcelable(String k,Object o){}}',
'android/os/Handler.java':'package android.os;import java.util.*;public class Handler{public List<Runnable>queued=new ArrayList<>();public void postDelayed(Runnable r,long d){queued.add(r);}public void removeCallbacks(Runnable r){queued.remove(r);}public void fire(){for(var r:new ArrayList<>(queued))r.run();queued.clear();}}',
'android/graphics/Rect.java':'package android.graphics;public class Rect{public Rect(int l,int t,int r,int b){}}',
'android/content/Intent.java':'package android.content;public class Intent{public Intent(String a){}public Intent setPackage(String p){return this;}public Intent putExtra(String k,Object o){return this;}}',
'android/content/BroadcastReceiver.java':'package android.content;public abstract class BroadcastReceiver{public int result;public int getResultCode(){return result;}public abstract void onReceive(Context c,Intent i);}',
'android/content/Context.java':'package android.content;import android.os.*;public class Context{public boolean auto=true;BroadcastReceiver callback;public void sendOrderedBroadcast(Intent i,String p,BroadcastReceiver r,Handler h,int code,String data,Bundle extras){callback=r;if(auto)ack(-1);}public void ack(int c){callback.result=c;callback.onReceive(this,null);}}',
'de/robv/android/xposed/XposedHelpers.java':'package de.robv.android.xposed;public class XposedHelpers{public static Object callMethod(Object o,String n,Object...a){return o;}}',
'io/github/zerohomers0/onesteplite/DesktopLaunchBridge.java':'package io.github.zerohomers0.onesteplite;public class DesktopLaunchBridge{static final String PREPARE_TOP="prepare";}',
}
for name,text in files.items():
 q=out/name;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(text,encoding='utf8')
p=out/'io/github/zerohomers0/onesteplite/TopAdmissionRegression.java';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8')
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(p),str(src/'TopTaskState.java'),*[str(out/n) for n in files]],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.TopAdmissionRegression'],check=True)

# Replay the actual face/suspend methods. A visible control face must paint after
# the SurfaceView hole, including while the old task is being returned by Shell.
layer='''package io.github.zerohomers0.onesteplite;
public final class TopLayerRegression {
 static final int ACTIVE=1,SLOT_TOP=4;int scene=ACTIVE;boolean screenOn=true,keyguardLocked,topFace=true,topSelectionPending;
 long topFaceGeneration;static int checks;int returned,selected,claimed,hints;static String order="";
 static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class View {static final int VISIBLE=0,INVISIBLE=4;int visibility=4,raises;
  void setVisibility(int value){visibility=value;}void bringToFront(){raises++;order+="raise;";}}
 static class Bar extends View {int leaves;void leaveLandscapePage(){leaves++;}
  void setTabletToolsExpanded(boolean expanded,boolean animate){setVisibility(expanded?VISIBLE:INVISIBLE);}}
 static class Pane extends View {boolean occupied;void occupied(boolean v){occupied=v;}}
 static class Root {int layouts;void requestLayout(){layouts++;}}
 static class Slot {boolean occupied,parked;int taskId=42;long taskEpoch=1;Object taskToken="token";}
 static class TopCandidate {boolean landscape;}
 static class Layout {boolean tabletLandscape;}
 Layout layout=new Layout();boolean tabletToolsOpen;void updateTopTaskGeometry(){}
 Root root=new Root();Bar topBar=new Bar();Pane topTaskPane=new Pane();
 Slot[]slots={new Slot(),new Slot(),new Slot(),new Slot(),new Slot()};
 TopTaskState topTaskState=new TopTaskState();TopCandidate current;
 boolean topFromMain=true;Object topOriginalSlot,tabletCoveredMain,tabletReturnedTop;Boolean mainOccupied=true;int retained,liveTransitions;boolean returnedLive;Runnable liveTransfer;
 void cancelTopTaskTransition(){}
 Boolean mainTaskOccupiedBeside(int id){return mainOccupied;}
 void retainTopTaskSnapshot(){retained++;}
 boolean startLiveMainRevealTransition(Runnable after){liveTransitions++;liveTransfer=after;return true;}
 void releaseTopTask(boolean front,boolean dismissed,Runnable after){returned++;order+="return;";returnedLive=false;}
 void releaseTopTask(boolean front,boolean dismissed,Runnable after,boolean live){returned++;order+="return;";returnedLive=live;}
 void releaseRetainedTopSnapshot(){}static void log(String text){}
 void claimTopTask(TopCandidate c,boolean manual){claimed++;}
 void updateTopBarState(){}
 TopCandidate currentTopCandidate(){return current;}
 void showTopCandidates(){selected++;}void updateHint(Slot slot){hints++;}
 void updateTopExitWindow(){}void releaseTopExitWindow(){}
'''+member('    private void settleTopFace(')+member('    private void resetTopForSuspend(')+'''
 public static void main(String[]args){
  order="";
  var h=new TopLayerRegression();h.slots[4].occupied=true;h.settleTopFace(false);
  check(h.topBar.visibility==0&&h.topTaskPane.visibility==4&&!h.topFace,"control face visibility");
  check(h.topBar.raises==1&&order.equals("raise;"),"controls paint above hole without returning the occupied upper app");
  check(h.returned==0&&h.retained==0&&h.liveTransitions==0,"occupied main keeps the landscape upper task bound in its TaskView");
  check(h.hints==1&&h.root.layouts==1,"retained upper task still updates its geometry");
  h=new TopLayerRegression();h.slots[4].occupied=true;h.mainOccupied=null;h.settleTopFace(false);
  check(h.returned==0&&h.retained==0&&h.liveTransitions==0,"unknown main occupancy conservatively retains the upper task");
  h=new TopLayerRegression();h.slots[4].occupied=true;h.mainOccupied=false;h.settleTopFace(false);
  check(h.liveTransitions==1&&h.returned==0,"empty main starts the live task return bridge");h.liveTransfer.run();
  check(h.returned==1&&h.returnedLive,"live bridge requests native return with the real-surface reveal flag");
  h=new TopLayerRegression();h.slots[4].occupied=true;h.mainOccupied=false;h.topFromMain=false;h.topOriginalSlot=new Object();h.settleTopFace(false);
  check(h.returned==1&&h.liveTransitions==0&&!h.returnedLive,"side-origin upper task returns without a main-center animation");
  h=new TopLayerRegression();h.topFace=false;h.topSelectionPending=true;h.current=new TopCandidate();h.current.landscape=true;h.settleTopFace(true);
  check(h.topFace&&h.selected==0&&h.claimed==0&&h.topBar.raises==0&&h.topBar.visibility==0&&h.topTaskPane.visibility==4,"top admission never replaces the original header with a cell");
  h=new TopLayerRegression();h.settleTopFace(true);check(h.selected==0,"empty top keeps the original header instead of opening a chooser");
  h=new TopLayerRegression();h.keyguardLocked=true;h.settleTopFace(false);check(h.topFace&&h.topBar.raises==0&&h.returned==0,"stale locked completion ignored");
  h=new TopLayerRegression();h.topSelectionPending=true;h.resetTopForSuspend();
  check(!h.topFace&&h.topBar.raises==1,"suspend restores control paint order");
  h=new TopLayerRegression();h.layout.tabletLandscape=true;h.tabletToolsOpen=true;h.resetTopForSuspend();
  check(!h.tabletToolsOpen&&h.topBar.visibility==4&&h.topBar.raises==0,"tablet suspend collapses instead of exposing the old control face");
  h.topBar.visibility=0;h.settleTopFace(false);
  check(h.topBar.visibility==0,"late tablet roll completion cannot interrupt a closing fade");
  System.out.println("Production top face/layer/suspend checks="+checks+" passed");
 }
}'''
p=out/'io/github/zerohomers0/onesteplite/TopLayerRegression.java';p.write_text(layer,encoding='utf-8')
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-cp',str(out),'-d',str(out),str(p),str(src/'TopTaskReturnPolicy.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.TopLayerRegression'],check=True)

direction='''package io.github.zerohomers0.onesteplite;
import java.util.*;
public class TopDirectionRegression {
 Object root=new Object();Map<TopTaskState.Key,Boolean> topDirections=new HashMap<>();
 TopTaskState topTaskState=new TopTaskState();boolean topAuto=true,topFace=true;
 static class TopCandidate {TopTaskState.Key key;boolean landscape;}TopCandidate current;int opened,claimed;
 static final int ACTIVE=1,SLOT_TOP=4;int scene=ACTIVE;boolean screenOn=true,keyguardLocked,transferBusy;
 Object promotion,workspaceAnimator;float workspaceAlpha=1f;
 static class VideoPane {boolean embedded(){return false;}void refresh(){}}VideoPane videoPane;
 static class TopPresentationController {void refreshVideo(){}}TopPresentationController topPresentation;
 static class Layout {boolean tabletLandscape;}Layout layout=new Layout();
 static class Slot {boolean occupied;}Slot[] slots={null,null,null,null,new Slot()};
 static class Bar {boolean enabled;void setAlpha(float a){}void setSendMainToTopEnabled(boolean b){enabled=b;}}Bar topBar=new Bar();
 void updateTopExitWindow(){}static void log(String s){}
 boolean supportsTaskUser(int user){return user==0||user==999;}boolean topReady(){return true;}
 TopCandidate currentTopCandidate(){if(current!=null)current.landscape=Boolean.TRUE.equals(topDirections.get(current.key));return current;}
 void claimTopTask(TopCandidate c,boolean manual){claimed++;}
'''+member('    private void topTaskDirection(')+member('    private void updateTopBarState(')+member('    private boolean mainCanFillTop(')+'''
 public static void main(String[]args){
  var h=new TopDirectionRegression();var a=new TopTaskState.Key(7,0,"a");h.current=new TopCandidate();h.current.key=a;
  for(int i=0;i<4;i++){h.topFace=i%2==0;h.topTaskDirection(a,true);h.topTaskDirection(a,false);}
  if(h.topBar.enabled)throw new AssertionError("portrait main action stays enabled");
  h.topTaskDirection(a,true);if(!h.topBar.enabled)throw new AssertionError("landscape main action was not refreshed");
  h.topTaskDirection(a,false);if(h.topBar.enabled)throw new AssertionError("return to portrait did not disable action");
  if(h.opened!=0||h.claimed!=0||h.topTaskState.owner()!=null||h.topTaskState.busy())throw new AssertionError("direction change admitted/opened upper task");
  var t=h.topTaskState.request(a,1,true,true);h.topTaskState.commit(t,1);h.topTaskDirection(a,false);
  if(!a.equals(h.topTaskState.owner())||h.topDirections.get(a)!=false)throw new AssertionError("portrait navigation lost explicit owner");
  var foreign=new TopTaskState.Key(8,10,"foreign");h.topTaskDirection(foreign,true);
  if(h.topDirections.containsKey(foreign))throw new AssertionError("unsupported user recorded");
  h.root=null;h.topTaskDirection(new TopTaskState.Key(9,0,"late"),true);
  if(h.topDirections.size()!=1)throw new AssertionError("detached Host consumed direction callback");
  System.out.println("Production upper manual-only direction checks=7 passed");
 }
}'''
p=out/'io/github/zerohomers0/onesteplite/TopDirectionRegression.java';p.write_text(direction,encoding='utf-8')
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-cp',str(out),'-d',str(out),str(p)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.TopDirectionRegression'],check=True)

returning='''package io.github.zerohomers0.onesteplite;
public class TopReturnBridgeRegression {
 static final int SLOT_TOP=4;static int checks;
 static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static class Slot {boolean occupied=true,promoting;int taskId=7;long taskEpoch=1;Object taskToken="upper";}
 Slot[] slots={null,null,null,null,new Slot()};boolean suspendRequested,ready=true,accept=true,topFace=true;
 static class TopTaskTransition {}
 static class Layout {boolean tabletLandscape;}
 final Layout layout=new Layout();
 TopTaskTransition topTransition;
 int nativeCalls,animations,cancels,fades;boolean nativeFront,nativeLive,prepared;Runnable pending,settled;
 boolean topReady(){return ready;}
 void prepareTopFace(){prepared=true;}
 boolean startLiveMainRevealTransition(Runnable r){
  animations++;pending=r;
  if(accept)topTransition=new TopTaskTransition();
  return accept;
 }
 void releaseTopTaskNow(boolean front,boolean dismissed,Runnable after,boolean live){nativeCalls++;nativeFront=front;nativeLive=live;}
 void fadeTopTaskOut(Runnable after){fades++;settled=after;}
 void cancelTopTaskTransition(){cancels++;topTransition=null;}
'''+member('    private void releaseTopTask(',0)+member('    private void releaseTopTask(',1)+'''
 public static void main(String[]args){
  var h=new TopReturnBridgeRegression();h.releaseTopTask(false,true,null,true);
  check(h.animations==0&&h.nativeCalls==1&&h.nativeLive,"roll live reveal bypasses a second upper collapse");
  h=new TopReturnBridgeRegression();h.releaseTopTask(false,true,null,false);
  check(h.animations==0&&h.nativeCalls==1&&!h.nativeLive,"non-live roll return hands back directly");
  h=new TopReturnBridgeRegression();h.releaseTopTask(true,true,null);
  check(h.animations==1&&h.nativeCalls==0,"explicit return waits for transparent live handoff");h.pending.run();
  check(h.nativeCalls==0&&h.settled!=null,"native resize must wait for the control face to be visible");
  h.releaseTopTask(true,true,null);
  check(h.nativeCalls==0&&h.animations==1,"a second tap cannot bypass the live roll");
  h.settled.run();
  check(h.nativeCalls==1&&h.nativeFront&&h.nativeLive,"explicit native handback always uses the actual task surface");
  h=new TopReturnBridgeRegression();h.releaseTopTask(true,true,null);h.slots[4].taskEpoch++;h.pending.run();
  check(h.nativeCalls==0&&h.cancels==1&&h.settled==null,"late collapse cannot turn a changed binding away");
  h=new TopReturnBridgeRegression();h.releaseTopTask(true,true,null);h.pending.run();h.slots[4].taskEpoch++;h.settled.run();
  check(h.nativeCalls==0&&h.cancels==1,"a task replaced mid-roll cannot be handed back");
  h=new TopReturnBridgeRegression();h.releaseTopTask(true,true,null);h.pending.run();h.cancelTopTaskTransition();h.settled.run();
  check(h.nativeCalls==0,"a roll completion cannot revive a cancelled bridge");
  h=new TopReturnBridgeRegression();h.layout.tabletLandscape=true;h.releaseTopTask(true,true,null);h.pending.run();
  check(h.nativeCalls==1&&h.settled==null,"the tablet fade still transfers without a phone roll");
  h=new TopReturnBridgeRegression();h.topFace=false;h.releaseTopTask(true,true,null);h.pending.run();
  check(h.nativeCalls==1&&h.settled==null,"the hidden phone face is already past the roll");
  h=new TopReturnBridgeRegression();h.suspendRequested=true;h.releaseTopTask(true,true,null);
  check(h.animations==0&&h.nativeCalls==1,"suspend/recovery bypasses decorative delay");
  System.out.println("Production upper return animation scope checks="+checks+" passed");
 }
}'''
p=out/'io/github/zerohomers0/onesteplite/TopReturnBridgeRegression.java';p.write_text(returning,encoding='utf-8')
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-cp',str(out),'-d',str(out),str(p)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.TopReturnBridgeRegression'],check=True)

opened='''package io.github.zerohomers0.onesteplite;
import java.util.*;
public class OpenedAppRegression {
 static final int ACTIVE=1,SLOT_TOP=4;int scene=ACTIVE;boolean screenOn=true,keyguardLocked,workspaceEntering,transferBusy,fail;
 Object promotion;LiveTask current;static class LiveTask {String pkg;LiveTask(String p){pkg=p;}}
 static class Slot {int index;String pkg;boolean occupied,closing,parked;Slot(int i){index=i;}}
 Slot[] slots={new Slot(0),new Slot(1),new Slot(2),new Slot(3),new Slot(4)};
 LiveTask findMainTask(){if(fail)throw new IllegalStateException();return current;}
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
''' + member('    private java.util.Set<String> openedQuickAppPackages(') + '''
 public static void main(String[]args){
  var h=new OpenedAppRegression();check(h.openedQuickAppPackages().isEmpty(),"desktop and empty cells have no markers");
  h.current=new LiveTask("main");h.slots[1].occupied=true;h.slots[1].pkg="side";
  h.slots[4].occupied=true;h.slots[4].pkg="top";h.slots[2].pkg="background";
  check(h.openedQuickAppPackages().equals(Set.of("main","side")),"only main and occupied sides count, not TOP or background metadata");
  h.slots[2].occupied=true;h.slots[2].pkg="side";
  check(h.openedQuickAppPackages().size()==2,"multiple tasks of one app share one marker");
  h.slots[1].closing=true;h.slots[2].parked=true;
  check(h.openedQuickAppPackages().equals(Set.of("main")),"closing and parked apps lose side markers");
  h.current=null;check(h.openedQuickAppPackages().isEmpty(),"return to desktop removes main marker");
  for(int gate=0;gate<7;gate++){
   h=new OpenedAppRegression();if(gate==0)h.scene=0;if(gate==1)h.screenOn=false;
   if(gate==2)h.keyguardLocked=true;if(gate==3)h.workspaceEntering=true;
   if(gate==4)h.promotion=new Object();if(gate==5)h.transferBusy=true;if(gate==6)h.fail=true;
   check(h.openedQuickAppPackages()==null,"unstable or private scene preserves previous snapshot "+gate);
  }
  System.out.println("Production opened app marker checks="+checks+" passed");
 }
}'''
p=out/'io/github/zerohomers0/onesteplite/OpenedAppRegression.java';p.write_text(opened,encoding='utf-8')
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-cp',str(out),'-d',str(out),str(p)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.OpenedAppRegression'],check=True)
