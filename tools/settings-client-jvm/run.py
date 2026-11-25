"""Replay settings refresh ordering and broadcast wakeups against the actual client."""
from pathlib import Path
import os
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root)
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/os/Looper.java':'''package android.os;public class Looper {public java.util.List<Runnable> tasks=new java.util.ArrayList<>();public void drain(){while(!tasks.isEmpty())tasks.remove(0).run();}}''',
'android/os/Handler.java':'''package android.os;public class Handler {public final Looper loop;public Handler(Looper l){loop=l;}public void post(Runnable r){loop.tasks.add(r);}public void removeCallbacks(Runnable r){loop.tasks.removeIf(t->t==r);}}''',
'android/os/HandlerThread.java':'''package android.os;public class HandlerThread {public static Looper latest;public HandlerThread(String n){latest=new Looper();}public void start(){}public Looper getLooper(){return latest;}}''',
'android/os/Bundle.java':'package android.os;public class Bundle {public int mode;public Bundle(int m){mode=m;}}',
'android/database/ContentObserver.java':'package android.database;public class ContentObserver {public ContentObserver(android.os.Handler h){}public void onChange(boolean changed){}}',
'android/content/BroadcastReceiver.java':'package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
'android/content/Intent.java':'package android.content;public class Intent {public int untrustedMode;}',
'android/content/IntentFilter.java':'package android.content;public class IntentFilter {public IntentFilter(String action){}}',
'android/content/Context.java':'''package android.content;public class Context {public static final int RECEIVER_EXPORTED=2;
 public final ContentResolver resolver=new ContentResolver();public BroadcastReceiver receiver;public String permission;public boolean failRegister;
 public ContentResolver getContentResolver(){return resolver;}
 public void registerReceiver(BroadcastReceiver r,IntentFilter f,String p,android.os.Handler h,int flags){if(failRegister)throw new IllegalStateException();receiver=r;permission=p;}}''',
'android/content/ContentResolver.java':'''package android.content;public class ContentResolver {
 public int mode;public boolean fail,failObserve;public android.database.ContentObserver observer;
 public android.os.Bundle call(Object u,String m,Object a,Object e){if(fail)throw new IllegalStateException();return new android.os.Bundle(mode);}
 public void registerContentObserver(Object u,boolean descendants,android.database.ContentObserver o){if(failObserve)throw new IllegalStateException();observer=o;}}''',
'android/util/Log.java':'package android.util;public class Log {public static int warnings;public static void w(String t,String s,Throwable e){warnings++;}}',
'io/github/zerohomers0/onesteplite/WorkspaceOptions.java':'package io.github.zerohomers0.onesteplite;class WorkspaceOptions {final int entryGesture;WorkspaceOptions(int m){entryGesture=m;}}',
'io/github/zerohomers0/onesteplite/WorkspaceSettingsStore.java':'package io.github.zerohomers0.onesteplite;class WorkspaceSettingsStore {static final Object URI=new Object();static final String READ="read";static WorkspaceOptions decode(android.os.Bundle b){return new WorkspaceOptions(b.mode);}}',
'io/github/zerohomers0/onesteplite/SettingsClientRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.os.*;import android.content.*;import java.util.*;
public class SettingsClientRegression {
 static int checks;static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 public static void main(String[]args){
  Context c=new Context();Looper main=new Looper();List<Integer> received=new ArrayList<>();
  var client=new WorkspaceSettingsClient(c,new Handler(main),o->received.add(o.entryGesture));Looper worker=HandlerThread.latest;
  check("io.github.zerohomers0.onesteplite.permission.SETTINGS_WAKEUP".equals(c.permission),"wakeup receiver requires the module signature permission");
  worker.drain();check(received.isEmpty()&&main.tasks.size()==1,"provider result delivered asynchronously");
  c.resolver.mode=1;client.refresh();main.drain();check(received.isEmpty(),"old queued snapshot cannot overwrite a newer selection");
  worker.drain();main.drain();check(received.equals(List.of(1)),"latest bottom selection delivered");
  c.resolver.mode=0;Intent wakeup=new Intent();wakeup.untrustedMode=1;c.receiver.onReceive(c,wakeup);worker.drain();main.drain();
  check(received.equals(List.of(1,0)),"wakeup rereads provider and ignores spoofed extras");
  c.resolver.mode=1;c.resolver.observer.onChange(false);worker.drain();main.drain();check(received.equals(List.of(1,0,1)),"URI notifications still refresh idle gesture spy");
  client.refresh();client.refresh();client.refresh();check(worker.tasks.size()==1,"rapid notifications coalesce to one provider read");worker.drain();main.drain();
  c.resolver.fail=true;client.refresh();worker.drain();main.drain();int warnings=android.util.Log.warnings;
  client.refresh();worker.drain();main.drain();check(android.util.Log.warnings==warnings,"repeated provider failure logged once");
  check(received.get(received.size()-1)==1,"provider failure preserves last valid gesture choice");
  c.resolver.fail=false;c.resolver.mode=0;client.refresh();worker.drain();main.drain();check(received.get(received.size()-1)==0,"provider recovery switches back to status-bar gesture");
  Context fallback=new Context();fallback.failRegister=true;
  var surviving=new WorkspaceSettingsClient(fallback,new Handler(main),o->received.add(o.entryGesture));
  HandlerThread.latest.drain();main.drain();check(fallback.resolver.observer!=null,"optional wakeup registration failure retains primary observer");
  System.out.println("Production settings client ordering checks="+checks+" passed");
 }
}'''
}
paths=[]
for name,body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'WorkspaceSettingsClient.java'))
paths.append(str(src/'Actions.java'))
paths.append(str(src/'Tags.java'))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.SettingsClientRegression'],check=True)
