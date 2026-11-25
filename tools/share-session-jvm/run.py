"""Replay production share routing and detached delivery lifecycle, including stale callbacks."""
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir

root = Path(__file__).resolve().parents[2]
src = package_dir(root)
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
source = (src / 'ShareWorkspace.java').read_text(encoding='utf-8-sig')

def member(signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

methods = '\n'.join(member(signature) for signature in [
    '    boolean onWindowDrag(', '    private void finishVisuals(', '    private void clearTargetHover(', '    void cancel(',
    '    boolean retainDelivery(', '    private void finishDelivery(',
    '    @Override protected void onDetachedFromWindow()', '    void close()'])
files = {
'ShareSessionRegression.java': '''package io.github.zerohomers0.onesteplite;
import java.util.Set;
import java.util.function.*;
public class ShareSessionRegression {
static int checks;static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
static final class DragEvent {static final int ACTION_DRAG_STARTED=1,ACTION_DRAG_LOCATION=2,ACTION_DROP=3,ACTION_DRAG_ENDED=4,ACTION_DRAG_EXITED=5;
int action;DragEvent(int value){action=value;}int getAction(){return action;}Object getClipDescription(){return this;}}
static final class ComponentName {String pkg;ComponentName(String p,String name){pkg=p;}}
static class Counter implements AutoCloseable {int closes,stops,restores;void dragStarted(){}public void close(){closes++;}void stop(){stops++;}boolean isDragging(){return false;}void dragEnded(boolean consumed){restores++;}}
static class Base {int detaches;protected void onDetachedFromWindow(){detaches++;}}
static class View {}
static class ShareTargetButton extends View {boolean hovered=true;void setDragHovered(boolean value){hovered=value;}}
static class Targets {ShareTargetButton child=new ShareTargetButton();int getChildCount(){return 1;}View getChildAt(int i){return child;}}
static class Handler {int removals;void removeCallbacks(Runnable r){removals++;}}
static class Transition {int resets,returns;void show(boolean value){if(!value)returns++;}void reset(){resets++;}}
static class ShareRailOverlay {boolean open;int events,closes;String target;
ShareRailOverlay(Object view,BooleanSupplier ready,Object dispatch,Object hint,Object bounds){}
boolean isOpen(){return open;}boolean onDrag(DragEvent event,String name){events++;target=name;open=event.action!=4;return true;}
void setWindowHover(Function<String,String> identity,Consumer<String> activate){}void setMainOnRight(boolean right){}void close(){open=false;closes++;}}
static class Replay extends Base {
BooleanSupplier ready=()->true;Object dispatch,bounds,hovered;Consumer<String> hint=value->{};ShareRailOverlay overlay;
boolean panelSource,hasPointer,mainRight;Object transferClip;Counter receipt;Function<String,String> hoverIdentity=value->value;Consumer<String> hoverActivate=value->{};
String sourcePackage;Set<String> windowPackages;
static class Hover extends Counter {void move(String value){}}Hover windowHover=new Hover();
boolean dragging,dropped,deliveryPending,retainingDelivery,shareOnly;int generation,starts,ends,stops,drops;String lastTarget;
Handler handler=new Handler();Runnable expire=()->{},deliverySettled=()->{};
Counter pendingRead,delegatedRead,catchment;Counter panel=new Counter(),history=new Counter(),capture=new Counter(),portal=new Counter();Runnable afterDrag=()->{};
Transition transition=new Transition();float alpha=1;
Targets targets=new Targets();
boolean accepts(Object description){return true;}boolean live(int token){return dragging&&ready.getAsBoolean();}
boolean drag(DragEvent event,Object plate){if(event.action==1){starts++;dragging=true;}if(event.action==4){ends++;finishVisuals();}return true;}
boolean drop(DragEvent event,ComponentName target){drops++;lastTarget=target.pkg;return true;}
void stopEdge(){stops++;}void setAlpha(float value){alpha=value;}
__METHODS__
}
public static void main(String[] args){
Replay rail=new Replay();check(rail.onWindowDrag(new DragEvent(1),null,false)&&rail.starts==1&&rail.dragging,"host root initializes normal share session");
rail.windowPackages=Set.of("chat.pkg");
check(rail.onWindowDrag(new DragEvent(2),"chat.pkg",false),"known compatible window can be highlighted");
check(!rail.onWindowDrag(new DragEvent(2),"other.pkg",false),"unknown window does not advertise an app share");
rail.sourcePackage="chat.pkg";check(!rail.onWindowDrag(new DragEvent(2),"chat.pkg",false),"source window never highlights");
rail.sourcePackage=null;
rail.onWindowDrag(new DragEvent(3),"chat.pkg",false);check(rail.drops==1&&"chat.pkg".equals(rail.lastTarget),"small-window DROP uses target package");
check(!rail.onWindowDrag(new DragEvent(3),null,false),"drop outside occupied target rejected");
rail.onWindowDrag(new DragEvent(4),null,false);check(rail.ends==1&&!rail.dragging,"root drag end always retires session");
check(!rail.onWindowDrag(new DragEvent(3),"late.pkg",false),"late target DROP rejected");
rail.onWindowDrag(new DragEvent(1),null,true);check(rail.overlay!=null&&rail.overlay.open,"occupied upper face opens dedicated overlay");
rail.onWindowDrag(new DragEvent(3),"notes.pkg",false);check("notes.pkg".equals(rail.overlay.target),"overlay owns entire current session even if coverage changes");
rail.onWindowDrag(new DragEvent(4),null,false);check(!rail.overlay.open,"root drag end reaches overlay after coverage changes");
rail=new Replay();rail.dragging=true;rail.dropped=true;rail.deliveryPending=true;int[] done={0};
check(rail.retainDelivery(()->done[0]++),"pending delivery can outlive drag window");
rail.onDetachedFromWindow();check(rail.panel.closes==1&&rail.capture.closes==1&&rail.history.stops==1,"detach closes all UI and listeners while retaining delivery");
check(rail.deliveryPending&&rail.retainingDelivery&&rail.detaches==1,"detach does not cancel accepted async delivery");
check(rail.transition.resets==1&&!rail.dragging,"retained delivery cannot retain cube animation");
rail.delegatedRead=new Counter();Counter grant=rail.delegatedRead;rail.finishDelivery(rail.generation);
check(!rail.deliveryPending&&!rail.retainingDelivery&&done[0]==1,"completed delivery releases retention and callback once");
check(grant.closes==1&&rail.delegatedRead==null,"completion releases remaining delegated grant");
rail.finishDelivery(rail.generation);check(done[0]==1&&grant.closes==1,"duplicate completion cannot double-close lease");
rail=new Replay();rail.deliveryPending=true;rail.retainDelivery(()->done[0]++);int previous=done[0];
rail.finishDelivery(rail.generation-1);check(rail.deliveryPending&&done[0]==previous,"stale completion cannot retire newer delivery");
rail.pendingRead=new Counter();rail.delegatedRead=new Counter();Counter read=rail.pendingRead;grant=rail.delegatedRead;
rail.cancel();check(!rail.deliveryPending&&!rail.retainingDelivery&&rail.pendingRead==null&&rail.delegatedRead==null,"privacy cancel clears all pending capability state");
check(read.closes==1&&grant.closes==1&&done[0]==previous+1,"privacy cancel releases capabilities and detached owner");
int reset=rail.transition.resets;rail.finishDelivery(rail.generation-1);check(rail.transition.resets==reset,"retired finish cannot change current visual state");
rail=new Replay();rail.shareOnly=true;rail.dragging=true;rail.dropped=true;rail.deliveryPending=true;rail.finishVisuals();
check(rail.alpha==0&&rail.transition.resets==1&&!rail.dragging,"share-only strip hides immediately when drag ends");
check(rail.deliveryPending,"visual retirement leaves accepted payload delivery alive");
check(!rail.targets.child.hovered,"drag end clears target magnification while preserving pending delivery");
rail=new Replay();rail.catchment=new Counter();Counter reach=rail.catchment;rail.onDetachedFromWindow();check(rail.panel.closes==1&&rail.capture.closes==1&&rail.history.stops==1,"ordinary detach releases all child state");
check(reach.closes==1&&rail.catchment==null,"detach removes the temporary arrow hit region");
check(rail.transition.resets==1,"ordinary detach clears cube animation");
rail=new Replay();rail.dragging=true;rail.sourcePackage="chat.pkg";check(!rail.onWindowDrag(new DragEvent(3),"chat.pkg",false)&&rail.drops==0,"same-app window release must not send the source message again");
rail=new Replay();rail.dragging=true;rail.cancel();check(rail.panel.restores==1,"cancel restores an active source popup");
rail=new Replay();rail.cancel();check(rail.panel.restores==0,"idle reset must not end a newly staged source drag");
System.out.println("Production share session checks="+checks+" passed");
}}
'''.replace('__METHODS__', methods),
}
paths = []
for name, body in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf-8')
    paths.append(str(path))
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), *paths], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.ShareSessionRegression'], check=True)
