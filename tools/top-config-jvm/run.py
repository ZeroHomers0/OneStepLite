"""Replay real top bounds/return/focus methods; no claim of Android rendering."""
from pathlib import Path
import os,subprocess,sys,re
import sys as _sys, pathlib as _pathlib; _sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1])); from source_paths import package_dir
root=Path(__file__).resolve().parents[2]
src=package_dir(root)
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=(src/'WorkspaceHost.java').read_text(encoding='utf-8-sig')
scale=(src/'WorkspaceScaleController.java').read_text(encoding='utf-8-sig')
transfer=(src/'WorkspaceTaskTransferController.java').read_text(encoding='utf-8').replace('host.', '').replace('slotRegistry.','').replace('logicalBounds(slot, topPortrait)','logicalBounds(slot)').replace('sideRows.','').replace('windowPresentation.','').replace('liveGeometry.','').replace('liveHandoff.','').replace('promotionController.','').replace('topPresentationHost.','').replace('topAdmission.','').replace('taskTransfer.','').replace('keyguard.','').replace('resumes.','').replace('carriers.','').replace('retainedSurfaces.','').replace('chrome.','').replace('mainTaskActions.','').replace('taskStates.','').replace('slotLifecycle.','').replace('sideInteraction.','').replace('displays.','').replace('wallpapers.','').replace('memory.','').replace('debug.','').replace('scales.','').replace('cells.','').replace('exits.','').replace('mainLaunch.','').replace('transitionWindow.','').replace('contentDrag.','').replace('sideCloses.','').replace('WorkspaceHost.', '')
native_hooks=(src/'WorkspaceNativeHooks.java').read_text(encoding='utf-8')
presentation=(src/'WorkspaceTopPresentationController.java').read_text(encoding='utf-8')
def member(signature,text=source):
 start=text.index(signature);end=text.index('{',start)+1;depth=1
 while depth:
  depth+=(text[end]=='{')-(text[end]=='}');end+=1
 return text[start:end]
top_configuration=member('    static void applyTopConfiguration(',native_hooks)
top_configuration=top_configuration.replace('static void applyTopConfiguration(WorkspaceHost h, Object wct, Object token, Slot slot)','void applyTopConfiguration(Object wct, Object token, Slot slot)')
top_configuration=re.sub(r'\bh\.','',top_configuration).replace('WorkspaceHost.SLOT_MAIN','SLOT_MAIN').replace('WorkspaceHost.SLOT_TOP','SLOT_TOP').replace('windowLayout.topGeometry()','topGeometry()').replace('slotRegistry.','').replace('logicalBounds(slot, topPortrait)','logicalBounds(slot)')
prefer_method=member('    void preferMainAfterTopChange(',presentation).replace('host.','').replace('WorkspaceHost.','').replace('slotRegistry.','').replace('taskStates.','')
body='''package io.github.zerohomers0.onesteplite;
import java.util.*;import android.graphics.Rect;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
public class TopConfigRegression {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static final int SLOT_MAIN=0,SLOT_TOP=4,ACTIVE=1,SUSPENDED=2;int scene=ACTIVE;long sceneEpoch=1;
 boolean suspendRequested,keyguardLocked,screenOn=true,defer,transferBusy,currentTicket=true,retainedOnExit;
 Object transitions=new Object(),shell=new Object();
 List<Runnable>idleQueue=new ArrayList<>();
 Consumer<Object> drawReady;
 int returned;
 static class Handler {void post(Runnable r){r.run();}}
 Handler main=new Handler();
 List<Runnable>queue=new ArrayList<>();
 static class Slot {int index=4,taskId=42;int[] rect;Object taskToken="upper",controller=new Object();String pkg="test.app";boolean promoting,frozen;}
 static class Promotion {Slot slot=new Slot();Object token="upper";int taskId=42;boolean background=true,liveMainReveal,handoffComplete,restoreMainOpacityAfterHandoff;List<Object>otherSideTokens=List.of("side");}
 static class Info {boolean isFocused,isVisible=true;int mode,id;Object token;Info(int i,int m,Object t,boolean focus){id=i;mode=m;token=t;isFocused=focus;}}
 static class LiveTask {int id;Object token;LiveTask(int i,Object t){id=i;token=t;}LiveTask(int i,Object t,String pkg){this(i,t);}}
 List<Info>infos=new ArrayList<>();LiveTask current,home=new LiveTask(1,"home");
 static class Service {int calls,last=-1;void setFocusedTask(int id){calls++;last=id;}}
 Service service=new Service();Object applied;
 static TopConfigRegression active;
 static class SyncTransaction {Executor executor;Runnable callback;}
 static class ShellCompat {
  static Object transaction(Object organizer,Object wct,String name,Object token,Object value){return XposedHelpers.callMethod(wct,name,token,value);}
  static void call(Object shell,String name,Runnable action){
   check(shell==active.shell&&name.equals("runOnIdle"),"native return waits on the real Shell queue");
   active.idleQueue.add(action);
  }
  static void applySynchronized(Object views,Object controller,Object wct,Consumer<Object> ready){
   active.applied=wct;active.drawReady=ready;
  }
 }
 static class XposedHelpers {
  static Object callStaticMethod(Class<?>c,String name,Object...args){return active.service;}
  static Object getObjectField(Object o,String name){
   if(name.equals("mTaskOrganizer"))return active.service;
   if(name.equals("mCaptionInsetsOwner"))return null;
   if(name.equals("mTransitions"))return active.shell;
   if(name.equals("mTaskToken"))return "upper";
   return ((Info)o).token;
  }
  static boolean getBooleanField(Object o,String n){Info i=(Info)o;return n.equals("isFocused")?i.isFocused:i.isVisible;}
  static Object callMethod(Object o,String n,Object...a){
   if(o instanceof android.window.WindowContainerTransaction){((android.window.WindowContainerTransaction)o).calls.add(n+":"+a[0]+":"+(a.length>1?a[1]:"")+(a.length>2?":"+a[2]:""));return o;}
   if(o instanceof SyncTransaction t && n.equals("addTransactionCommittedListener")){
    t.executor=(Executor)a[0];t.callback=((android.view.SurfaceControl.TransactionCommittedListener)a[1])::onTransactionCommitted;return null;
   }
   if(o instanceof Service){((Service)o).setFocusedTask((Integer)a[0]);return null;}throw new AssertionError(n);
  }
 }
 boolean executeOnShell(Runnable r){if(defer)queue.add(r);else r.run();return true;}
 boolean canRender(Slot s){return true;}boolean holdsLiveFlipZoom(){return false;}boolean mayWriteSideSurface(Slot s){return true;}
 boolean controllerOwns(Slot s,int id,Object token){return true;}Object getSurfaceField(Object c,String n){return new Object();}
 TopTaskGeometry topGeometry(){return new TopTaskGeometry(1080,2460,622,true,2.75f);}
  int screenW=1080,screenH=2460,displayDensityDpi=440;
 boolean topPortrait;
  Rect logicalBounds(Slot s){TopTaskGeometry g=topGeometry();if(s.index==4)return new Rect(0,0,topPortrait?534:g.logicalWidth,g.logicalHeight);if(s.rect!=null)return new Rect(0,0,s.rect[2]-s.rect[0],s.rect[3]-s.rect[1]);return new Rect(0,0,1080,2460);}
 boolean applyWct(String why,Object wct){applied=wct;return true;}static void log(String s){}
 Iterable<Info>rootInfos(){return infos;}int windowModeOf(Object o){return ((Info)o).mode;}
 boolean isSideToken(Object token){return "upper".equals(token)||"side".equals(token);}
 LiveTask findMainTask(){return current;}LiveTask existingHomeTask(){return home;}
 Object rootInfo(LiveTask task){return infos.stream().filter(i->i.id==task.id&&i.token.equals(task.token)).findFirst().orElse(null);}
  boolean ordinaryRoot(Object i,int m){return i instanceof Info&&((Info)i).mode==m;}
  boolean returnTicketCurrent(Promotion p){return currentTicket;}
  void checkReturned(Slot s,Promotion p,int attempt,Runnable success,Consumer<String> failure){returned++;success.run();}
'''+top_configuration+member('    void applyFullscreenBounds(', scale).replace('host.','').replace('slotRegistry.','').replace('liveGeometry.','').replace('WorkspaceNativeHooks.getSurfaceField','getSurfaceField').replace('WorkspaceNativeHooks.applyTopConfiguration(host, ','applyTopConfiguration(').replace('WorkspaceNativeHooks.applyWct','applyWct').replace('WorkspaceHost.getSurfaceField','getSurfaceField').replace('WorkspaceHost.log','log')+member('    Object fullscreenExitTransaction(', transfer).replace('WorkspaceNativeHooks.applyWct','applyWct')+member('    private void appendTaskViewExitOverrides(', transfer).replace('WorkspaceNativeHooks.applyWct','applyWct')+member('    private void finishNativeReturnWhenIdle(', transfer).replace('WorkspaceNativeHooks.applyWct','applyWct')+member('    private void applyRetainedDesktopReturn(', transfer).replace('WorkspaceNativeHooks.applyWct','applyWct')+member('    void restoreRetainedMainOpacityAfterHandoff(', transfer).replace('WorkspaceNativeHooks.applyWct','applyWct')+member('    void restoreTopMainOpacity(', transfer).replace('WorkspaceNativeHooks.applyWct','applyWct')+member('    private void restoreMainOpacity(', transfer).replace('WorkspaceNativeHooks.applyWct','applyWct')+prefer_method+'''
 static TopConfigRegression host(){
  var h=new TopConfigRegression();active=h;h.infos.add(new Info(1,1,"home",false));h.infos.add(new Info(42,6,"upper",true));return h;
 }
 public static void main(String[]args)throws Exception{
  var h=host();var slot=new Slot();h.applyFullscreenBounds(slot);
  var w=(android.window.WindowContainerTransaction)h.applied;
  check(w.calls.contains("setDensityDpi:upper:0"),"top inherits native density to keep retained and new View pixels consistent");
  check(w.calls.contains("setAppBounds:upper:0,0,1647,949"),"phone app metrics use the compact logical viewport");
  check(w.calls.contains("setScreenSizeDp:upper:599:345"),"resources match the logical viewport and selected density");
  check(w.calls.contains("setSmallestScreenWidthDp:upper:345"),"smallest width belongs to the compact viewport");
  h.topPortrait=true;h.applyFullscreenBounds(slot);w=(android.window.WindowContainerTransaction)h.applied;
  check(w.calls.contains("setAppBounds:upper:0,0,534,949")&&w.calls.contains("setScreenSizeDp:upper:194:345"),
        "portrait navigation resources match the portrait viewport");
  h.topPortrait=false;
  var g=h.topGeometry();
  check(Math.round(g.logicalWidth*g.scale)==1080&&Math.round(g.logicalHeight*g.scale)==622,"compact renderer and input fill the original strip");
  var wide=new TopTaskGeometry(1080,2460,622,false,2.75f);
  check(wide.logicalWidth==2964&&wide.logicalHeight==1707,"noncompact viewport keeps the existing tablet metrics");
  slot.index=1;h.applyFullscreenBounds(slot);w=(android.window.WindowContainerTransaction)h.applied;
  check(w.calls.stream().noneMatch(s->s.startsWith("setDensityDpi")),"other side density unchanged");
  check(w.calls.stream().noneMatch(s->s.startsWith("setAppBounds")||s.startsWith("setScreenSizeDp")||s.startsWith("setSmallestScreenWidthDp")),"ordinary side tasks acquire no top resource overrides");
  slot.rect=new int[]{0,0,270,600};h.applyFullscreenBounds(slot);w=(android.window.WindowContainerTransaction)h.applied;
  check(w.calls.contains("setDensityDpi:upper:107"),"ordinary side task uses compact slot density");
  var p=new Promotion();w=(android.window.WindowContainerTransaction)h.fullscreenExitTransaction(p);
  check(w.calls.contains("setDensityDpi:upper:0"),"top native return clears density override");
  check(w.calls.contains("setAppBounds:upper:null")&&w.calls.contains("setScreenSizeDp:upper:0:0")&&w.calls.contains("setSmallestScreenWidthDp:upper:0"),"return clears all top metrics in the same native transaction");
  check(w.calls.contains("setFocusable:upper:true")&&w.calls.contains("reorder:side:true"),"return keeps native focusability and side visibility");
  check(w.calls.contains("setForceTranslucent:upper:false"),"ordinary return restores opaque native policy");
  p.liveMainReveal=true;w=(android.window.WindowContainerTransaction)h.fullscreenExitTransaction(p);
  check(w.calls.contains("setForceTranslucent:upper:true"),"live return keeps the real underlying Home/app visible");
  p.liveMainReveal=false;
  p.slot.index=1;w=(android.window.WindowContainerTransaction)h.fullscreenExitTransaction(p);
  check(w.calls.contains("setDensityDpi:upper:0"),"side return clears compact density in the same transaction");
  check(w.calls.stream().noneMatch(s->s.startsWith("setAppBounds")||s.startsWith("setScreenSizeDp")||s.startsWith("setSmallestScreenWidthDp")),"ordinary side return leaves resource policy alone");
  h=host();p=new Promotion();h.infos.set(1,new Info(42,1,"upper",true));
  h.finishNativeReturnWhenIdle(p,()->{},error->{throw new AssertionError(error);});
  check(h.applied==null&&h.returned==0,"native animation keeps sole control before idle");
  h.idleQueue.remove(0).run();w=(android.window.WindowContainerTransaction)h.applied;
  check(h.returned==1&&w.calls.contains("setForceTranslucent:upper:false")&&w.calls.contains("setAppBounds:upper:null"),
       "native handback clears actual TOP flags after idle");
  check(w.calls.stream().noneMatch(s->s.startsWith("setWindowingMode")||s.startsWith("setBounds")||s.startsWith("setHidden")
       ||s.startsWith("reorder")||s.contains(":side:")),"native handback never reopens or reorders the app");
  h=host();p=new Promotion();h.scene=SUSPENDED;h.retainedOnExit=true;
  h.infos.set(1,new Info(42,1,"upper",false));h.infos.get(1).isVisible=false;
  h.finishNativeReturnWhenIdle(p,()->{},error->{throw new AssertionError(error);});
  h.idleQueue.remove(0).run();w=(android.window.WindowContainerTransaction)h.applied;
  check(w.calls.contains("setHidden:upper:false"),"hidden retained task carried a stale hidden flag into fullscreen");
  check(w.calls.stream().noneMatch(s->s.startsWith("setWindowingMode")||s.startsWith("setBounds")
       ||s.startsWith("reorder")),"retained visibility repair only clears stale flags");
  h=host();p=new Promotion();final List<String> failures=new ArrayList<>();
  h.finishNativeReturnWhenIdle(p,()->{},failures::add);h.currentTicket=false;h.idleQueue.remove(0).run();
  check(h.applied==null&&h.returned==0&&failures.size()==1,"stale idle callback cannot write another task");
  h=host();p=new Promotion();
  h.applyRetainedDesktopReturn(p,()->{},error->{throw new AssertionError(error);});
  w=(android.window.WindowContainerTransaction)h.applied;
  check(w.calls.contains("setHidden:upper:false")&&w.calls.contains("setForceTranslucent:upper:true"),
        "retained return makes the app drawable and keeps the current backdrop visible");
  check(h.returned==0,"queued WCT cannot retire the TaskView before native draw sync");
  SyncTransaction sync=new SyncTransaction();h.drawReady.accept(sync);
  check(h.returned==0&&sync.callback!=null,"native draw transaction completes before handoff");
  sync.executor.execute(sync.callback);check(h.returned==1,"draw commit confirms the same task");
  h=host();p=new Promotion();p.restoreMainOpacityAfterHandoff=true;
  h.infos.set(1,new Info(42,1,"upper",true));h.restoreRetainedMainOpacityAfterHandoff(p);
  check(h.applied==null&&p.restoreMainOpacityAfterHandoff,"backdrop stays visible until native task handoff completes");
  p.handoffComplete=true;h.restoreRetainedMainOpacityAfterHandoff(p);w=(android.window.WindowContainerTransaction)h.applied;
  check(w.calls.contains("setForceTranslucent:upper:false")&&!p.restoreMainOpacityAfterHandoff,
        "native handoff restores opaque app policy only after the replacement surface is ready");
  h=host();p=new Promotion();p.restoreMainOpacityAfterHandoff=true;p.handoffComplete=true;
  h.infos.set(1,new Info(42,1,"upper",true));h.currentTicket=false;h.restoreRetainedMainOpacityAfterHandoff(p);
  check(h.applied==null,"old opacity completion cannot modify a rebound task");
  h=host();h.preferMainAfterTopChange();check(h.service.calls==1&&h.service.last==1,"admitted top defaults to visible Home");
  h=host();h.infos.add(new Info(3,1,"main",false));h.current=new LiveTask(3,"main");h.preferMainAfterTopChange();
  check(h.service.calls==1&&h.service.last==3,"existing main app wins over top and Home");
  for(int state=0;state<7;state++){
   h=host();switch(state){case 0:h.suspendRequested=true;break;case 1:h.screenOn=false;break;case 2:h.keyguardLocked=true;break;
    case 3:h.scene=2;break;case 4:h.infos.add(new Info(9,5,"foreign",true));break;case 5:h.infos.get(0).isVisible=false;break;
    case 6:h.defer=true;break;}
   h.preferMainAfterTopChange();if(state==6){h.sceneEpoch++;h.queue.remove(0).run();}
   check(h.service.calls==0,"do not steal focus across unsafe/stale state "+state);
  }
  h=host();p=new Promotion();h.infos.set(1,new Info(42,1,"upper",true));h.restoreTopMainOpacity(p);
  check(((android.window.WindowContainerTransaction)h.applied).calls.contains("setForceTranslucent:upper:false"),
   "same fullscreen task restores native opacity after actual-surface reveal");
  h=host();h.restoreTopMainOpacity(new Promotion());check(h.applied==null,"late cleanup cannot modify readopted freeform");
  h=host();h.infos.set(1,new Info(42,1,"replacement",true));h.restoreTopMainOpacity(new Promotion());
  check(h.applied==null,"reused task id with different token is not touched");
  h=host();h.defer=true;h.infos.set(1,new Info(42,1,"upper",true));h.restoreTopMainOpacity(new Promotion());
  h.infos.set(1,new Info(42,6,"upper",true));h.queue.remove(0).run();
  check(h.applied==null,"queued restore rechecks current ownership at execution");
  System.out.println("Production top density/return/default-focus checks="+checks+" passed");
 }
}'''
files={'android/view/SurfaceControl.java':'package android.view;public class SurfaceControl {public interface TransactionCommittedListener {void onTransactionCommitted();}}',
 'io/github/zerohomers0/onesteplite/TopConfigRegression.java':body,
 'android/window/WindowContainerTransaction.java':'package android.window;public class WindowContainerTransaction {public java.util.List<String>calls=new java.util.ArrayList<>();}',
 'android/view/WindowInsets.java':'package android.view;public class WindowInsets {public static class Type {public static int captionBar(){return 32;}}}',
 'android/graphics/Rect.java':'package android.graphics;public class Rect {int a,b,c,d;public Rect(){}public Rect(int a,int b,int c,int d){this.a=a;this.b=b;this.c=c;this.d=d;}public int width(){return c-a;}public int height(){return d-b;}public String toString(){return a+","+b+","+c+","+d;}}',
 'android/app/ActivityTaskManager.java':'package android.app;public class ActivityTaskManager {}'}
paths=[]
for name,text in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths,str(src/'TopTaskGeometry.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.TopConfigRegression'],check=True)
