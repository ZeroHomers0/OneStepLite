"""Replay the detached header cache; no device rendering claim."""
from pathlib import Path
import ast, os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
tree=ast.parse((root/'tools/taskview-warmup-jvm/run.py').read_text(encoding='utf-8-sig'))
shared=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='files' for t in n.targets))
files={k:v for k,v in shared.items() if k.startswith('android/') or k.endswith('/EntryGuard.java') or k.endswith('/CurrentUser.java')}
files['android/content/SharedPreferences.java']=files['android/content/SharedPreferences.java'].replace('public Editor edit()', 'public boolean getBoolean(String k,boolean fallback){return fallback;}public Editor edit()')
files['android/content/res/Configuration.java']=files['android/content/res/Configuration.java'].replace('public int orientation=', 'public String language="zh-CN";public android.os.LocaleList getLocales(){return new android.os.LocaleList(language);}public int orientation=')
files['android/content/res/Resources.java']=files['android/content/res/Resources.java'].replace('public Configuration getConfiguration()', 'public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}public Configuration getConfiguration()')
files['android/view/Display.java']=files['android/view/Display.java'].replace('public static final', 'public void getRealSize(android.graphics.Point p){p.x=1220;p.y=2656;}public static final')
files.update({
'android/content/MutableContextWrapper.java':'package android.content;public class MutableContextWrapper extends Context {public Context base;public MutableContextWrapper(Context c){base=c;}public void setBaseContext(Context c){base=c;}public android.content.res.Resources getResources(){return base.getResources();}}',
'android/os/LocaleList.java':'package android.os;public class LocaleList {String value;public LocaleList(String s){value=s;}public String toLanguageTags(){return value;}}',
'android/util/DisplayMetrics.java':'package android.util;public class DisplayMetrics {public float density=3;}',
'android/graphics/Point.java':'package android.graphics;public class Point {public int x,y;}',
'android/graphics/Bitmap.java':'''package android.graphics;public class Bitmap {public enum Config{ARGB_8888}public static Bitmap last;public int width,height;public boolean recycled;public static Bitmap createBitmap(int w,int h,Config c){last=new Bitmap();last.width=w;last.height=h;return last;}public void recycle(){recycled=true;}}''',
'android/graphics/Canvas.java':'package android.graphics;public class Canvas {public Canvas(Bitmap b){}public void scale(float x,float y){}}',
'android/view/View.java':'package android.view;public class View {public static class MeasureSpec {public static final int EXACTLY=1;public static int makeMeasureSpec(int n,int mode){return n;}}}',
'io/github/zerohomers0/onesteplite/QuickApp.java':'package io.github.zerohomers0.onesteplite;class QuickApp {}',
'io/github/zerohomers0/onesteplite/DeviceProfile.java':'package io.github.zerohomers0.onesteplite;class DeviceProfile {static boolean isTablet(Object c){return false;}static int statusBarHeightPx(Object c){return 40;}}',
'io/github/zerohomers0/onesteplite/TopBar.java':'''package io.github.zerohomers0.onesteplite;
class TopBar {interface Listener {void onOpenSettings();void onSetMainSide(boolean b);void onExit();void onLaunch(QuickApp a);}
static int creates,draws;static boolean failDraw;static TopBar last;android.content.Context context;Listener listener;boolean active=true,right;int apps;WorkspaceOptions options;
TopBar(android.content.Context c,WorkspaceLayout l,java.util.List<QuickApp>a,Listener owner){last=this;context=c;creates++;listener=owner;apps=a.size();}
void setWorkspaceActive(boolean b){active=b;}void applyOptions(WorkspaceOptions o){options=o;}void setStatusBarInset(int n){}
void measure(int w,int h){}void layout(int a,int b,int c,int d){}void draw(android.graphics.Canvas c){draws++;if(failDraw)throw new IllegalStateException();}
void bindListener(Listener owner){listener=owner;}void setMainOnRight(boolean b){right=b;}
}''',
'io/github/zerohomers0/onesteplite/ChromeWarmupRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.content.Context;import android.graphics.Bitmap;import java.util.*;
public class ChromeWarmupRegression {
static int checks,exits;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
static List<QuickApp> apps=List.of(new QuickApp(),new QuickApp());
static WorkspaceLayout layout(boolean right){return WorkspaceLayout.of(1220,2656,right,3,false);}
static final TopBar.Listener OWNER=new TopBar.Listener(){public void onOpenSettings(){}public void onSetMainSide(boolean b){}public void onExit(){exits++;}public void onLaunch(QuickApp a){}};
static Context fresh(){ChromeWarmup.invalidate(true);EntryGuard.open=true;CurrentUser.user=0;return new Context();}
static void prepare(Context c){ChromeWarmup.prepare(c,0,apps,WorkspaceOptions.DEFAULT);}
public static void main(String[]args){
 Context c=fresh();prepare(c);TopBar idle=TopBar.last;
 check(!idle.active&&idle.apps==2&&idle.options==WorkspaceOptions.DEFAULT,"precreated header has content and disables refreshes before attaching");
 check(Bitmap.last.recycled&&Bitmap.last.width<=512&&Bitmap.last.height<=512,"one software frame uses a bounded, released buffer");
 int count=TopBar.creates,draws=TopBar.draws;for(int i=0;i<20;i++)prepare(c);
 check(TopBar.creates==count&&TopBar.draws==draws,"completed header preparation has no repeated rendering");
 TopBar ready=ChromeWarmup.take(c,layout(true),OWNER);
 check(ready==idle&&ready.active&&ready.right&&ready.listener==OWNER,"first caller reuses layout with current side and listener");
 check(((android.content.MutableContextWrapper)ready.context).base==c,"reused widgets must bind to the real host window context");
 ready.listener.onExit();check(exits==1,"cached controls belong to the current host");
 check(ChromeWarmup.take(c,layout(true),OWNER)==null,"cache transfers ownership exactly once");
 prepare(c);check(TopBar.creates==count&&!ChromeWarmup.needsPreparation(),"cache is not replenished after first demand");
 for(int gate=0;gate<6;gate++){
  c=fresh();prepare(c);WorkspaceLayout target=layout(false);
  switch(gate){case 0:c.resources.config.fontScale=1.5f;break;case 1:c.resources.config.uiMode=2;break;case 2:c.resources.config.language="en-US";break;case 3:c.resources.config.densityDpi++;break;case 4:CurrentUser.user++;break;case 5:target=WorkspaceLayout.of(1080,2400,false,3,false);break;}
  check(ChromeWarmup.take(c,target,OWNER)==null,"incompatible header must use fresh construction gate="+gate);
 }
 c=fresh();EntryGuard.open=false;count=TopBar.creates;prepare(c);check(TopBar.creates==count,"locked device cannot precreate chrome");
 EntryGuard.open=true;prepare(c);check(TopBar.creates==count+1,"unlock allows a single preparation");
 ChromeWarmup.invalidate(false);prepare(c);check(TopBar.creates==count+2,"package or configuration invalidation replaces unclaimed chrome");
 c=fresh();count=TopBar.creates;CurrentUser.user=10;prepare(c);check(TopBar.creates==count,"stale user worker result is discarded");
 c=fresh();TopBar.failDraw=true;prepare(c);check(Bitmap.last.recycled&&ChromeWarmup.take(c,layout(false),OWNER)!=null,"unsupported software icon drawing releases its buffer and keeps the measured header");
 System.out.println("Production detached header warmup checks="+checks+" passed");
}
}'''
})
paths=[]
files['android/os/MessageQueue.java']='''package android.os;public class MessageQueue {
 public interface IdleHandler{boolean queueIdle();}public static java.util.List<IdleHandler> handlers=new java.util.ArrayList<>();
 public void addIdleHandler(IdleHandler h){handlers.add(h);}public static void drainIdle(){for(var h:new java.util.ArrayList<>(handlers))if(!h.queueIdle())handlers.remove(h);}
}'''
files['android/os/Looper.java']=files['android/os/Looper.java'].replace('public static Looper', 'public MessageQueue getQueue(){return new MessageQueue();}public static Looper')
files['android/os/Handler.java']=files['android/os/Handler.java'].replace('public void post(', 'public Looper getLooper(){return Looper.getMainLooper();}public void post(')
source=(src/'Warmup.java').read_text(encoding='utf-8-sig')
start=source.index('    private static void whenUiIdle(');end=source.index('{',start)+1;depth=1
while depth:
 depth+=(source[end]=='{')-(source[end]=='}');end+=1
name='io/github/zerohomers0/onesteplite/ChromeWarmupRegression.java'
files[name]=files[name].replace('public static void main(String[]args){',
 'static final android.os.Handler MAIN=new android.os.Handler(android.os.Looper.getMainLooper());\n'+source[start:end]+'''\npublic static void main(String[]args){
 final int[] idleRuns={0};whenUiIdle(()->idleRuns[0]++);android.os.Handler.drain();
 check(idleRuns[0]==0&&android.os.MessageQueue.handlers.size()==1,"boot UI work waits for an idle queue");
 android.os.MessageQueue.drainIdle();check(idleRuns[0]==1&&android.os.MessageQueue.handlers.isEmpty(),"idle preparation removes itself after one run");
 android.os.MessageQueue.drainIdle();check(idleRuns[0]==1,"idle preparation cannot become a render loop");
''')
for name,text in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths,str(src/'ChromeWarmup.java'),str(src/'WorkspaceLayout.java'),str(src/'WorkspaceOptions.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.ChromeWarmupRegression'],check=True)

# The cache test above substitutes widgets. Also replay the REAL Header
# constructor/button callbacks so a captured prewarm listener cannot pass it.
header_source=(src/'TopBar.java').read_text(encoding='utf-8-sig')
def header_member(signature):
 start=header_source.index(signature);end=header_source.index('{',start)+1;depth=1
 while depth:
  depth+=(header_source[end]=='{')-(header_source[end]=='}');end+=1
 return header_source[start:end]
actions=Path(__file__).with_name('HeaderActionsRegression.java').read_text(encoding='utf-8')
actions=actions.replace('__BIND__',header_member('    void bindListener(').replace('Listener owner','Owner owner'))
actions=actions.replace('__CONSTRUCTOR__',header_member('        Header(Context context,Runnable tools)'))
actions=actions.replace('__BUTTON__',header_member('        private IconButton button('))
action_files={
 'HeaderActionsRegression.java':actions,
 'android/view/Gravity.java':'package android.view;public class Gravity {public static final int CENTER=17;}',
 'android/graphics/Typeface.java':'package android.graphics;public class Typeface {public static Object create(String name,int style){return null;}}',
}
action_paths=[]
for name,text in action_files.items():
 p=out/'actions'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');action_paths.append(str(p))
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out/'actions'),*action_paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out/'actions'),'io.github.zerohomers0.onesteplite.HeaderActionsRegression'],check=True)
