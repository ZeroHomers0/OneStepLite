from pathlib import Path
import os, subprocess, sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir

root = Path(__file__).resolve().parents[2]
src = package_dir(root)
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
text = (src/'WorkspaceHost.java').read_text(encoding='utf-8-sig')

def member(sig):
    start=text.index(sig); brace=text.index('{',start); depth=1; end=brace+1
    while depth:
        depth += (text[end]=='{')-(text[end]=='}'); end+=1
    return text[start:end]

body='''package io.github.zerohomers0.onesteplite;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
public class SlotCreationRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static final class WorkspaceLayout {int cellCount;}
 static final class Slot {final int index;Slot(int i){index=i;}}
 static final class TaskViewWarmup {static Object take(Object a,Object b){return null;}}
 static final class ShellCompat {static void createTaskView(Object f,Object c,Executor e,Consumer<Object> cb){cb.accept(new Object());}}
 static final class Main {void post(Runnable r){r.run();}}
 final List<Integer> created=new ArrayList<>();boolean configured,compacted; final class Cells { void requestCompaction(){SlotCreationRegression.this.requestCompaction();} } Cells cells=new Cells();
 static final int SLOT_TOP=5,SLOT_COUNT=6;
 int CELL_SLOT_COUNT;Slot[] slots=new Slot[SLOT_COUNT];WorkspaceLayout layout=new WorkspaceLayout();Main main=new Main();
 Object ctx=new Object(),factory=new Object();Executor executor=Runnable::run;
 SlotCreationRegression(int cells){CELL_SLOT_COUNT=cells+1;layout.cellCount=cells;for(int i=0;i<slots.length;i++)slots[i]=new Slot(i);}
 void configureTabletTools(){configured=true;}static void log(String s){}void requestCompaction(){compacted=true;}
 void onTaskViewCreated(Slot slot,Object view,boolean next){created.add(slot.index);if(next)createNext(slot.index+1);}
__CREATE_NEXT__
 public static void main(String[] args){
  SlotCreationRegression phone=new SlotCreationRegression(3);phone.createNext(1);
  check(phone.created.equals(List.of(1,2,3,5))&&phone.configured&&phone.compacted,
        "phone skips the unused fourth-cell slot but still creates TOP");
  SlotCreationRegression tablet=new SlotCreationRegression(4);tablet.createNext(1);
  check(tablet.created.equals(List.of(1,2,3,4,5)),"tablet retains all four cells and TOP");
  System.out.println("SlotCreation checks="+checks+" passed");
 }
}
'''.replace('__CREATE_NEXT__', member('    private void createNext(final int index) {'))
p=out/'io/github/zerohomers0/onesteplite/SlotCreationRegression.java';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8')
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(p)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.SlotCreationRegression'],check=True)