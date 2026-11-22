"""Exercise actual side motion, window privacy and native task-return coordination."""
from pathlib import Path
import ast
import os
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root)
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
tree=ast.parse((root/'tools/top-transition-jvm/run.py').read_text(encoding='utf-8-sig'))
shared=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign)
            and any(isinstance(t,ast.Name) and t.id=='files' for t in n.targets))
files={n:s for n,s in shared.items() if n.startswith('android/animation/')}
files.update({
'android/animation/TimeInterpolator.java':'package android.animation;public interface TimeInterpolator {float getInterpolation(float f);}',
'android/content/Context.java':'package android.content;public class Context {}',
'android/view/View.java':'''package android.view;public class View {
 public java.util.List<Runnable> frames=new java.util.ArrayList<>();public android.content.Context getContext(){return new android.content.Context();}
 public void postOnAnimation(Runnable r){frames.add(r);}public void removeCallbacks(Runnable r){frames.removeIf(x->x==r);}}''',
'android/widget/OverScroller.java':'''package android.widget;public class OverScroller {
 public static OverScroller last;public boolean moving;public int y,end;public OverScroller(Object c){last=this;}
 public void fling(int x,int y,int vx,int vy,int a,int b,int c,int end){this.y=y;this.end=end;moving=true;}
 public int getFinalY(){return end;}public int getCurrY(){return y;}public boolean computeScrollOffset(){return moving;}
 public void forceFinished(boolean v){moving=!v;}}''',
'io/github/zerohomers0/onesteplite/WorkspaceCoordinationRegression.java':'''package io.github.zerohomers0.onesteplite;
import java.util.*;import android.animation.*;import android.view.View;import android.widget.OverScroller;
public class WorkspaceCoordinationRegression {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static class Window implements WindowPrivacy.Window {
  float alpha=.8f;int flags=64,applies,layouts;boolean fail;
  public float alpha(){return alpha;}public int flags(){return flags;}public void attributes(float a,int f){alpha=a;flags=f;}
  public void apply(){if(fail)throw new IllegalStateException();applies++;}public void requestLayout(){layouts++;}}
 static class ReturnHost implements TaskReturnCoordinator.Host {
  boolean current=true,queue=true,attempted,ready,removed,inspectFail;int inspections,completes,failures;boolean frozen;
  List<Runnable> shell=new ArrayList<>(),main=new ArrayList<>(),later=new ArrayList<>();
  public boolean current(){return current;}public boolean executeOnShell(Runnable a){if(queue)shell.add(a);return queue;}
  public TaskReturnCoordinator.Result inspect(){inspections++;if(inspectFail)throw new IllegalStateException();return new TaskReturnCoordinator.Result(ready,removed,"waiting");}
  public void post(Runnable a){main.add(a);}public void later(Runnable a,long delay){check(delay==500,"native retry interval unchanged");later.add(a);}
  public boolean handoffAttempted(){return attempted;}public void complete(boolean gone){completes++;check(gone==removed,"task removal observation preserved");}
  public void fail(String s,boolean freeze){failures++;frozen=freeze;}
  void drain(){while(!shell.isEmpty())shell.remove(0).run();while(!main.isEmpty())main.remove(0).run();}}
 public static void main(String[]args){
  var delayed=new ArrayList<Runnable>();var published=new ArrayList<String>();long[] now={100},delay={0};boolean[] zoomed={true};
  var zoom=new WorkspaceZoomCommands((r,ms)->{delayed.add(r);delay[0]=ms;},()->now[0],()->zoomed[0],published::add,s->{});
  zoom.animated(true,".75 600 0",132,300);check(delay[0]==482&&published.get(0).endsWith("at=132"),"zoom steady write waits for shared animation start");
  zoom.write("1 0 0");delayed.remove(0).run();check(published.size()==2,"plain restore invalidates delayed zoom completion");
  zoom.animated(true,".75 600 0",132,300);zoom.observeGeneration(100);delayed.remove(0).run();check(published.size()==3,"live swap generation invalidates older workspace completion");
  zoom.animated(true,".75 600 0",132,300);zoomed[0]=false;delayed.remove(0).run();check(published.size()==4,"lock/suspend state prevents late active zoom rewrite");
  now[0]=500;zoomed[0]=true;zoom.animated(true,".75 600 0",132,300);check(delay[0]==450,"late publish retains bounded steady delay");
  delayed.remove(0).run();check(published.size()==6&&published.get(5).equals(".75 600 0"),"current active animation completes with one steady request");
  check(zoom.nextGeneration()>100,"workspace and live-swap commands share a monotonic generation");
  var rows=new SideRowOrder<String>();rows.addAll(List.of("a","b","c","d","e"));
  check(rows.maxOffset(3,s->false)==0,"empty rows cannot scroll past the visible page");
  check(rows.maxOffset(3,s->s.equals("d"))==2,"one ready empty row follows final occupied task");
  String[] slots={"main","a","b","c","top","spare","d","e"};
  var rowIndices=new HashMap<String,Integer>();var positions=new HashMap<String,Integer>();
  String[] mapped=rows.remap(slots,1,3,6,rowIndices::put,positions::put);
  check(mapped[0].equals("main")&&mapped[4].equals("top"),"main and top owners survive side remapping");
  check(mapped[1].equals("b")&&mapped[2].equals("c")&&mapped[3].equals("d"),"viewport maps existing row owners into visible positions");
  check(mapped[6].equals("a")&&mapped[7].equals("e")&&rowIndices.get("e")==4,"offscreen rows retain identity and logical row");
  check(slots[1].equals("a")&&positions.get("d")==3,"remap clones array before publishing new physical indices");
  rows.removeIf(s->s.equals("b"));rows.add(1,"b");check(rows.equals(List.of("a","b","c","d","e")),"row compaction preserves existing list operations");
  var privacy=new WindowPrivacy(s->{});var w=new Window();
  check(privacy.setHidden(w,true,16)&&w.alpha==0&&w.flags==80,"hide captures alpha and disables input");
  check(privacy.setHidden(w,false,16)&&w.alpha==.8f&&w.flags==64,"unlock restores exact alpha and touchability");
  w.flags=80;privacy.setHidden(w,true,16);privacy.setHidden(w,false,16);check(w.flags==80,"preexisting not-touchable flag survives unlock");
  w.fail=true;float alpha=w.alpha;int flags=w.flags;
  check(!privacy.setHidden(w,true,16)&&w.alpha==alpha&&w.flags==flags,"failed hide rolls attributes back");
  w.fail=false;privacy.setHidden(w,true,16);w.fail=true;
  check(!privacy.setHidden(w,false,16)&&w.alpha==0,"failed reveal retains hidden attributes");
  Object token=new Object();for(int gate=0;gate<6;gate++){
   check(!TaskHandoffPolicy.bindingCurrent(gate!=0,1,gate==1?2:1,3,gate==2?4:3,7,gate==3?8:7,
        gate==4?new Object():token,gate==5?null:token),"changed identity rejected "+gate);
  }
  check(TaskHandoffPolicy.bindingCurrent(true,1,1,3,3,7,7,token,token),"same scene and exact task binding accepted");
  var h=new ReturnHost();h.ready=true;var returned=new TaskReturnCoordinator(h);returned.observe(0);h.drain();
  check(h.completes==1&&h.failures==0,"native success completes once");returned.observe(0);check(h.shell.isEmpty(),"completed coordinator cannot restart");
  h=new ReturnHost();h.removed=h.ready=true;returned=new TaskReturnCoordinator(h);returned.observe(0);h.drain();check(h.completes==1,"confirmed dead task follows removal completion");
  h=new ReturnHost();returned=new TaskReturnCoordinator(h);returned.observe(0);
  for(int i=0;i<3;i++){h.drain();if(!h.later.isEmpty())h.later.remove(0).run();}
  check(h.inspections==3&&h.failures==1&&h.frozen,"native observation retains original bounded retry count");
  h=new ReturnHost();h.attempted=true;returned=new TaskReturnCoordinator(h);returned.observe(0);h.drain();
  check(h.inspections==1&&h.failures==1&&h.later.isEmpty(),"attempted ownership handoff never retries blindly");
  h=new ReturnHost();returned=new TaskReturnCoordinator(h);returned.observe(0);h.current=false;h.drain();
  check(h.completes==0&&h.failures==1&&!h.frozen,"stale Shell callback cannot complete or freeze a replacement binding");
  h=new ReturnHost();h.ready=true;returned=new TaskReturnCoordinator(h);returned.observe(0);h.shell.remove(0).run();h.current=false;h.drain();
  check(h.completes==0&&h.failures==1,"binding rechecked after returning to main thread");
  h=new ReturnHost();h.queue=false;returned=new TaskReturnCoordinator(h);returned.observe(0);check(h.failures==1&&!h.frozen,"unavailable Shell does not freeze untouched task");
  h=new ReturnHost();h.inspectFail=true;returned=new TaskReturnCoordinator(h);returned.observe(2);h.drain();check(h.failures==1,"inspection exceptions follow bounded failure handling");
  var motion=new SideScrollMotion();float[] offset={0};int[] done={0};boolean[] current={true};
  motion.animate(0,10,160,f->f,()->current[0],f->offset[0]=f,()->done[0]++);var first=ValueAnimator.all.get(ValueAnimator.all.size()-1);
  first.step(.5f);check(offset[0]==5&&motion.running(),"side settle interpolates original offset");
  motion.cancel();first.step(.8f);first.end();check(offset[0]==5&&done[0]==0&&!motion.running(),"cancelled side animation cannot write or complete");
  motion.animate(0,1,240,f->f,()->current[0],f->offset[0]=f,()->done[0]++);first=ValueAnimator.all.get(ValueAnimator.all.size()-1);
  float before=offset[0];current[0]=false;first.step(.7f);check(offset[0]==before,"old scene rejects compaction frame");motion.cancel();current[0]=true;
  View root=new View();int[] pixels={0};int projected=motion.fling(root,100,1000,200,()->current[0],y->pixels[0]=y,motion::cancel,()->done[0]++);
  check(projected==1000&&motion.running()&&root.frames.size()==1,"fling belongs to one root frame queue");
  Runnable frame=root.frames.remove(0);OverScroller.last.y=300;frame.run();check(pixels[0]==300,"fling updates pixel offset");
  motion.cancel();frame.run();check(root.frames.isEmpty()&&!motion.running(),"cancel removes old-root callbacks and stale frame cannot repost");
  motion.fling(root,100,1000,200,()->current[0],y->pixels[0]=y,motion::cancel,()->done[0]++);
  current[0]=false;root.frames.remove(0).run();check(!motion.running(),"scene change invalidates fling without another frame");
  System.out.println("Production workspace coordination checks="+checks+" passed");
 }
}'''
})
paths=[]
for name,body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'SideScrollMotion.java'))
paths.append(str(src/'SideRowOrder.java'))
paths.append(str(src/'WindowPrivacy.java'))
paths.append(str(src/'TaskReturnCoordinator.java'))
paths.append(str(src/'TaskHandoffPolicy.java'))
paths.append(str(src/'HandoffOwner.java'))
paths.append(str(src/'WorkspaceZoomCommands.java'))
paths.append(str(src/'ZoomLifecyclePolicy.java'))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.WorkspaceCoordinationRegression'],check=True)
