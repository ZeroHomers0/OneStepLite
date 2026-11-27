"""Regression for bounded TaskWindowState retention."""
import os
import sys as _sys, pathlib as _pathlib
_sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1]))
from source_paths import package_dir
from pathlib import Path
import subprocess, sys
root=Path(__file__).resolve().parents[2]
src=package_dir(root)
out=Path(sys.argv[1]); out.mkdir(parents=True,exist_ok=True)
source=(src/'WorkspaceTaskStateController.java').read_text(encoding='utf-8-sig')
a=source.index('    private void trimTaskWindowStatesLocked() {')
b=source.index('{',a)+1; depth=1
while depth:
    depth += (source[b]=='{')-(source[b]=='}'); b+=1
method=source[a:b].replace('host.','')
body=r'''package io.github.zerohomers0.onesteplite;
import java.util.*;
public class TaskStateRetentionRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static final int MAX_TASK_STATES=96;
 static class Slot{int taskId;Slot(int id){taskId=id;}}
 static class TaskWindowState{}
 final Map<Integer,TaskWindowState> taskWindowStates=new LinkedHashMap<>(16,.75f,true);
 final Slot[] slots={new Slot(7)};
 __METHOD__
 public static void main(String[] args){
  TaskStateRetentionRegression h=new TaskStateRetentionRegression();
  for(int i=0;i<120;i++)h.taskWindowStates.put(i,new TaskWindowState());
  h.trimTaskWindowStatesLocked();
  check(h.taskWindowStates.size()==MAX_TASK_STATES,"cache is bounded");
  check(h.taskWindowStates.containsKey(7),"active task state survives eviction");
  check(!h.taskWindowStates.containsKey(0),"old inactive task state is evicted");
  System.out.println("Task state retention checks="+checks+" passed");
 }
}
'''.replace('__METHOD__',method)
p=out/'io/github/zerohomers0/onesteplite/TaskStateRetentionRegression.java';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8')
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(p),str(src/'WorkspaceRuntimeCounters.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.TaskStateRetentionRegression'],check=True)
