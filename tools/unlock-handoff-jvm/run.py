"""Execute production authenticated-unlock coordinator with controlled WM acknowledgements."""
from pathlib import Path
import os,subprocess,sys
import sys as _sys, pathlib as _pathlib; _sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1])); from source_paths import package_dir
root=Path(__file__).resolve().parents[2]
src=package_dir(root)
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/os/Looper.java':'''package android.os;public class Looper {public static Object MAIN=new Object(),current=MAIN;public static Object myLooper(){return current;}}''',
'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {
 public Object getLooper(){return Looper.MAIN;}public boolean post(Runnable r){r.run();return true;}
 public List<Runnable> delayed=new ArrayList<>();public boolean postDelayed(Runnable r,long ms){delayed.add(r);return true;}
 public void timeout(){for(Runnable r:List.copyOf(delayed))r.run();delayed.clear();}}''',
'android/content/BroadcastReceiver.java':'''package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}''',
'android/content/IntentFilter.java':'''package android.content;public class IntentFilter {public final String action;public IntentFilter(String a){action=a;}}''',
'android/content/Intent.java':'''package android.content;import java.util.*;public class Intent {
 public static final int FLAG_RECEIVER_REGISTERED_ONLY=1;public final String action;public String pkg;public int flags;public Map<String,Object> extras=new HashMap<>();
 public Intent(String a){action=a;}public Intent setPackage(String p){pkg=p;return this;}public Intent addFlags(int f){flags|=f;return this;}
 public Intent putExtra(String k,long v){extras.put(k,v);return this;}public Intent putExtra(String k,int v){extras.put(k,v);return this;}
 public Intent putExtra(String k,boolean v){extras.put(k,v);return this;}public Intent putExtra(String k,Object v){extras.put(k,v);return this;}
 public long getLongExtra(String k,long d){return extras.containsKey(k)?(Long)extras.get(k):d;}public int getIntExtra(String k,int d){return extras.containsKey(k)?(Integer)extras.get(k):d;}
 public boolean getBooleanExtra(String k,boolean d){return extras.containsKey(k)?(Boolean)extras.get(k):d;}public String getStringExtra(String k){return (String)extras.get(k);}
 public <T>T getParcelableExtra(String k,Class<T> t){return t.cast(extras.get(k));}}''',
'android/content/Context.java':'''package android.content;import java.util.*;import android.os.Handler;
 public class Context {public static final int RECEIVER_EXPORTED=2;public BroadcastReceiver receiver;public int registered,unregistered;public boolean failRegister,failSend;public List<String> events=new ArrayList<>();public Intent sent;public String permission;
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String p,Handler h,int flags){if(failRegister)throw new IllegalStateException();receiver=r;permission=p;registered++;return null;}
 public void unregisterReceiver(BroadcastReceiver r){if(receiver==r){unregistered++;receiver=null;}}
 public void sendBroadcast(Intent i,String p){if(failSend)throw new IllegalStateException();sent=i;events.add(i.action);}
 public void deliver(Intent i){if(receiver!=null)receiver.onReceive(this,i);}}''',
'android/view/SurfaceControl.java':'''package android.view;public class SurfaceControl {public static class Transaction implements AutoCloseable {public long mNativeObject=11;public int closes,merges,applies;public static Transaction waiting;private java.util.concurrent.Executor executor;private Runnable committed;
public Transaction addTransactionCommittedListener(java.util.concurrent.Executor e,Runnable r){executor=e;committed=r;waiting=this;return this;}public static void commitLatest(){Transaction t=waiting;waiting=null;if(t!=null)t.executor.execute(t.committed);}public Transaction merge(Object other){merges++;return this;}public void apply(){applies++;}public void close(){closes++;mNativeObject=0;}}}''',
'io/github/zerohomers0/onesteplite/UnlockHandoffRegression.java':r'''package io.github.zerohomers0.onesteplite;
import android.content.*;import android.os.Handler;import android.view.SurfaceControl.Transaction;import java.util.*;
public class UnlockHandoffRegression {
 static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static Context ctx;static Handler handler;static int ready,hides;static UnlockHandoff current;static LiveSwapZoom.Ack received;
 static void fresh(){ctx=new Context();handler=new Handler();ready=0;hides=0;received=null;current=new UnlockHandoff(ctx,handler,.7f,0,606,a->{ready++;received=a;check(hides==0,"native shade hidden before retained Host was ready");},()->{hides++;ctx.events.add("NATIVE_HIDE");});}
 static Intent ack(long generation,boolean committed,float scale,Transaction replay){return new Intent(LiveSwapZoomProtocol.ACK)
  .putExtra(LiveSwapZoomProtocol.GENERATION,generation).putExtra(LiveSwapZoomProtocol.TRANSACTION,99L)
  .putExtra(LiveSwapZoomProtocol.SCALE,Float.floatToIntBits(scale)).putExtra(LiveSwapZoomProtocol.X,0)
  .putExtra(LiveSwapZoomProtocol.Y,Float.floatToIntBits(606)).putExtra(LiveSwapZoomProtocol.COMMITTED,committed)
  .putExtra(LiveSwapZoomProtocol.FINISH_REPLAY,replay);}
 static long generation(){return ctx.sent.getLongExtra(LiveSwapZoomProtocol.GENERATION,0);}
 public static void main(String[] args){
  fresh();current.start();long generation=generation();
  check(current.active()&&hides==0&&ready==0,"native hide proceeds before WMS commit");
  check(ctx.permission.equals(LiveSwapZoomProtocol.PERMISSION)&&ctx.sent.pkg.equals("android"),"bridge must be scoped and permission protected");
  check(ctx.sent.getBooleanExtra(UnlockHandoff.PHYSICAL_KEYGUARD,false),"native shade must retain physical geometry while WM prepares workspace");
  ctx.deliver(ack(generation+1,true,.7f,new Transaction()));check(ready==0&&hides==0,"wrong generation grants reveal");
  Transaction replay=new Transaction();ctx.deliver(ack(generation,true,.7f,replay));
  check(ready==1&&hides==0&&current.active(),"WMS commit is not the existing Host hardware frame");
  check(received.transactionId==99&&received.committed&&replay.closes==1,"exact WM operations consumed and closed");
  ctx.deliver(ack(generation,true,.7f,new Transaction()));check(ready==1,"duplicate WM acknowledgement delivered twice");
  current.presented();check(hides==1&&!current.active()&&ctx.unregistered==1,"Host frame releases native hide once");
  check(ctx.sent.getBooleanExtra(UnlockHandoff.WORKSPACE,false),"committed Host frame carries retained workspace intent");
  check(Float.intBitsToFloat(ctx.sent.getIntExtra(LiveSwapZoomProtocol.SCALE,0))==.7f,"release carries the exact committed main scale");
  check(ctx.events.get(ctx.events.size()-2).equals("NATIVE_HIDE")&&ctx.events.get(ctx.events.size()-1).equals(UnlockHandoff.RELEASE),"native hide precedes physical-shade release");check(ctx.sent.getBooleanExtra(UnlockHandoff.COMPLETED,false),"completed release carries native reveal permission");
  current.finish();handler.timeout();check(hides==1&&ctx.unregistered==1,"late watchdog repeats native hide");
  for(int failure=0;failure<5;failure++){
   fresh();current.start();long g=generation();Transaction t=new Transaction();if(failure==3)t.mNativeObject=0;
   Intent response=ack(g,failure!=0,failure==1?.9f:.7f,failure==2?null:t);
   if(failure==4)response.putExtra(LiveSwapZoomProtocol.ERROR,"WM mismatch");ctx.deliver(response);
   check(ready==0&&hides==1&&!current.active(),"failed WM commit cannot reveal retained Host");
   check(!ctx.sent.getBooleanExtra(UnlockHandoff.WORKSPACE,true),"failed WM commit must restore physical fullscreen");
  }
  fresh();current.start();handler.timeout();check(ready==0&&hides==1&&!current.active(),"missing WM acknowledgement must release native system");
  check(!ctx.sent.getBooleanExtra(UnlockHandoff.WORKSPACE,true),"watchdog without Host frame cannot request retained zoom");
  fresh();current.start();ctx.deliver(ack(generation(),true,.7f,new Transaction()));handler.timeout();
  check(ready==1&&hides==1,"missing Host frame cannot leave native hide blocked forever");
  fresh();current.start();current.cancel();handler.timeout();check(hides==0&&!current.active()&&ctx.unregistered==1,"relock cancels stale native hide without revealing previous session");check(!ctx.sent.getBooleanExtra(UnlockHandoff.COMPLETED,true),"cancel does not authorize native main-surface reveal");
  fresh();ctx.failRegister=true;current.start();check(hides==1&&!current.active(),"receiver registration failure cannot block native hide");
  fresh();ctx.failSend=true;current.start();check(hides==1&&!current.active(),"dispatch failure cannot block native hide");
  System.out.println("Production authenticated unlock handoff: "+checks+" checks passed");
 }
}'''
}
host_source=(src/'WorkspaceSystemEvents.java').read_text(encoding='utf-8-sig').replace('WorkspaceHost.currentInstance()', 'sInstance').replace('WorkspaceHost.', '')
start=host_source.index('    static boolean handoffNativeUnlock(')
end=host_source.index('{',start)+1;depth=1
while depth:
 depth+=(host_source[end]=='{')-(host_source[end]=='}');end+=1
keyguard_source=(src/'WorkspaceKeyguardController.java').read_text(encoding='utf-8-sig')
helper_start=keyguard_source.index('    boolean handoffLockExit(')
helper_end=keyguard_source.index('{',helper_start)+1;helper_depth=1
while helper_depth:
 helper_depth+=(keyguard_source[helper_end]=='{')-(keyguard_source[helper_end]=='}');helper_end+=1
finish_methods=[]
for signature in ['    void trackUnlockFinish(', '    void appendUnlockFinishCommit(']:
 method_start=keyguard_source.index(signature);method_end=keyguard_source.index('{',method_start)+1;method_depth=1
 while method_depth:
  method_depth+=(keyguard_source[method_end]=='{')-(keyguard_source[method_end]=='}');method_end+=1
 finish_methods.append(keyguard_source[method_start:method_end].replace('host.', '').replace('slotRegistry.','').replace('sideRows.','').replace('windowPresentation.','').replace('liveGeometry.','').replace('liveHandoff.','').replace('promotionController.','').replace('topPresentationHost.','').replace('topAdmission.','').replace('taskTransfer.','').replace('keyguard.','').replace('resumes.','').replace('carriers.','').replace('retainedSurfaces.','').replace('chrome.','').replace('mainTaskActions.','').replace('taskStates.','').replace('slotLifecycle.','').replace('sideInteraction.','').replace('displays.','').replace('wallpapers.','').replace('memory.','').replace('debug.','').replace('scales.','').replace('cells.','').replace('exits.','').replace('mainLaunch.','').replace('transitionWindow.','').replace('contentDrag.','').replace('sideCloses.','').replace('WorkspaceHost.', ''))
files['io/github/zerohomers0/onesteplite/WorkspaceHost.java']=r'''package io.github.zerohomers0.onesteplite;
import android.content.Context;import android.os.*;import android.view.SurfaceControl.Transaction;import java.util.*;
public class WorkspaceHost {
 static final int ACTIVE=2,RESUMING=1;static WorkspaceHost sInstance;Context ctx=new Context();Handler main=new Handler();
 boolean lockExitPending;int keyguardResumeIndex;static class Options {boolean keepOnLock=true;}Options workspaceOptions=new Options();boolean retainedGeometryConfirmed;boolean retainedForLock=true,keyguardWindowHidden=true,keyguardLocked=true,screenOn,workspaceEntering=true,zoomed;
 int scene=ACTIVE;long sceneEpoch=7,workspaceGeneration,zoomAnimUntil=800;float alpha;
 UnlockHandoff unlockHandoff;WorkspaceLayout layout=new WorkspaceLayout();Root root=new Root();int parents,closes;
 static class UnlockGeometryReplay {void capture(Transaction t,long e,float s,float x,float y){}}UnlockGeometryReplay unlockGeometryReplay=new UnlockGeometryReplay();
 final UnlockFinishGate unlockFinishGate=new UnlockFinishGate();{unlockFinishGate.reset(7);}
 final FinishTransactions<Long> unlockFinishTransactions=new FinishTransactions<>(16);
 static class WorkspaceLayout {float mainScale=.7f,mainOffsetX,mainOffsetY=606;}
 static class View {static final int GONE=8;}
 static class Root {boolean gone;void setVisibility(int v){gone=v==8;}Runnable frame;Root getViewTreeObserver(){return this;}void registerFrameCommitCallback(Runnable r){frame=r;}void invalidate(){}void commit(){Runnable r=frame;frame=null;if(r!=null)r.run();}}
 static class EntryGuard {static boolean unlocked=true;static boolean canOpen(Context c){return unlocked;}}
 static class XposedHelpers {static Object callMethod(Object o,String n,Object...args)throws Exception{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)return m.invoke(o,args);throw new IllegalStateException(n);}}
 void leaveKeyguardPrivacy(){check(retainedGeometryConfirmed,"completion without committed geometry");retainedForLock=false;}void suspendSession(String s){check(root.gone,"failed handoff must hide Host before normal cleanup");suspends++;}int suspends;
 static void log(String s){}void setWorkspaceAlpha(float a){alpha=a;}
 void appendWorkspaceSurfaces(Object tx,List<Long> epochs){check(((Transaction)tx).merges==1,"WM geometry and real task parents are in the same native transaction");check(epochs.contains(sceneEpoch),"scene identity lost");parents++;}
 void closeSurfaceObject(Object tx,String m){if(tx!=null){((Transaction)tx).close();closes++;}}
 void setZoomState(boolean value){zoomed=value;}boolean setKeyguardWindowHidden(boolean hidden){keyguardWindowHidden=hidden;return true;}
 ACTUAL
 EXIT_ACTUAL
 FINISH_ACTUAL
 static int checks,hides;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static WorkspaceHost fresh(){EntryGuard.unlocked=true;Looper.current=Looper.MAIN;hides=0;sInstance=new WorkspaceHost();return sInstance;}
 static android.content.Intent ack(WorkspaceHost h){long g=h.ctx.sent.getLongExtra(LiveSwapZoomProtocol.GENERATION,0);return UnlockHandoffRegression.ack(g,true,.7f,new Transaction());}
 public static void main(String[]args){
  WorkspaceHost h=fresh();check(handoffNativeUnlock(()->hides++),"retained authenticated hide not deferred");
  check(hides==0&&h.keyguardLocked&&h.keyguardWindowHidden,"WM request alone revealed workspace");
  h.ctx.deliver(ack(h));check(h.keyguardWindowHidden&&!h.zoomed&&h.root.frame==null&&!h.retainedGeometryConfirmed,"WM tuple acknowledgement alone cannot reveal before real geometry transaction commit");
  Transaction.commitLatest();check(!h.keyguardLocked&&!h.keyguardWindowHidden&&h.zoomed&&h.alpha==1&&h.parents==1&&h.closes==1,"committed WMS tuple must prepare real original Host");
  check(hides==0&&h.root.frame!=null,"WMS ack cannot replace actual Host frame commit");
  h.root.commit();check(hides==1&&h.unlockHandoff==null,"Host frame must release native hide once");
  for(int gate=0;gate<7;gate++){
   h=fresh();if(gate==0)EntryGuard.unlocked=false;if(gate==1)h.retainedForLock=false;
   if(gate==2)h.keyguardWindowHidden=false;if(gate==3)h.root=null;if(gate==4)h.layout=null;if(gate==5)h.scene=0;if(gate==6)Looper.current=null;
   check(!handoffNativeUnlock(()->hides++)&&h.ctx.sent==null&&hides==0,"native hide changed without exact admission and retained owner");
  }
  h=fresh();handoffNativeUnlock(()->hides++);h.sceneEpoch++;h.ctx.deliver(ack(h));check(h.parents==0&&h.keyguardWindowHidden&&hides==1,"stale scene cannot reveal original Host");
  h=fresh();handoffNativeUnlock(()->hides++);EntryGuard.unlocked=false;h.ctx.deliver(ack(h));check(h.parents==0&&h.keyguardWindowHidden&&hides==0,"relock after request cannot run stale native hide or reveal workspace");
  h=fresh();handoffNativeUnlock(()->hides++);h.ctx.deliver(ack(h));Transaction.commitLatest();EntryGuard.unlocked=false;h.root.commit();check(hides==0,"relock between WM commit and Host frame cannot dismiss new keyguard");
  for(int gate=0;gate<3;gate++){
   h=fresh();handoffNativeUnlock(()->hides++);
   if(gate>=1)h.ctx.deliver(ack(h));if(gate==2)Transaction.commitLatest();
   h.main.timeout();
   check(h.root.gone&&h.suspends==1&&!h.retainedForLock&&!h.retainedGeometryConfirmed,"missing WM ack, SF commit or Host frame falls back without exposing clipped content");
   Transaction.commitLatest();h.root.commit();check(h.suspends==1,"late geometry/frame cannot revive failed workspace");
  }
  h=fresh();handoffNativeUnlock(()->hides++);h.ctx.deliver(ack(h));Transaction.commitLatest();Runnable oldFrame=h.root.frame;
  h.unlockHandoff.cancel();h.unlockHandoff=null;h.keyguardWindowHidden=true;h.keyguardLocked=true;
  handoffNativeUnlock(()->hides++);UnlockHandoff next=h.unlockHandoff;oldFrame.run();
  check(h.unlockHandoff==next&&next.active()&&!h.retainedGeometryConfirmed,"old frame cannot finish a new unlock generation");
  h=fresh();h.workspaceOptions.keepOnLock=false;check(handoffNativeUnlock(()->hides++),"exit choice has an authenticated physical-geometry handoff");
  check(h.root.gone&&h.alpha==0&&h.lockExitPending&&h.ctx.sent.getIntExtra(LiveSwapZoomProtocol.SCALE,0)==Float.floatToIntBits(1f),"deferred exit hides Host and requests physical fullscreen, never workspace zoom");
  android.content.Intent exitAck=ack(h).putExtra(LiveSwapZoomProtocol.SCALE,Float.floatToIntBits(1f)).putExtra(LiveSwapZoomProtocol.Y,0);
  h.ctx.deliver(exitAck);check(h.suspends==0&&hides==0&&h.root.frame==null,"exit ack alone cannot release native visibility");
  Transaction.commitLatest();check(h.suspends==1&&h.root.gone&&!h.zoomed&&!h.retainedForLock&&h.parents==0&&hides==1,"committed exit cleans up behind hidden Host without a workspace reveal");
  h=fresh();h.workspaceOptions.keepOnLock=false;handoffNativeUnlock(()->hides++);EntryGuard.unlocked=false;h.main.timeout();check(h.suspends==0&&hides==0&&h.retainedForLock&&h.root.gone,"relock cannot complete a stale deferred exit");
  h=fresh();h.workspaceOptions.keepOnLock=false;handoffNativeUnlock(()->hides++);h.main.timeout();check(h.suspends==1&&h.root.gone&&hides==1,"missing physical ack falls back to normal hidden cleanup, never clipped workspace");
  h=fresh();handoffNativeUnlock(()->hides++);h.ctx.deliver(ack(h));h.workspaceOptions.keepOnLock=false;Transaction.commitLatest();check(h.root.gone&&h.lockExitPending&&h.parents==1&&hides==0&&h.unlockHandoff!=null&&h.unlockHandoff.active(),"changing to exit before SF commit switches to hidden physical handoff without revealing Host");
  h=fresh();long nativeTicket=h.unlockFinishGate.begin(7);handoffNativeUnlock(()->hides++);h.ctx.deliver(ack(h));Transaction.commitLatest();h.root.commit();
  check(hides==0&&h.unlockHandoff.active()&&!h.retainedGeometryConfirmed,"Host frame cannot unmask main while native cleanup is still pending");
  h.unlockFinishGate.committed(List.of(nativeTicket));check(hides==1&&h.unlockHandoff==null&&h.retainedGeometryConfirmed,"native cleanup commit must release the same correlated Host frame");
  h.unlockFinishGate.committed(List.of(nativeTicket));check(hides==1,"duplicate native completion cannot unmask twice");
  h=fresh();nativeTicket=h.unlockFinishGate.begin(7);h.unlockFinishGate.committed(List.of(nativeTicket));handoffNativeUnlock(()->hides++);h.ctx.deliver(ack(h));Transaction.commitLatest();h.root.commit();
  check(hides==1&&h.retainedGeometryConfirmed,"native finish before Host frame must not add a delay");
  h=fresh();nativeTicket=h.unlockFinishGate.begin(7);handoffNativeUnlock(()->hides++);h.ctx.deliver(ack(h));Transaction.commitLatest();h.root.commit();EntryGuard.unlocked=false;
  h.unlockFinishGate.committed(List.of(nativeTicket));check(hides==0&&!h.retainedGeometryConfirmed,"native finish after quick relock cannot expose main");
  h=fresh();Transaction nativeFinish=new Transaction();h.trackUnlockFinish(nativeFinish);h.trackUnlockFinish(nativeFinish);
  check(h.unlockFinishTransactions.size()==1,"repeated observer notification must reuse the finish ticket");
  handoffNativeUnlock(()->hides++);h.ctx.deliver(ack(h));Transaction.commitLatest();h.root.commit();
  h.appendUnlockFinishCommit(nativeFinish);check(hides==0,"registering a commit listener is not a native finish commit");
  Transaction.commitLatest();check(hides==1&&h.retainedGeometryConfirmed,"actual native finish listener releases the held main");
  h=fresh();nativeFinish=new Transaction();h.trackUnlockFinish(nativeFinish);Transaction mergedFinish=new Transaction();
  h.unlockFinishTransactions.merge(mergedFinish,nativeFinish);handoffNativeUnlock(()->hides++);h.ctx.deliver(ack(h));Transaction.commitLatest();h.root.commit();
  h.appendUnlockFinishCommit(nativeFinish);check(hides==0,"consumed source cannot complete merged native cleanup");
  h.appendUnlockFinishCommit(mergedFinish);Transaction.commitLatest();check(hides==1,"native finish ticket follows exact transaction merge");
  h=fresh();h.trackUnlockFinish(null);handoffNativeUnlock(()->hides++);h.ctx.deliver(ack(h));Transaction.commitLatest();h.root.commit();check(hides==1,"absent finish transaction must not create an unfinishable ticket");
  System.out.println("Production Host authenticated handoff: "+checks+" checks passed");
 }
}'''.replace('FINISH_ACTUAL','\n'.join(finish_methods)).replace('EXIT_ACTUAL',keyguard_source[helper_start:helper_end].replace('this.host', 'this').replace('host.windowPresentation.','').replace('host.sideRows.','').replace('host.zoomSession.','').replace('host.keyguard.','').replace('host.resumes.','').replace('host.','').replace('!WorkspaceHost.isCurrentInstance(host)', 'host!=sInstance').replace('WorkspaceHost.isCurrentInstance(host)', 'host==sInstance').replace('WorkspaceHost.log', 'log')).replace('ACTUAL',host_source[start:end].replace('h.keyguard.','h.').replace('h.resumes.','h.').replace('h.windowPresentation.','h.').replace('h.retainedSurfaces.','h.').replace('h.zoomSession.','h.').replace('h.sideRows.','h.'))
paths=[]
for rel,body in files.items():
 p=out/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf8');paths.append(str(p))
for name in ['UnlockHandoff','LiveSwapZoom','LiveSwapZoomProtocol','Actions','UnlockFinishGate','FinishTransactions']:paths.append(str(src/(name+'.java')))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.UnlockHandoffRegression'],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.WorkspaceHost'],check=True)
