"""Replay WorkSpace status-bar lease ordering after the dead preview bridge removal."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
def method(text,signature):
    start=text.index(signature);brace=text.index('{',start);depth=1;end=brace+1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[start:end]
host=(src/'WorkspaceLiveHandoffController.java').read_text(encoding='utf-8-sig')
methods='\n'.join(method(host,s).replace('host.','') for s in (
    '    void closeSwapPreview() {',
    '    void closeSwapPreview(Object fallbackStatusOwner) {',
    '    void finishSwapPreview(Object statusOwner, long settleMs) {'))
java=r'''import java.util.*;
public class HostRevealOrderTest {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check="+checks);}
 static class Queue{ArrayDeque<Runnable> tasks=new ArrayDeque<>();void postOnAnimation(Runnable r){tasks.add(r);}void postDelayed(Runnable r,long ms){tasks.add(r);}void next(){tasks.remove().run();}}
 static class Status{Object owner;boolean hidden;void begin(Object t){owner=t;hidden=true;}void finish(Object t){if(t!=null&&owner==t){owner=null;hidden=false;}}}
 static class Promotion{boolean dragHover;}
 Queue root=new Queue(),main=new Queue();Status statusBar=new Status();Object promotion;
 __METHODS__
 public static void main(String[] args){
  var h=new HostRevealOrderTest();Object first=new Object(),second=new Object();
  h.statusBar.begin(first);h.finishSwapPreview(first,120);check(h.statusBar.hidden);
  h.root.next();h.root.next();check(h.statusBar.hidden);h.main.next();check(!h.statusBar.hidden);
  h.statusBar.begin(first);h.finishSwapPreview(first,0);h.root.next();h.root.next();check(!h.statusBar.hidden);
  h.statusBar.begin(first);h.finishSwapPreview(first,0);h.statusBar.begin(second);h.root.next();h.root.next();
  check(h.statusBar.hidden&&h.statusBar.owner==second);h.closeSwapPreview(second);check(!h.statusBar.hidden);
  h.statusBar.begin(first);h.closeSwapPreview(first);check(!h.statusBar.hidden);
  Promotion drag=new Promotion();drag.dragHover=true;h.statusBar.begin(drag);h.finishSwapPreview(drag,120);
  check(!h.statusBar.hidden&&h.root.tasks.isEmpty());
  System.out.println("HostRevealOrder checks="+checks+" passed");
 }
}'''.replace('__METHODS__',methods)
p=out/'HostRevealOrderTest.java';p.write_text(java,encoding='utf-8')
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(p)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'HostRevealOrderTest'],check=True)
