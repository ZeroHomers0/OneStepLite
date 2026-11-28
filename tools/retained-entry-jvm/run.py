"""Replay retained entry: all native tasks/parents precede the main zoom."""
from pathlib import Path
import os
import subprocess
import sys
import sys as _sys, pathlib as _pathlib; _sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1])); from source_paths import package_dir

root = Path(__file__).resolve().parents[2]
src=package_dir(root)
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
source = (src / 'WorkspaceResumeController.java').read_text(encoding='utf-8-sig')


def member(signature):
    a = source.index(signature)
    b = source.index('{', a) + 1
    depth = 1
    while depth:
        depth += (source[b] == '{') - (source[b] == '}')
        b += 1
    return source[a:b]


methods = '\n'.join(member(s) for s in [
    '    private void showResumingSession(', '    private void awaitRetainedWorkspaceFrame(',
    '    private void confirmRetainedResume(long epoch, int attempt) {',
    '    private void confirmRetainedResume(long epoch, int attempt, boolean visibilityRestored)',
    '    private void finishRetainedResumeCheck(',
    '    private void prepareRetainedWorkspaceEntry(']).replace('host.','').replace('slotRegistry.','').replace('sideRows.','').replace('windowPresentation.','').replace('liveGeometry.','').replace('liveHandoff.','').replace('promotionController.','').replace('topPresentationHost.','').replace('topAdmission.','').replace('taskTransfer.','').replace('keyguard.','').replace('resumes.','').replace('carriers.','').replace('retainedSurfaces.','').replace('chrome.','').replace('mainTaskActions.','').replace('taskStates.','').replace('slotLifecycle.','').replace('sideInteraction.','').replace('displays.','').replace('wallpapers.','').replace('memory.','').replace('debug.','').replace('scales.','').replace('cells.','').replace('exits.','').replace('mainLaunch.','').replace('transitionWindow.','').replace('contentDrag.','').replace('sideCloses.','').replace('host::','this::').replace('exits.','').replace('WorkspaceHost.getSurfaceField','getSurfaceField').replace('WorkspaceHost.ACTIVE','ACTIVE').replace('WorkspaceHost.SUSPENDING','SUSPENDING').replace('WorkspaceHost.SUSPENDED','SUSPENDED').replace('WorkspaceHost.RESUMING','RESUMING').replace('WorkspaceHost.log','log')

methods=methods.replace('WorkspaceNativeHooks.getSurfaceField','getSurfaceField').replace('WorkspaceNativeHooks.applyWct','applyWct').replace('chrome::','this::')
files = {
    'android/window/WindowContainerTransaction.java': '''package android.window;
import java.util.*;public class WindowContainerTransaction {
 public List<Object> restored=new ArrayList<>();
 public void setHidden(Object t,boolean b){if(!b)restored.add(t);}
 public void setAlwaysOnTop(Object t,boolean b){}public void reorder(Object t,boolean b){}
}''',
    'android/view/SurfaceControl.java': '''package android.view;
import java.util.concurrent.Executor;
public class SurfaceControl {public static class Transaction {
 public static Transaction waiting;public boolean applied,committed;Executor executor;Runnable callback;
 public Transaction(){waiting=this;}
 public void addTransactionCommittedListener(Executor e,Runnable r){executor=e;callback=r;}
 public void apply(){applied=true;}public void close(){}
 public void commit(){if(!applied)throw new AssertionError();committed=true;executor.execute(callback);}
}}''',
    'io/github/zerohomers0/onesteplite/RetainedEntryRegression.java': r'''package io.github.zerohomers0.onesteplite;
import java.util.*;import android.view.SurfaceControl.Transaction;
public class RetainedEntryRegression {
 static final int ACTIVE=1,RESUMING=2,SUSPENDED=3,SLOT_MAIN=0,CELL_SLOT_COUNT=4;
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static class Queue {ArrayDeque<Runnable> now=new ArrayDeque<>(),later=new ArrayDeque<>();
  void post(Runnable r){now.add(r);}void postDelayed(Runnable r,long ms){later.add(r);}}
 static class View {static final int VISIBLE=0,INVISIBLE=4;int visibility;Object token;
  Runnable frame;boolean isAttachedToWindow(){return true;}boolean isHardwareAccelerated(){return true;}View getViewTreeObserver(){return this;}void registerFrameCommitCallback(Runnable r){frame=r;}void invalidate(){}void commitFrame(){Runnable r=frame;frame=null;if(r!=null)r.run();}
  int getVisibility(){return visibility;}void setVisibility(int v){visibility=v;}}
 static class Slot {int index,taskId,sideRow=-1;boolean occupied=true,parked,sidePrepared,owned=true;
  Object taskToken=new Object(),controller=new Object();View view=new View();Info info=new Info();Slot(int i){index=i;taskId=10+i;}}
 public static class Info {public boolean isVisible;int mode=6;}
 static class LiveTask {Slot slot;LiveTask(Slot s){slot=s;}}
 public static class Shell {public void runOnIdle(Runnable r){r.run();}}
 public static class Transitions {public Shell mTransitions=new Shell();}
 static class ShellCompat {static Object call(Object o,String n,Object...a){return XposedHelpers.callMethod(o,n,a);}}
 static class XposedHelpers {
  static Object getObjectField(Object o,String n){try{var f=o.getClass().getField(n);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
  static boolean getBooleanField(Object o,String n){return (Boolean)getObjectField(o,n);}
  static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}
 }
 static class EntryGuard {static boolean canOpen(Object c){return true;}}
 static class DeviceProfile {static boolean isTablet(Object c){return false;}}
 Object ctx=new Object();View root=new View();Slot[] slots={new Slot(1),new Slot(2),new Slot(3)};
 Queue main=new Queue(),shell=new Queue();Transitions transitions=new Transitions();
 int scene=RESUMING,keyguardResumeIndex=-1,entries,completed,queries,parents,suspends,adoptions;
 long sceneEpoch=7,workspaceGeneration;boolean retainedOnExit=true,retainedForLock,keyguardLocked,screenOn=true,suspendRequested,workspaceEntering;
 float alpha=1;Runnable entryDone;Set<Object> restored=new HashSet<>();
 boolean executeOnShell(Runnable r){queries++;shell.post(r);return true;}
 public static class Surface {public boolean isValid(){return true;}}
 Object getSurfaceField(Object c,String n){return new Surface();}
 Object rootInfo(LiveTask t){return t.slot.info;}boolean ordinaryRoot(Object info,int mode){return ((Info)info).mode==mode;}
 boolean isActive(Slot s){return true;}boolean controllerOwns(Slot s,int id,Object token){return s.owned;}
 void applyWct(String reason,Object transaction){var w=(android.window.WindowContainerTransaction)transaction;
  restored.addAll(w.restored);for(Slot s:slots)if(w.restored.contains(s.taskToken))s.info.isVisible=true;}
 void setWorkspaceAlpha(float a){alpha=a;}void setMirrorLayerVisible(boolean v){}
 void collapseStatusBarPanels(){}void refreshDisplayGeometry(){}void consumePendingMainSide(){}void log(String s){}
 void beginWorkspaceEntry(Runnable r){
  if(retainedOnExit)check(Transaction.waiting.committed&&parents==3,"all parents must commit before zoom starts");
  entries++;entryDone=r;
 }
 void completeWorkspaceResume(long e,boolean retained){completed++;scene=ACTIVE;retainedOnExit=false;}
 void resumeNext(int i,long e){adoptions++;}
 void suspendSession(String r){suspends++;scene=SUSPENDED;}
 void appendWorkspaceSurfaces(Object t,List<Long> epochs){check(alpha==0&&entries==0,"task preparation remains invisible");parents=3;}
 void closeSurfaceObject(Object t,String n){}
 void drain(){while(!shell.now.isEmpty()||!main.now.isEmpty()){
  while(!shell.now.isEmpty())shell.now.remove().run();while(!main.now.isEmpty())main.now.remove().run();}}
 void delayed(){while(!main.later.isEmpty())main.now.add(main.later.remove());drain();}
 __METHODS__
 public static void main(String[]args){
  var h=new RetainedEntryRegression();h.slots[0].info.isVisible=true;Object[] tokens=Arrays.stream(h.slots).map(s->s.taskToken).toArray();
  h.showResumingSession(7);check(h.alpha==0&&h.entries==0&&h.workspaceEntering,"first retained cell cannot start entry alone");check(h.queries==0,"task ownership query waits for recreated SurfaceView parents");h.root.commitFrame();h.drain();
  check(h.restored.size()==2&&h.entries==0,"hidden fixed-layout peers restored before main zoom, not after fade");h.delayed();
  check(h.parents==3&&h.entries==0&&Transaction.waiting.applied,"all native parents staged together behind zero opacity");
  Transaction.waiting.commit();h.drain();check(h.entries==1&&h.completed==0,"only committed parent group starts one zoom");
  h.entryDone.run();check(h.completed==1&&h.adoptions==0,"retained entry reuses existing tasks");
  for(int i=0;i<3;i++)check(h.slots[i].taskToken==tokens[i],"entry preserves exact task identity");
  h=new RetainedEntryRegression();for(Slot s:h.slots)s.info.isVisible=true;h.showResumingSession(7);h.root.commitFrame();h.drain();
  h.sceneEpoch++;Transaction.waiting.commit();h.drain();check(h.entries==0,"late parent commit cannot revive previous session");
  h=new RetainedEntryRegression();for(Slot s:h.slots)s.info.isVisible=true;h.showResumingSession(7);h.root.commitFrame();h.drain();h.delayed();
  check(h.suspends==1&&h.entries==0,"lost parent commit cannot strand hidden resume");Transaction.waiting.commit();h.drain();check(h.entries==0,"late parent commit cannot revive timeout fallback");
  h=new RetainedEntryRegression();for(Slot s:h.slots)s.info.isVisible=true;h.showResumingSession(7);h.root.commitFrame();h.drain();h.workspaceGeneration++;Transaction.waiting.commit();h.drain();check(h.entries==0,"relock invalidates prepared parent callback without changing scene epoch");
  h=new RetainedEntryRegression();h.slots[2].owned=false;h.showResumingSession(7);h.root.commitFrame();h.drain();h.delayed();h.delayed();
  check(h.entries==0&&h.suspends==1,"foreign/replaced task never enters partial workspace");
  h=new RetainedEntryRegression();h.retainedForLock=true;h.showResumingSession(7);
  check(h.keyguardResumeIndex==1&&h.queries==0&&h.entries==0,"lock pauses hidden retained preparation");
  h=new RetainedEntryRegression();h.retainedOnExit=false;h.showResumingSession(7);
  check(h.entries==1&&h.queries==0,"cold entry keeps its separate task adoption path");
  System.out.println("Production retained entry preparation: "+checks+" checks passed");
 }
}'''.replace('__METHODS__', methods)
}
paths = []
for name, body in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf-8')
    paths.append(str(path))
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), *paths, str(src/'framework/platform/TransactionCompat.java')], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.RetainedEntryRegression'], check=True)
