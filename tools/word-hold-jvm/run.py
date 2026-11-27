"""Exercise real delayed extraction and raw-pointer cancellation, without injecting touches."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=(src/'PortalSource.java').read_text(encoding='utf-8')
def member(signature):
    start=source.index(signature);end=source.index('{',start)+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
files={
 'android/os/Bundle.java':'package android.os;public class Bundle{public boolean lift;public String uri,cleanup;public boolean region;public void putBoolean(String k,boolean v){lift=v;}public String getString(String key){if("uri".equals(key))return uri;if("cleanup_uri".equals(key))return cleanup;return null;}public boolean getBoolean(String key){return region;}}',
 'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long now=1100;public static long uptimeMillis(){return now;}}',
 'android/widget/Toast.java':'package android.widget;public class Toast{public static int shown;public static final int LENGTH_SHORT=0;public static Toast makeText(Object c,String s,int d){return new Toast();}public void show(){shown++;}}',
 'android/view/InputDevice.java':'package android.view;public class InputDevice{public static final int SOURCE_TOUCHSCREEN=4098;}',
 'android/view/ViewConfiguration.java':'package android.view;public class ViewConfiguration{public static ViewConfiguration get(Object c){return new ViewConfiguration();}public static int getLongPressTimeout(){return 500;}public int getScaledTouchSlop(){return 8;}}',
 'io/github/zerohomers0/onesteplite/WordHoldRegression.java':'''package io.github.zerohomers0.onesteplite;
import java.util.*;import java.util.function.*;import android.os.Bundle;import java.util.concurrent.CountDownLatch;import java.util.concurrent.atomic.AtomicBoolean;
class ShareDiagnostics{enum Stage{DRAG,IMAGE_LIFT}enum Code{FAILED,CANCELLED}static void event(Stage stage,Code code){}}
class MotionEvent{
 static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_UP=6;
 int action,count=1;long down=1000;float x=50,y=50;boolean recycled;
 MotionEvent(int action){this.action=action;}
 int getActionMasked(){return action;}int getPointerCount(){return count;}
 int getDeviceId(){return 4;}int getPointerId(int index){return 0;}
 long getDownTime(){return down;}float getRawX(){return x;}float getRawY(){return y;}
 boolean isFromSource(int source){return true;}void recycle(){recycled=true;}
 static MotionEvent obtainNoHistory(MotionEvent e){MotionEvent c=new MotionEvent(e.action);c.count=e.count;c.down=e.down;c.x=e.x;c.y=e.y;return c;}
}
public class WordHoldRegression{
 static class Handler{Map<Runnable,Long> queue=new LinkedHashMap<>();void postDelayed(Runnable r,long ms){queue.put(r,ms);}
 void removeCallbacks(Runnable r){queue.remove(r);}void drain(){var copy=new ArrayList<>(queue.keySet());queue.clear();for(Runnable r:copy)r.run();}}
 static class Anchor{Object getContext(){return this;}}
 Object window,selection;MotionEvent pointer;Runnable pendingWords,fallbackJob;float wordX,wordY,fallbackX,fallbackY;boolean validGesture,fallbackIssued,selectingDrag;
 Bundle liftCandidate;float downX,downY;long liftAt;boolean liftEligible,startingDrag;
 AtomicBoolean windowRequest;
 String nativeCaptureId,nativeCleanupUri;int discardedCaptures,discardedPublications;void discardCapture(String id){if(id!=null)discardedCaptures++;}void discardPublication(String uri){if(uri!=null)discardedPublications++;}
 int discarded;void clearLiftCandidate(){if(liftCandidate!=null)discarded++;liftCandidate=null;}
 int lifts;void offer(Bundle request,Bundle[] result,CountDownLatch done,AtomicBoolean expired){if(request.lift)lifts++;}
 void clearWindow(){window=null;startingDrag=false;windowRequest=null;validGesture=false;}
 static class Capture{
 boolean deferred;java.util.function.Consumer<String> result;
 void close(){}
 void extractText(java.util.function.Consumer<String> callback){if(deferred)result=callback;else callback.accept("screen OCR");}
 }Capture fallbackCapture=new Capture();
 Handler main=new Handler();Anchor anchor=new Anchor();int generation,opens;boolean active=true;
 BooleanSupplier ready=()->active;boolean live(){return pointer!=null&&validGesture&&active;}
 boolean openWords(String text){opens++;return true;}
 __METHODS__
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static WordHoldRegression fresh(){android.os.SystemClock.now=1100;var h=new WordHoldRegression();h.motion(new MotionEvent(0));return h;}
 static WordHoldRegression twoFingers(){var h=fresh();MotionEvent event=new MotionEvent(2);event.count=2;h.motion(event);return h;}
 public static void main(String[] args){
 var h=fresh();check(!h.deferWords("selected")&&h.opens==0,"single-finger extraction uses the lift candidate path");
 h=twoFingers();check(h.deferWords("selected"),"live two-finger text accepted");
 check(h.deferWords("duplicate")&&h.main.queue.size()==1,"duplicate OEM paths share one pending selection");
 android.os.SystemClock.now=4000;h.main.drain();check(h.opens==1,"held gesture opens once");
 h=twoFingers();h.deferWords("cancelled");h.motion(new MotionEvent(1));h.main.drain();check(h.opens==0&&h.pointer==null,"release before the selection callback cancels");
 h=twoFingers();h.deferWords("cancelled");MotionEvent move=new MotionEvent(2);move.count=2;move.x=80;h.motion(move);h.main.drain();check(h.opens==0,"moving into another menu cancels");
 h=twoFingers();h.deferWords("old scene");h.generation++;h.main.drain();check(h.opens==0,"old scene never opens late");
 h=twoFingers();h.deferWords("locked");h.active=false;h.main.drain();check(h.opens==0,"hidden or locked workspace rejects delayed callback");
 h=fresh();MotionEvent two=new MotionEvent(2);two.count=2;h.motion(two);h.deferWords("explicit");check(h.main.queue.values().iterator().next()==0,"explicit two-finger extraction retains immediate access");h.main.drain();check(h.opens==1,"two-finger selection opens once");
 h=twoFingers();h.deferWords("cancelled");h.motion(new MotionEvent(3));h.main.drain();check(h.opens==0,"native input cancellation retires timer");
 h=twoFingers();h.selection=new Object();check(!h.deferWords("other"),"existing selection is not replaced");
 h=fresh();two=new MotionEvent(2);two.count=2;h.motion(two);check(h.fallbackJob!=null,"stable two-finger hold arms screenshot OCR fallback");h.main.drain();check(h.opens==1,"fallback opens local selection without OEM offer");
 h=fresh();two=new MotionEvent(2);two.count=2;h.motion(two);h.motion(new MotionEvent(1));h.main.drain();check(h.opens==0,"release cancels unarmed screenshot request");
 h=fresh();two=new MotionEvent(2);two.count=2;h.motion(two);move=new MotionEvent(2);move.count=2;move.x=90;h.motion(move);h.main.drain();check(h.opens==0,"pinch or scroll movement cancels screenshot fallback");
 h=fresh();two=new MotionEvent(2);two.count=2;h.motion(two);MotionEvent lift=new MotionEvent(6);lift.count=2;h.motion(lift);h.main.drain();check(h.opens==0,"lifting either finger cancels fallback even without another MOVE");
 h=fresh();h.liftCandidate=new Bundle();h.motion(new MotionEvent(1));check(h.liftCandidate==null&&h.lifts==0&&h.discarded==1,"stationary release discards exactly once");
 h=fresh();h.liftCandidate=new Bundle();move=new MotionEvent(2);move.x=90;h.motion(move);check(h.lifts==0&&h.liftCandidate==null,"ordinary scrolling before long press never initiates drag");
 h=fresh();h.liftCandidate=new Bundle();android.os.SystemClock.now=1800;move=new MotionEvent(2);move.x=90;h.motion(move);check(h.lifts==1&&h.discarded==0,"long press movement transfers the candidate without discarding it");
 h=fresh();h.liftCandidate=new Bundle();android.os.SystemClock.now=1800;move=new MotionEvent(2);move.x=61;
 h.motion(move);check(h.lifts==0&&h.liftCandidate!=null,"small post-hold movement leaves the app menu alone");
 move.x=63;h.motion(move);check(h.lifts==1,"movement beyond one and a half touch slops starts a lift");
 h=fresh();h.liftCandidate=new Bundle();h.liftCandidate.uri="content://image";android.os.SystemClock.now=1800;
 move=new MotionEvent(2);move.x=61;h.motion(move);
 check(h.lifts==1,"an image still uses the original movement threshold");
 h=fresh();h.liftCandidate=new Bundle();h.liftCandidate.region=true;android.os.SystemClock.now=1800;
 move=new MotionEvent(2);move.x=61;h.motion(move);
 check(h.lifts==1,"a region fallback uses the same threshold as a direct image");
 h=fresh();h.liftCandidate=new Bundle();android.os.SystemClock.now=1550;
 move=new MotionEvent(2);move.x=90;h.motion(move);check(h.lifts==0&&h.liftCandidate!=null,"movement during the system-menu grace period retains the candidate without lifting");
 android.os.SystemClock.now=1660;h.motion(move);check(h.lifts==1&&h.liftCandidate==null,"continued movement after the grace period starts one drag");
 h=fresh();h.liftCandidate=new Bundle();android.os.SystemClock.now=1550;
 h.motion(move);h.motion(new MotionEvent(1));check(h.lifts==0&&h.liftCandidate==null,"release during the grace period cancels pending lift");
 h=fresh();h.liftCandidate=new Bundle();h.motion(new MotionEvent(0));check(h.liftCandidate==null,"new DOWN cannot reuse an earlier gesture's content");
 h=fresh();int previous=h.generation;h.motion(new MotionEvent(0));check(h.generation!=previous,"a new press invalidates preceding extraction offers");
 h=fresh();h.selection=new Object();two=new MotionEvent(2);two.count=2;h.motion(two);check(h.fallbackJob==null,"word selection owns its touch stream");
 h=fresh();android.os.SystemClock.now=1800;move=new MotionEvent(2);move.x=90;h.motion(move);
 h.liftCandidate=new Bundle();h.moveLiftCandidate(h.pointer);
 check(h.lifts==1,"a late text or image can use movement already observed after the hold");
 h=fresh();h.motion(move);check(!h.liftEligible,"scrolling before long press invalidates a later content offer");
 h=fresh();h.window=new Object();h.startingDrag=true;h.motion(new MotionEvent(1));
 check(h.window==null&&!h.startingDrag&&h.pointer==null,"release retires a source waiting for its first frame");
 h=fresh();h.window=new Object();h.startingDrag=true;h.motion(move);
 check(h.pointer.getRawX()==move.x && h.startingDrag,"native start uses the latest point of the same press");
 h=fresh();h.liftCandidate=new Bundle();h.liftAt=1900;
 android.os.SystemClock.now=1800;h.motion(move);check(h.lifts==0,"fallback waits briefly for a preferred image");
 android.os.SystemClock.now=1900;h.moveLiftCandidate(h.pointer);check(h.lifts==1,"fallback lifts without another move once its wait ends");
 h=fresh();h.fallbackCapture.deferred=true;two=new MotionEvent(2);two.count=2;
 h.motion(two);h.main.drain();h.motion(new MotionEvent(0));h.fallbackCapture.result.accept("old OCR");
 check(h.opens==0,"OCR from a previous press cannot open word selection");
 h=fresh();h.window=new Object();h.finishRejectedLift(true);
 check(h.window==null&&h.opens==0&&android.widget.Toast.shown==1,"rejected single-finger lift reports failure without opening words or preview");
 h.finishRejectedLift(false);check(android.widget.Toast.shown==1,"cancelled input does not show a retry prompt");
 h.nativeCaptureId="capture";h.nativeCleanupUri="content://media/external/images/media/1";h.finishRejectedLift(false);
 check(h.nativeCaptureId==null&&h.nativeCleanupUri==null&&h.discardedCaptures==1&&h.discardedPublications==1,"a failed image lift releases private capture and published media");
 h=fresh();h.window=new Object();h.startingDrag=true;h.windowRequest=new AtomicBoolean();
 h.clearPendingWindow(new AtomicBoolean(true));
 check(h.window!=null,"an unrelated IPC timeout cannot cancel a pending native drag");
 h.clearPendingWindow(h.windowRequest);
 check(h.window==null,"an IPC timeout can retire its own pending window");
 h=fresh();h.window=new Object();h.windowRequest=new AtomicBoolean(true);
 h.clearPendingWindow(h.windowRequest);
 check(h.window!=null,"an IPC timeout cannot clear a drag that already started");
 System.out.println("Production word hold cancellation checks="+checks+" passed");
 }
}
'''.replace('__METHODS__',member('    private void motion(')+'\n'+member('    private void moveLiftCandidate(')+'\n'+member('    private boolean deferWords(')+'\n'+member('    private void finishRejectedLift(')+'\n'+member('    private void clearPendingWindow('))}
paths=[]
for name,text in files.items():
    path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8');paths.append(str(path))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.WordHoldRegression'],check=True)
