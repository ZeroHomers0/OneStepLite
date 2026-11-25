"""Replay production refresh lifecycles with a deterministic queue, not a battery estimate."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src = package_dir(Path(__file__).resolve().parents[2])
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
def member(file, signature):
    text = (src / file).read_text(encoding='utf-8-sig')
    start = text.index(signature); end = text.index('{', start) + 1; depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}'); end += 1
    return text[start:end]
rail = member('TopBar.java', '    private final Runnable refreshOpenedApps =') + ';\n'
for signature in ['    void requestOpenedAppsRefresh(', '    void setWorkspaceActive(',
                  '    @Override protected void onAttachedToWindow()',
                  '    @Override protected void onDetachedFromWindow()',
                  '    @Override protected void onVisibilityChanged(',
                  '    @Override protected void onWindowVisibilityChanged(']:
    rail += member('TopBar.java', signature) + '\n'
host = member('WorkspaceHost.java', '    private void startWorkspaceHeartbeat(')
host += member('WorkspaceHost.java', '    private void setZoomState(')
body = '''package io.github.zerohomers0.onesteplite;
import java.util.*;
public class PowerRefreshRegression {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static class Queue {
  Map<Runnable,Long> pending=new LinkedHashMap<>();
  void removeCallbacks(Runnable r){pending.remove(r);}void postDelayed(Runnable r,long delay){pending.put(r,delay);}
  void run(){Runnable r=pending.keySet().iterator().next();pending.remove(r);r.run();}
 }
 static class View extends Queue {
  static final int VISIBLE=0;boolean attached=true,shown=true;int windowVisibility;
  boolean isAttachedToWindow(){return attached;}boolean isShown(){return shown;}
  int getWindowVisibility(){return windowVisibility;}int getVisibility(){return shown?0:8;}
  protected void onAttachedToWindow(){attached=true;}protected void onDetachedFromWindow(){attached=false;}
  protected void onVisibilityChanged(View v,int value){shown=value==0;}
  protected void onWindowVisibilityChanged(int value){windowVisibility=value;}
 }
 static class Rail extends View {
  boolean workspaceActive=true,dragShareEnabled=true;int reads,updates,closes;
  class Listener {Set<String> openedAppPackages(){reads++;return Set.of("main");}}
  class QuickRail {void setOpenedPackages(Set<String> p){updates++;}}
  class Picker {void close(){closes++;}}
  class ShareSurface {boolean active;void setActive(boolean value){active=value;}}
  ShareSurface shareWorkspace=new ShareSurface();
  Listener listener=new Listener();QuickRail quickRail=new QuickRail();Picker appPicker=new Picker();
__RAIL__
 }
 static class SystemClock {static long now=40000;static long uptimeMillis(){return now;}}
 static class HotZoneGesture {static boolean isAttached(){return true;}static void raiseAll(Object c){}}
 static class HostEntry {static void setHostZoomed(boolean b){}}
 static class StatusBarSampling {static void geometryChanged(){}}
 static class Host {
  static final int ACTIVE=1;static Host sInstance;int scene=ACTIVE;boolean screenOn=true,keyguardLocked,lockExitPending,zoomed=true,flip;
  View root=new View();Object layout=new Object(),ctx;Queue main=new Queue();Rail topBar=new Rail();
  class Replay {void clear(){}}Replay unlockGeometryReplay=new Replay();
  long zoomAnimUntil,lastWorkspaceRepair;Runnable workspaceHeartbeat;int carrierSlotIndex=-1,leashPocSlot=-1,writes,repairs;class Carriers{int leashPocSlot(){return -1;}}Carriers carriers=new Carriers();
  class Slot {boolean carrier,occupied=true,promoting,taskVisible=true;int index=1;}
  Slot[] slots={new Slot()};Host(){sInstance=this;}
  boolean holdsLiveFlipZoom(){return flip;}void writeZoom(boolean b){writes++;}void reassertCarrier(){}
  void ensureCellBackground(Slot s){}void reapplyScale(Slot s){repairs++;}void scheduleSideVisibilityRepair(Slot s){}
__HOST__
 }
 public static void main(String[] args){
  Rail r=new Rail();for(int i=0;i<20;i++)r.requestOpenedAppsRefresh();
  check(r.pending.size()==1&&r.pending.values().iterator().next()==100L,"task bursts coalesce before querying Binder");
  r.run();check(r.reads==1&&r.updates==1&&r.pending.values().iterator().next()==30000L,"visible rail uses a low frequency fallback");
  r.onVisibilityChanged(r,8);check(r.pending.isEmpty(),"hidden rail has no timer");
  r.onVisibilityChanged(r,0);check(r.pending.size()==1,"showing rail schedules a current snapshot");
  r.setWorkspaceActive(false);r.requestOpenedAppsRefresh();check(r.pending.isEmpty(),"locked workspace has no rail timer");
  check(!r.shareWorkspace.active,"locking also stops content subscriptions");
  r.dragShareEnabled=false;r.setWorkspaceActive(true);check(!r.shareWorkspace.active,"disabled drag share does not start on wake");
  r.dragShareEnabled=true;r.setWorkspaceActive(true);r.onWindowVisibilityChanged(8);check(r.pending.isEmpty(),"native hidden window cancels rail work");
  r.onWindowVisibilityChanged(0);r.onDetachedFromWindow();check(r.pending.isEmpty(),"detaching removes the queued runnable");
  r.onAttachedToWindow();check(r.pending.size()==1,"reattach restarts exactly once");
  Host h=new Host();h.startWorkspaceHeartbeat();h.startWorkspaceHeartbeat();
  check(h.main.pending.size()==1&&h.main.pending.values().iterator().next()==8000L,"one lease timer remains below the 15 second expiry");
  h.main.run();check(h.writes==1&&h.repairs==1,"initial recovery remains available");
  SystemClock.now+=8000;h.main.run();check(h.writes==2&&h.repairs==1,"lease renewal avoids redundant TaskView writes");
  SystemClock.now+=24000;h.main.run();check(h.repairs==2,"bounded surface recovery runs after its interval");
  h.flip=true;h.main.run();check(h.writes==3&&h.repairs==2,"live swaps keep sole transform ownership");
  h.setZoomState(false);check(h.main.pending.isEmpty()&&!h.zoomed,"exiting cancels lease work immediately");
  h.flip=false;h.setZoomState(true);check(h.main.pending.size()==1,"resume restarts maintenance");
  h.keyguardLocked=true;h.setZoomState(true);check(h.main.pending.isEmpty()&&!h.zoomed,"locked resume cannot renew zoom");
  h=new Host();h.startWorkspaceHeartbeat();h.screenOn=false;h.main.run();check(h.main.pending.isEmpty(),"screen off cannot perpetuate a timer");
  h=new Host();h.startWorkspaceHeartbeat();h.root.shown=false;h.main.run();check(h.main.pending.isEmpty(),"hidden root cannot perpetuate a timer");
  check(RoutineLog.allow("WM zoom applied scale=test",1000),"first routine message passes");
  check(!RoutineLog.allow("WM zoom applied scale=test",1001),"identical routine logs are suppressed");
  check(RoutineLog.allow("WM zoom applied scale=test",31000),"routine diagnostics remain periodically observable");
  check(RoutineLog.allow("writeZoom failed: test",1000)&&RoutineLog.allow("writeZoom failed: test",1001),"errors are never throttled");
  for(int i=0;i<200;i++)RoutineLog.allow("task window state task="+i,1000);
  check(RoutineLog.allow("WM zoom applied scale=test",31001),"diagnostic cache is bounded");
  System.out.println("Production power refresh lifecycle checks="+checks+" passed");
 }
}'''.replace('__RAIL__', rail).replace('__HOST__', host.replace('WorkspaceHost.this', 'thisHost()'))
# The production inner Runnable captures its enclosing Host, just as the Android host does.
body = body.replace('static Host sInstance;', 'Host thisHost(){return this;}static Host sInstance;')
p = out/'PowerRefreshRegression.java'; p.write_text(body, encoding='utf-8')
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(p),str(src/'RoutineLog.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.PowerRefreshRegression'],check=True)
