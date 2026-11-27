"""Verify HookGuard commits complete groups and rolls back partial installs."""
from pathlib import Path
import os, subprocess, sys
import sys as _sys, pathlib as _pathlib
_sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]) if len(sys.argv)>1 else root/'build/tests/hook-guard-jvm'
out.mkdir(parents=True,exist_ok=True)
files={
'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;
public class XC_MethodHook {
 public static class Unhook {
  private final Runnable undo;boolean active=true;
  public Unhook(Runnable undo){this.undo=undo;}
  public void unhook(){if(active){active=false;undo.run();}}
 }
}''',
'de/robv/android/xposed/XposedBridge.java':'''package de.robv.android.xposed;
import java.lang.reflect.Member;import java.util.Set;
public class XposedBridge {
 public static int installed,rolledBack;public static boolean failMethod;
 public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> owner,String name,XC_MethodHook callback){
  if("missing".equals(name))return Set.of();
  installed++;return Set.of(new XC_MethodHook.Unhook(()->{installed--;rolledBack++;}));
 }
 public static XC_MethodHook.Unhook hookMethod(Member method,XC_MethodHook callback){
  if(failMethod)throw new IllegalStateException("signature changed");
  installed++;return new XC_MethodHook.Unhook(()->{installed--;rolledBack++;});
 }
 public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> owner,XC_MethodHook callback){
  installed++;return Set.of(new XC_MethodHook.Unhook(()->{installed--;rolledBack++;}));
 }
}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;
public class XposedHelpers {
 public static Class<?> findClass(String name,ClassLoader loader){try{return Class.forName(name,false,loader);}catch(ClassNotFoundException e){throw new IllegalStateException(e);}}
}''',
'io/github/zerohomers0/onesteplite/HookGuardRegression.java':'''package io.github.zerohomers0.onesteplite;
import de.robv.android.xposed.*;import java.util.*;
public class HookGuardRegression {
 static int checks;static List<String> log=new ArrayList<>();
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  ClassLoader loader=HookGuardRegression.class.getClassLoader();
  boolean complete=HookGuard.install("complete",loader,log::add,session->{
   Class<?> type=session.findClass("java.lang.String");
   session.hookAllMethods(type,"ok",new XC_MethodHook(){});
   session.hookMethod(String.class.getMethod("length"),new XC_MethodHook(){});
  });
  check(complete&&XposedBridge.installed==2&&XposedBridge.rolledBack==0,"complete group commits every hook");
  XposedBridge.installed=XposedBridge.rolledBack=0;
  boolean missing=HookGuard.install("missing",loader,log::add,session->{
   Class<?> type=session.findClass("java.lang.String");
   session.hookAllMethods(type,"ok",new XC_MethodHook(){});
   session.hookAllMethods(type,"missing",new XC_MethodHook(){});
  });
  check(!missing&&XposedBridge.installed==0&&XposedBridge.rolledBack==1,"missing method rolls back the group");
  XposedBridge.installed=XposedBridge.rolledBack=0;
  boolean optional=HookGuard.install("optional",loader,log::add,session->{
   Class<?> type=session.findClass("java.lang.String");
   session.tryHookAllMethods(type,"missing",new XC_MethodHook(){});
   session.hookAllMethods(type,"ok",new XC_MethodHook(){});
  });
  check(optional&&XposedBridge.installed==1&&XposedBridge.rolledBack==0,"optional probe keeps the remaining group");
  check(log.stream().anyMatch(line->line.contains("skipped java.lang.String#missing")),"optional skip is explicit");
  XposedBridge.installed=XposedBridge.rolledBack=0;XposedBridge.failMethod=true;
  boolean failed=HookGuard.install("failed",loader,log::add,session->{
   Class<?> type=session.findClass("java.lang.String");
   session.hookAllMethods(type,"ok",new XC_MethodHook(){});
   session.hookMethod(String.class.getMethod("length"),new XC_MethodHook(){});
  });
  check(!failed&&XposedBridge.installed==0&&XposedBridge.rolledBack==1,"thrown install error rolls back earlier hooks");
  System.out.println("HookGuard transaction checks="+checks+" passed");
 }
}'''
}
for name,body in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8')
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),
                *[str(out/name) for name in files],str(package_dir(root)/'HookGuard.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.HookGuardRegression'],check=True)