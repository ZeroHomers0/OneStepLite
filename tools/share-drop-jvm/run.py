"""Replay a DROP after the share icon was selected, including a late coordinate miss."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=(src/'ShareWorkspace.java').read_text(encoding='utf-8-sig')
start=source.index('    private boolean drag(');end=source.index('{',start)+1;depth=1
while depth:
    depth+=(source[end]=='{')-(source[end]=='}');end+=1
drag=source[start:end]
drop_start=source.index('    private boolean drop(');drop_end=source.index('{',drop_start)+1;depth=1
while depth:
    depth+=(source[drop_end]=='{')-(source[drop_end]=='}');drop_end+=1
drop_method=source[drop_start:drop_end]
if 'queryIntentActivities(intent,0).isEmpty()' in drop_method:
    raise AssertionError('DROP must not silently reject after the target icon was selected')
java=r'''package io.github.zerohomers0.onesteplite;
import java.util.*;
import java.util.function.*;
public class ShareDropRegression {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static final class DragEvent {static final int ACTION_DRAG_STARTED=1,ACTION_DRAG_LOCATION=2,ACTION_DROP=3,ACTION_DRAG_ENDED=4,ACTION_DRAG_EXITED=5;
  final int action;Object local;ClipDescription description=new ClipDescription();boolean result;DragEvent(int action){this.action=action;}
  int getAction(){return action;}Object getLocalState(){return local;}ClipDescription getClipDescription(){return description;}boolean getResult(){return result;}}
 static final class ClipDescription {final Map<String,Object> extras=new HashMap<>();String mime="text/plain";
  boolean hasMimeType(String type){return type.equals(mime);}Extras getExtras(){return null;}int getMimeTypeCount(){return 1;}String getMimeType(int i){return mime;}ClipDescription(){}ClipDescription(ClipDescription other){}ClipDescription(String label,String[] types){mime=types[0];}static final class Extras{int getInt(String key,int fallback){return fallback;}String getString(String key){return null;}}}
 static class View {}
 static final class ComponentName {final String pkg;ComponentName(String pkg){this.pkg=pkg;}public boolean equals(Object o){return o instanceof ComponentName&&pkg.equals(((ComponentName)o).pkg);}public int hashCode(){return pkg.hashCode();}}
 static final class ClipData {}
 static final class SharePayload {final String mime="text/plain";final List<Object> uris=new ArrayList<>();SharePayload(ClipData c){}}
 static final class ShareTarget {}
 static final class ShareRailOverlay {boolean isOpen(){return false;}}
 static final class Portal {void dragStarted(){}}
 static final class Panel {boolean isDragging(){return false;}void dragEnded(boolean consumed){}}
 static final class Handler {final List<Runnable> delayed=new ArrayList<>();void post(Runnable r){r.run();}void postDelayed(Runnable r,long delay){delayed.add(r);}void removeCallbacks(Runnable r){delayed.removeIf(x->x==r);}void runDelayed(){List<Runnable> copy=new ArrayList<>(delayed);delayed.clear();for(Runnable r:copy)r.run();}}
 static final class Worker {void execute(Runnable r){}}
 static final class ShareDragCatchment {ShareDragCatchment(Object owner,int offset,BooleanSupplier live){}void start(){}}
 static final class CurrentUser {static int id(){return 0;}}
 static final class ShareDiagnostics {enum Stage{DRAG}enum Code{REJECTED}static void event(Stage s,Code c){}}
 static final class ContentAccessCompat {static boolean currentProcessUser(){return true;}static int arrowOffset(DragEvent e){return 0;}static float shadowHalfWidth(DragEvent e){return 0;}}
 static final class ContentDragMotion {static void managed(Object owner,ClipDescription description){}static void close(){}}
 static final class ShareTargetCatalog {record Matches(Set<String> packages){}static Matches queryMatches(Object c,ClipDescription d,int n){return new Matches(Set.of());}static List<ShareTarget> loadForDrag(Object c,ClipDescription d,int n,Matches matches){return List.of();}}
 static final class Replay extends View {
  BooleanSupplier ready=()->true,covered=()->false;ShareRailOverlay overlay;boolean shareOnly,dragging,dropped,panelSource,imageDrag,hasPointer;Object transferClip;
  ComponentName hovered;Set<String> windowPackages;String sourcePackage;Consumer<String> hint=value->{};Portal portal=new Portal();Runnable expire=()->{};
  Panel panel=new Panel();static Handler handler=new Handler();static final Worker WORKER=new Worker();ShareDragCatchment catchment;int tipOffset,generation,user,drops,rejections;float dragHalfWidth;
  Runnable afterDrag=()->{},dragFinished=()->{};String lastMessage;AutoCloseableFixture windowHover=new AutoCloseableFixture();
  static final class AutoCloseableFixture {void close(){}}
  boolean accepts(ClipDescription description){return true;}boolean live(int token){return dragging;}boolean onWindowDrag(DragEvent e,Object target,boolean covered,View origin){return true;}
  void cancel(){dragging=false;}void locateDragTip(DragEvent event,View plate){hovered=null;}
  boolean drop(DragEvent event,ComponentName target){drops++;dropped=true;return true;}
  void showTargets(List<ShareTarget> entries){}void message(String value){lastMessage=value;}
  void finishVisuals(){dragging=false;}void stopEdge(){}void clearTargetHover(){}
  Replay getContext(){return this;}void post(Runnable r){r.run();}boolean performHapticFeedback(int kind){return true;}
__DRAG__
 }
 public static void main(String[] args){
  Replay selected=new Replay();selected.dragging=true;selected.hovered=new ComponentName("chat");
  check(selected.drag(new DragEvent(DragEvent.ACTION_DROP),null)&&selected.drops==1,
      "DROP commits the icon that was visibly selected even if final hit conversion misses");
  Replay missing=new Replay();missing.dragging=true;
  check(!missing.drag(new DragEvent(DragEvent.ACTION_DROP),null)&&missing.drops==0&&missing.lastMessage!=null,
      "DROP without any selected icon explains why it was rejected");
  Replay ordered=new Replay();ordered.dragging=true;ordered.hovered=new ComponentName("chat");
  ordered.catchment=new ShareDragCatchment(ordered,300,()->true);ordered.handler.delayed.clear();
  DragEvent ended=new DragEvent(DragEvent.ACTION_DRAG_ENDED);ended.result=false;
  check(ordered.drag(ended,null)&&ordered.dragging&&ordered.handler.delayed.size()==1,
      "underlying rail defers ENDED until a possibly later catchment DROP");
  ordered.hovered=new ComponentName("chat");
  check(ordered.drag(new DragEvent(DragEvent.ACTION_DROP),new View())&&ordered.drops==1,
      "late catchment DROP still commits before deferred retirement");
  ordered.handler.runDelayed();
  check(!ordered.dragging,"deferred retirement completes after the late DROP");
  Replay crossing=new Replay();crossing.dragging=true;crossing.hovered=new ComponentName("chat");
  crossing.catchment=new ShareDragCatchment(crossing,300,()->true);
  crossing.drag(new DragEvent(DragEvent.ACTION_DRAG_EXITED),null);
  check(crossing.hovered!=null,"rail EXIT into the overlapping catchment keeps the selected target");
  Replay newer=new Replay();newer.dragging=true;newer.drag(ended,null);newer.generation++;
  newer.handler.runDelayed();check(newer.dragging,"previous drag end cannot retire a newer drag");
  System.out.println("Production share DROP checks="+checks+" passed");
}}
'''.replace('__DRAG__',drag)
path=out/'ShareDropRegression.java';path.write_text(java,'utf-8')
haptic=out/'android/view/HapticFeedbackConstants.java';haptic.parent.mkdir(parents=True,exist_ok=True)
haptic.write_text('package android.view;public final class HapticFeedbackConstants{public static final int DRAG_START=0;}\n','utf-8')
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(path),str(haptic)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.ShareDropRegression'],check=True)
