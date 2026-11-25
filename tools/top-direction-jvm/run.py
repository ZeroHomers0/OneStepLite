"""Compile the actual direction sender; keep the shared native TaskInfo aggregation untouched."""
import ast, os, subprocess, sys
from pathlib import Path
import sys as _sys, pathlib as _pathlib; _sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1])); from source_paths import package_dir
ROOT=Path(__file__).resolve().parents[2]
OUT=Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'build/tests/top-direction-jvm'
product=Path(sys.argv[2]) if len(sys.argv)>2 else package_dir(ROOT)/'HostEntry.java'
s=product.read_text(encoding='utf8');start=s.index('    private void sendTopTaskDirection(');end=s.index('\n    /**',start)
method=s[start:end]
tree=ast.parse((ROOT/'tools/live-swap-jvm/run.py').read_text(encoding='utf-8-sig'))
shared=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='FILES' for t in n.targets))
FILES={
'de/robv/android/xposed/XposedHelpers.java':shared['de/robv/android/xposed/XposedHelpers.java'],
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long uptimeMillis(){return 1000;}}',
'android/os/Parcelable.java':'package android.os;public interface Parcelable {}',
'android/os/Handler.java':'package android.os;public class Handler {public boolean post(Runnable r){r.run();return true;}}',
'android/Manifest.java':'package android;public class Manifest {public static class permission {public static final String WRITE_SECURE_SETTINGS="secure";}}',
'android/content/Intent.java':'''package android.content;import java.util.*;public class Intent {public Map<String,Object> extras=new HashMap<>();public String action,pkg;public Intent(String a){action=a;}public Intent setPackage(String p){pkg=p;return this;}public Intent putExtra(String k,Object v){extras.put(k,v);return this;}}''',
'android/content/ComponentName.java':'package android.content;public class ComponentName {public String flattenToString(){return "pkg/Activity";}}',
'android/content/Context.java':'''package android.content;import java.util.*;public class Context {public List<Intent> sent=new ArrayList<>();public void sendBroadcast(Intent i,String permission){if(!"secure".equals(permission))throw new AssertionError("unprotected");sent.add(i);}}''',
'io/github/zerohomers0/onesteplite/DirectionRegression.java':'''package io.github.zerohomers0.onesteplite;
import java.util.*;import android.os.*;import android.content.*;import de.robv.android.xposed.XposedHelpers;
public class DirectionRegression {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static class WorkspaceHost {static final String TOP_TASK_STATE_ACTION="direction";}
 static class FullscreenRotationPolicy {static boolean requestsLandscape(int r){return r==0;}static boolean usesSensorOrUserRotation(int r){return r==2;}}
 static class Token implements Parcelable {}
 static class Remote {Token token=new Token();Object toWindowContainerToken(){return token;}}
 static Object nativeTaskInfoInProgress;
 static class Info {int userId=999;Object token=new Token();boolean isFocused;}
 static class Activity {ComponentName mActivityComponent=new ComponentName();InfoConfig config=new InfoConfig();InfoConfig getConfiguration(){return config;}}
 static class InfoConfig {int orientation=2;}
 static class Task {int mUserId=10,mCurrentUser=20,aggregations;boolean leaf=true,focused=true,upper=true,fullscreen=true;Object mRemoteToken=new Remote();
  Activity topRunningActivity(){return new Activity();}
  boolean isLeafTask(){return leaf;}boolean isFocused(){return focused;}
  Info getTaskInfo(){aggregations++;nativeTaskInfoInProgress=null;return new Info();}}
 static boolean sZoomed=true;static long topWorkspaceGeneration=1;static final String SYSTEMUI="com.android.systemui";
 static Context sSystemContext=new Context(),sWmsContext;static Handler sZoomBridgeHandler=new Handler();
 final Map<Object,String> topTaskSignals=new WeakHashMap<>();static List<String> logs=new ArrayList<>();
 boolean wideVideoFullscreen(Object t){return ((Task)t).fullscreen;}
 static boolean isUpperLandscapeTask(Object t){return ((Task)t).upper;}static void log(String s){logs.add(s);}
'''+method+'''
 void send(Task t,int mode){sendTopTaskDirection(t,100,1,0,0,mode);}
 public static void main(String[] args){DirectionRegression h=new DirectionRegression();Task t=new Task();Object outer=new Object();nativeTaskInfoInProgress=outer;
 h.send(t,1);check(t.aggregations==0,"direction sender reentered shared native TaskInfo aggregation");check(nativeTaskInfoInProgress==outer,"outer native TaskInfo was cleared");
 check(sSystemContext.sent.size()==1,"main signal missing");Intent i=sSystemContext.sent.get(0);check(i.extras.get("user").equals(10),"leaf user mismatch");check(i.extras.get("token")==((Remote)t.mRemoteToken).token,"native token mismatch");check(i.extras.get("task").equals(100),"task id changed");check(i.pkg.equals(SYSTEMUI),"receiver changed");
 check(Boolean.TRUE.equals(i.extras.get("fullscreen"))&&Boolean.TRUE.equals(i.extras.get("focused"))&&i.extras.get("mode").equals(1),"fullscreen scene payload missing");
 h.send(t,1);check(sSystemContext.sent.size()==1,"duplicate not suppressed");t.focused=false;h.send(t,1);check(sSystemContext.sent.size()==2,"native focus change lost");
 t.leaf=false;h.send(t,1);check(sSystemContext.sent.get(2).extras.get("user").equals(20),"root current user mismatch");
 int before=sSystemContext.sent.size();t.upper=false;h.send(t,6);check(sSystemContext.sent.size()==before,"non-TOP side admitted");t.upper=true;h.send(t,6);check(sSystemContext.sent.size()==before+1,"TOP signal missing");
 h.sendTopTaskDirection(t,100,0,0,0,6);check(Boolean.TRUE.equals(sSystemContext.sent.get(before+1).extras.get("landscape")),"landscape changed");
 Intent upper=sSystemContext.sent.get(before+1);
 check(upper.extras.get("request").equals(0)&&upper.extras.get("page").equals("pkg/Activity")&&upper.extras.get("viewportOrientation").equals(2),"actual page request and viewport missing");
 int last=sSystemContext.sent.size();t.fullscreen=false;h.send(t,1);check(sSystemContext.sent.size()==last+1,"late fullscreen change lost");check(Boolean.FALSE.equals(sSystemContext.sent.get(last).extras.get("fullscreen")),"fullscreen cleared signal wrong");
 sZoomed=false;h.send(t,1);check(h.topTaskSignals.isEmpty(),"exit cache not cleared");sZoomed=true;t.mRemoteToken=null;h.send(t,1);check(!logs.isEmpty(),"unavailable native token not contained");
 check(t.aggregations==0&&nativeTaskInfoInProgress==outer,"later branch reentered shared native helper");
 System.out.println("Actual direction sender: "+checks+" checks passed; shared native TaskInfo untouched");}
}
'''}
for name,body in FILES.items():
 p=OUT/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf8')
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN']);classes=OUT/'classes';classes.mkdir(exist_ok=True)
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',str(classes),*[str(OUT/n) for n in FILES]],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(classes),'io.github.zerohomers0.onesteplite.DirectionRegression'],check=True)
