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
 'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long now=1100;public static long uptimeMillis(){return now;}}',
 'android/view/InputDevice.java':'package android.view;public class InputDevice{public static final int SOURCE_TOUCHSCREEN=4098;}',
 'android/view/ViewConfiguration.java':'package android.view;public class ViewConfiguration{public static ViewConfiguration get(Object c){return new ViewConfiguration();}public int getScaledTouchSlop(){return 8;}}',
 'io/github/zerohomers0/onesteplite/WordHoldRegression.java':'''package io.github.zerohomers0.onesteplite;
import java.util.*;import java.util.function.*;
class MotionEvent{
 static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;
 int action,count=1;long down=1000;float x=50,y=50;boolean recycled;
 MotionEvent(int action){this.action=action;}
 int getActionMasked(){return action;}int getPointerCount(){return count;}
 long getDownTime(){return down;}float getRawX(){return x;}float getRawY(){return y;}
 boolean isFromSource(int source){return true;}void recycle(){recycled=true;}
 static MotionEvent obtainNoHistory(MotionEvent e){MotionEvent c=new MotionEvent(e.action);c.count=e.count;c.down=e.down;c.x=e.x;c.y=e.y;return c;}
}
public class WordHoldRegression{
 static class Handler{Map<Runnable,Long> queue=new LinkedHashMap<>();void postDelayed(Runnable r,long ms){queue.put(r,ms);}
 void removeCallbacks(Runnable r){queue.remove(r);}void drain(){var copy=new ArrayList<>(queue.keySet());queue.clear();for(Runnable r:copy)r.run();}}
 static class Anchor{Object getContext(){return this;}}
 Object window,selection;MotionEvent pointer;Runnable pendingWords;float wordX,wordY;boolean validGesture;
 Handler main=new Handler();Anchor anchor=new Anchor();int generation,opens,wordPointers;boolean active=true;
 BooleanSupplier ready=()->active;boolean live(){return pointer!=null&&validGesture&&active;}
 boolean openWords(String text){opens++;return true;}
 __METHODS__
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static WordHoldRegression fresh(){android.os.SystemClock.now=1100;var h=new WordHoldRegression();h.motion(new MotionEvent(0));return h;}
 public static void main(String[] args){
 var h=fresh();check(h.deferWords("selected"),"live text accepted");check(h.opens==0&&h.main.queue.values().iterator().next()==2900,"single-finger extraction waits until three seconds");
 check(h.deferWords("duplicate")&&h.main.queue.size()==1,"duplicate OEM paths share one pending selection");
 android.os.SystemClock.now=4000;h.main.drain();check(h.opens==1,"held gesture opens once");
 h=fresh();h.deferWords("cancelled");h.motion(new MotionEvent(1));h.main.drain();check(h.opens==0&&h.pointer==null,"release before three seconds cancels");
 h=fresh();h.deferWords("cancelled");MotionEvent move=new MotionEvent(2);move.x=80;h.motion(move);h.main.drain();check(h.opens==0,"moving into another menu cancels");
 h=fresh();h.deferWords("old scene");h.generation++;h.main.drain();check(h.opens==0,"old scene never opens late");
 h=fresh();h.deferWords("locked");h.active=false;h.main.drain();check(h.opens==0,"hidden or locked workspace rejects delayed callback");
 h=fresh();MotionEvent two=new MotionEvent(2);two.count=2;h.motion(two);h.deferWords("explicit");check(h.main.queue.values().iterator().next()==0,"explicit two-finger extraction retains immediate access");h.main.drain();check(h.opens==1,"two-finger selection opens once");
 h=fresh();h.deferWords("cancelled");h.motion(new MotionEvent(3));h.main.drain();check(h.opens==0,"native input cancellation retires timer");
 h=fresh();h.selection=new Object();check(!h.deferWords("other"),"existing selection is not replaced");
 System.out.println("Production word hold cancellation checks="+checks+" passed");
 }
}
'''.replace('__METHODS__',member('    private void motion(')+'\n'+member('    private boolean deferWords('))}
paths=[]
for name,text in files.items():
    path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8');paths.append(str(path))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.WordHoldRegression'],check=True)
