"""Replay production fullscreen routing against task replacement, native commits and lock races."""
from pathlib import Path
import os, subprocess, sys
import sys as _sys, pathlib as _pathlib; _sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1])); from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root)
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
s=(src/'WorkspaceHost.java').read_text(encoding='utf-8')
def member(signature):
 start=s.index(signature);end=s.index('{',start)+1;depth=1
 while depth:
  depth+=(s[end]=='{')-(s[end]=='}');end+=1
 return s[start:end]
methods='\n'.join(member('    private void '+n+'(') for n in ['observeWideVideo','attemptWideVideo'])
files={
'android/os/SystemClock.java': 'package android.os;public class SystemClock {public static long now=1000;public static long uptimeMillis(){return now;}}',
'android/widget/Toast.java': 'package android.widget;public class Toast {public static final int LENGTH_SHORT=0;public static int shown;public static Toast makeText(Object c,String s,int d){shown++;return new Toast();}public void show(){}}',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;public class XposedHelpers {
 public static boolean getBooleanField(Object o,String n){return (Boolean)getObjectField(o,n);}
 public static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object callMethod(Object o,String n,Object...args){try{var m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new RuntimeException(e);}}}''',
'android/view/WindowManager.java': 'package android.view;public class WindowManager {public static class LayoutParams {public static final int FLAG_FULLSCREEN=1024;public int flags,systemUiVisibility;}}',
'android/view/View.java': 'package android.view;public class View {public static final int SYSTEM_UI_FLAG_FULLSCREEN=4;}',
'android/view/WindowInsets.java': 'package android.view;public class WindowInsets {public static class Type {public static int statusBars(){return 1;}}}',
'io/github/zerohomers0/onesteplite/WideVideoRegression.java':'''package io.github.zerohomers0.onesteplite;
import java.util.*;import de.robv.android.xposed.XposedHelpers;
public class WideVideoRegression {
 static final int ACTIVE=1,RESUMING=2,SLOT_TOP=4;static int checks;
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static class EntryGuard {static boolean allowed=true;static boolean canOpen(Object c){return allowed;}}
 static class Clipboard {static Clipboard get(Object c){return new Clipboard();}void close(){}}
 static class Slot {boolean occupied;Object view=new Object(),controller=new Object();}
 class Roll {boolean moving,task;int resets;boolean rolling(){return moving;}
  void show(boolean t,Runnable r){throw new AssertionError("automatic pin must not roll header");}
  void reset(boolean t){moving=false;task=t;resets++;}}
 class Handler {List<Runnable> queue=new ArrayList<>();void postDelayed(Runnable r,long ms){queue.add(r);}void drain(){var list=new ArrayList<>(queue);queue.clear();for(var r:list)r.run();}}
 static class TopCandidate {TopTaskState.Key key;Object task=new Info();TopCandidate(TopTaskState.Key k){key=k;}}
 public static class Info {public boolean isFocused=true;}
 Object root=new Object(),ctx=new Object(),topTransition;int scene=ACTIVE;long sceneEpoch=1,wideVideoSequence,wideVideoEpoch=-1;
 boolean screenOn=true,keyguardLocked,retainedForLock,suspendRequested,ready=true,topFace;
 final Handler main=new Handler();final Roll topRoll=new Roll();
 final Slot[] slots={new Slot(),new Slot(),new Slot(),new Slot(),new Slot()};
 WorkspaceOptions workspaceOptions=WorkspaceOptions.DEFAULT;final WideVideoPolicy wideVideoPolicy=new WideVideoPolicy();
 final TopTaskState topTaskState=new TopTaskState();
 final Map<TopTaskState.Key,Boolean> topDirections=new HashMap<>();
 final TopTaskState.Key key=new TopTaskState.Key(7,0,"original-token");TopCandidate current=new TopCandidate(key);
 int claimed,released,suspended;TopTaskState.Ticket ticket;
 boolean supportsTaskUser(int user){return user==0||user==999;}
 boolean topReady(){return ready&&scene==ACTIVE&&screenOn&&!keyguardLocked&&!retainedForLock;}
 TopCandidate currentTopCandidate(){return current;}Object rootInfo(Object task){return task;}
 void log(String s){}void prepareLiveTopRoll(){}void topRollSettled(boolean t){topFace=t;}
 void suspendSession(String s){check(s.equals("video-fullscreen"),"explicit fullscreen exit reason");suspended++;scene=3;sceneEpoch++;}
 void claimTopTask(TopCandidate c,boolean manual){check(manual&&c.key.equals(key),"claims exact original task");claimed++;
  ticket=topTaskState.request(c.key,sceneEpoch,true,true,true,true);}
 void releaseTopTask(boolean front,boolean dismissed,Runnable r){check(front&&!dismissed,"automatic return alive to main");released++;topTaskState.release(topTaskState.owner(),false);}
 void commit(){check(topTaskState.commit(ticket,sceneEpoch),"native ownership commit");slots[SLOT_TOP].occupied=true;}
 void signal(boolean land,boolean full,boolean focused,int nativeMode){topDirections.put(key,land);observeWideVideo(key,land,full,focused,nativeMode,!land,1000);}
'''+methods+'''
 public static void main(String[]args){
  for(int mode=1;mode<3;mode++){
   var h=new WideVideoRegression();h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(mode);h.signal(true,true,true,1);
   if(mode==1){check(h.claimed==0&&h.suspended==0&&h.topRoll.resets==0,"turned remains native main");
    for(int i=0;i<20;i++){h.signal(false,false,false,6);h.signal(true,true,false,6);}
    check(h.released==0,"native state changes cannot release a main APP task");}
   if(mode==2)check(h.suspended==1&&h.claimed==0,"fullscreen exits workspace");
  }
  for(int mutation=0;mutation<9;mutation++){
   var h=new WideVideoRegression();h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(1);
   switch(mutation){case 0:h.keyguardLocked=true;break;case 1:h.screenOn=false;break;case 2:h.retainedForLock=true;break;
    case 3:h.root=null;break;case 4:h.suspendRequested=true;break;case 5:h.scene=3;break;case 6:((Info)h.current.task).isFocused=false;break;
    case 7:h.current=new TopCandidate(new TopTaskState.Key(7,0,"replacement-token"));break;case 8:EntryGuard.allowed=false;break;}
   h.signal(true,true,true,1);check(h.topRoll.resets==0&&h.claimed==0&&h.suspended==0,"privacy/ownership rejection "+mutation);EntryGuard.allowed=true;
  }
  for(int mutation=0;mutation<4;mutation++){
   var h=new WideVideoRegression();h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(1);
   h.signal(mutation!=0,mutation!=1,mutation!=2,mutation==3?6:1);check(h.claimed==0,"not foreground fullscreen landscape "+mutation);
  }
  var h=new WideVideoRegression();h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(1);h.ready=false;h.signal(true,true,true,1);
  check(h.main.queue.size()==1&&h.claimed==0,"entry waits without moving app");h.ready=true;h.main.drain();check(h.claimed==0&&h.suspended==0,"ready entry leaves the main app alone");
  h=new WideVideoRegression();h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(1);h.ready=false;h.signal(true,true,true,1);h.sceneEpoch++;h.ready=true;h.main.drain();check(h.claimed==0,"stale request cannot collect into new scene");
  h=new WideVideoRegression();h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(1);h.ready=false;h.signal(true,true,true,1);h.keyguardLocked=true;h.ready=true;h.main.drain();check(h.claimed==0,"relock cancels deferred collection");
  h=new WideVideoRegression();h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(1);h.ready=false;h.signal(true,true,true,1);h.current=null;h.ready=true;h.main.drain();check(h.claimed==0&&!h.topFace,"switched task cannot replace header");
  h=new WideVideoRegression();h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(1);h.slots[4].occupied=true;h.signal(true,true,true,1);check(h.topRoll.resets==0&&h.claimed==0,"existing TOP never replaced");
  h=new WideVideoRegression();h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(1);h.signal(true,true,true,1);h.signal(false,false,false,6);
  check(h.claimed==0&&h.released==0&&h.main.queue.isEmpty(),"turned request never creates an APP admission or handback");
  h=new WideVideoRegression();h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(1);h.slots[4].view=null;h.signal(true,true,true,1);check(h.main.queue.size()==1&&h.claimed==0,"wait for native TaskView before auto admission");h.slots[4].view=new Object();h.main.drain();check(h.claimed==0&&h.suspended==0,"ready strip does not collect a task");
  h=new WideVideoRegression();h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(1);h.ready=false;h.signal(true,true,true,1);h.signal(true,false,true,1);h.ready=true;h.main.drain();check(h.claimed==0,"fullscreen exit before admission cancels collection");
  h=new WideVideoRegression();h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(1);h.ready=false;h.signal(true,true,true,1);((Info)h.current.task).isFocused=false;h.ready=true;h.main.drain();check(h.claimed==0,"focus revalidated after entry");
  h=new WideVideoRegression();h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(1);h.ready=false;h.signal(true,true,true,1);android.os.SystemClock.now=4000;h.ready=true;h.main.drain();check(h.claimed==0,"expired deferred request cannot collect");android.os.SystemClock.now=1000;
  h=new WideVideoRegression();h.signal(true,true,true,1);h.sceneEpoch++;h.workspaceOptions=h.workspaceOptions.withLandscapeVideo(1);h.signal(true,true,true,1);check(h.claimed==0&&h.suspended==0,"fresh workspace leaves the retained task alone");
  var p=new WideVideoPolicy();TopTaskState.Key a=new TopTaskState.Key(7,0,"a"),b=new TopTaskState.Key(7,999,"a"),c=new TopTaskState.Key(7,0,"b");p.handled(a);
  check(!p.eligible(a,true,true,true,1)&&p.eligible(b,true,true,true,1)&&p.eligible(c,true,true,true,1),"dedup scoped to token and user");
  p.observe(a,false,false,6);check(!p.eligible(a,true,true,true,1),"native TOP configuration cannot start new episode");p.observe(a,false,false,1);check(p.eligible(a,true,true,true,1),"next fullscreen episode can trigger");
  check(!WideVideoPolicy.fresh(5000,1000)&&!WideVideoPolicy.fresh(900,1000),"old and future signals rejected");
  System.out.println("Production wide-video routing: "+checks+" checks passed");
 }
}'''}
saved=s
s=(src/'HostEntry.java').read_text(encoding='utf-8')
files['io/github/zerohomers0/onesteplite/FullscreenSceneRegression.java']='''package io.github.zerohomers0.onesteplite;
import java.util.*;import android.view.WindowManager;import de.robv.android.xposed.XposedHelpers;
public class FullscreenSceneRegression {
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static final Object sDisplayContent=new Object();static boolean sFullscreenRotationActive=true;static int sHeldDisplayRotation=0;
 static final Map<Object,Integer> sTaskRotations=new HashMap<>();
 static class Activity {Object mDisplayContent=sDisplayContent;Task task;Window window;boolean home;int request=0;
  Object getTask(){return task;}Object findMainWindow(){return window;}int getRootTaskId(){return 42;}
  int getRequestedOrientation(){return request;}boolean isActivityTypeHome(){return home;}}
 static class Window {Activity mActivityRecord;WindowManager.LayoutParams mAttrs=new WindowManager.LayoutParams();int visible=1;boolean fail;
  int getRequestedVisibleTypes(){if(fail)throw new IllegalStateException();return visible;}}
 static class Task {Activity top;int mode=1;boolean upper;Object topRunningActivity(){return top;}int getWindowingMode(){return mode;}}
 int sends,lastId,lastRotation,lastRequest,lastMode;
 boolean isUpperLandscapeTask(Object t){return ((Task)t).upper;}
 void sendTopTaskDirection(Object t,int id,int request,int resolved,int rotation,int mode){sends++;lastId=id;lastRotation=rotation;lastRequest=request;lastMode=mode;}
'''+member('    private boolean wideVideoFullscreen(')+member('    private void refreshWideVideoSignal(')+'''
 public static void main(String[]args){
  var h=new FullscreenSceneRegression();var a=new Activity();var w=new Window();var t=new Task();a.task=t;a.window=w;w.mActivityRecord=a;t.top=a;
  check(!h.wideVideoFullscreen(t),"ordinary app not fullscreen");w.mAttrs.flags=1024;check(h.wideVideoFullscreen(t),"legacy fullscreen flag");
  w.mAttrs.flags=0;w.mAttrs.systemUiVisibility=4;check(h.wideVideoFullscreen(t),"legacy immersive visibility");
  w.mAttrs.systemUiVisibility=0;w.visible=0;check(h.wideVideoFullscreen(t),"modern hidden status insets");
  w.fail=true;check(!h.wideVideoFullscreen(t),"missing vendor insets fails closed");w.fail=false;
  a.home=true;check(!h.wideVideoFullscreen(t),"launcher excluded");a.home=false;a.window=null;check(!h.wideVideoFullscreen(t),"missing main window excluded");a.window=w;
  sTaskRotations.put(a,3);h.refreshWideVideoSignal(w);check(h.sends==1&&h.lastId==42&&h.lastRotation==3&&h.lastRequest==0,"surface pass emits exact owned rotation and root task");
  a.mDisplayContent=new Object();h.refreshWideVideoSignal(w);check(h.sends==1,"secondary display excluded");a.mDisplayContent=sDisplayContent;
  a.window=new Window();h.refreshWideVideoSignal(w);check(h.sends==1,"dialog excluded");a.window=w;
  t.top=new Activity();h.refreshWideVideoSignal(w);check(h.sends==1,"departing page excluded");t.top=a;
  t.mode=6;h.refreshWideVideoSignal(w);check(h.sends==1,"ordinary side task excluded");t.upper=true;h.refreshWideVideoSignal(w);check(h.sends==2&&h.lastMode==6,"TOP exit observable");
  sFullscreenRotationActive=false;h.refreshWideVideoSignal(w);check(h.sends==2,"inactive workspace excluded");
  System.out.println("Production fullscreen flags and surface observation: "+checks+" checks passed");
 }
}'''
s=saved
paths=[]
for name,body in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths += [str(src/n) for n in ['WorkspaceOptions.java','TopTaskState.java','WideVideoPolicy.java']]
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN']);classes=out/'classes';classes.mkdir(exist_ok=True)
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(classes),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(classes),'io.github.zerohomers0.onesteplite.WideVideoRegression'],check=True)

subprocess.run([str(jdk/'java.exe'),'-cp',str(classes),'io.github.zerohomers0.onesteplite.FullscreenSceneRegression'],check=True)
