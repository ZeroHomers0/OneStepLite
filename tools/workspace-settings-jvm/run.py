"""Exercise real preference migration, snapshots and the system gesture-line gate."""
import os
from pathlib import Path
import subprocess
import sys
import sys as _sys, pathlib as _pathlib; _sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1])); from source_paths import package_dir

root = Path(__file__).resolve().parents[2]
src=package_dir(root)
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
files = {
'android/content/Intent.java':'package android.content;public class Intent {public Intent(String action){}public Intent setPackage(String p){return this;}}',

'android/net/Uri.java': 'package android.net;public class Uri {public static Uri parse(String s){return new Uri();}}',
'android/os/Bundle.java': '''package android.os;import java.util.*;public class Bundle {
 Map<String,Object> values=new HashMap<>();public void putInt(String k,int v){values.put(k,v);}
 public int getInt(String k,int fallback){return (Integer)values.getOrDefault(k,fallback);}
 public void putBoolean(String k,boolean v){values.put(k,v);}
 public boolean getBoolean(String k,boolean fallback){return (Boolean)values.getOrDefault(k,fallback);}}''',
'android/content/ContentResolver.java': '''package android.content;public class ContentResolver {
 public int notifications;public void notifyChange(Object uri,Object observer){notifications++;}}''',
'android/content/SharedPreferences.java': '''package android.content;import java.util.*;public class SharedPreferences {
 public Map<String,Object> values=new HashMap<>();public boolean commit=true;
 public int getInt(String key,int fallback){return (Integer)values.getOrDefault(key,fallback);}
 public boolean getBoolean(String key,boolean fallback){return (Boolean)values.getOrDefault(key,fallback);}
 public Editor edit(){return new Editor();}
 public class Editor {Map<String,Object> pending=new HashMap<>();
  public Editor remove(String k){pending.put(k,null);return this;}public Editor putInt(String k,int v){pending.put(k,v);return this;}
  public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
  public boolean commit(){if(commit)for(var entry:pending.entrySet()){if(entry.getValue()==null)values.remove(entry.getKey());else values.put(entry.getKey(),entry.getValue());}return commit;}}}''',
'android/content/Context.java': '''package android.content;public class Context {
 public void sendBroadcast(Intent i){}
 public static final int MODE_PRIVATE=0;public SharedPreferences prefs=new SharedPreferences();
 public ContentResolver resolver=new ContentResolver();public Context createDeviceProtectedStorageContext(){return this;}
 public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
 public ContentResolver getContentResolver(){return resolver;}}''',
'android/provider/Settings.java': '''package android.provider;public class Settings {public static class Global {
 public static int hidden;public static boolean fail;public static int getInt(Object r,String name,int fallback){
 if(!"hide_gesture_line".equals(name))throw new AssertionError("wrong key");
 if(fail)throw new IllegalStateException();return hidden;}}}''',
'io/github/zerohomers0/onesteplite/SettingsRegression.java': '''package io.github.zerohomers0.onesteplite;
import android.content.*;import android.os.*;import android.provider.Settings;
public class SettingsRegression {
 static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 public static void main(String[] args) {
  Context c=new Context();
  check(WorkspaceSettingsStore.read(c).entryGesture==0,"old install retains corner default");
  c.prefs.values.put("hidden_buttons",2);c.prefs.values.put("swap_mode",1);
  WorkspaceOptions old=WorkspaceSettingsStore.read(c);
  check(old.showLunar&&old.swapMode==1&&old.entryGesture==0,"old preferences migrate without loss");
  check(!old.keepOnLock&&!WorkspaceOptions.DEFAULT.keepOnLock,"old installs retain lock-exit default");
  check(WorkspaceSettingsStore.write(c,old.withKeepOnLock(true)),"lock choice saves independently of gesture line");
  check(WorkspaceSettingsStore.read(c).keepOnLock,"lock choice persists");
  check(WorkspaceSettingsStore.decode(WorkspaceSettingsStore.bundle(WorkspaceSettingsStore.read(c))).keepOnLock,"lock choice reaches SystemUI snapshot");
  check(old.withKeepOnLock(true).withShowLunar(false).withSwapMode(0).withEntryGesture(1).keepOnLock,"all other choices preserve lock preference");
  check(!WorkspaceSettingsStore.decode(new Bundle()).keepOnLock,"old provider snapshot defaults to exit");
  check(WorkspaceSettingsStore.write(c,old),"lock option can be disabled");
  c.resolver.notifications=0;
  WorkspaceOptions bottom=old.withEntryGesture(1);
  check(!WorkspaceSettingsStore.write(c,bottom),"visible line cannot enable bottom");
  check(c.resolver.notifications==0&&WorkspaceSettingsStore.read(c).entryGesture==0,"rejected selection does not persist or notify");
  Settings.Global.hidden=1;
  check(WorkspaceSettingsStore.write(c,bottom),"hidden line allows selection");
  check(c.resolver.notifications==1,"saved selection notifies live host");
  WorkspaceOptions decoded=WorkspaceSettingsStore.decode(WorkspaceSettingsStore.bundle(WorkspaceSettingsStore.read(c)));
  check(decoded.entryGesture==1&&decoded.showLunar&&decoded.swapMode==1,"snapshot carries all user choices");
  check(!WorkspaceSettingsStore.decode(WorkspaceSettingsStore.bundle(decoded.withShowLunar(false))).showLunar,"lunar choice reaches SystemUI");
  check(decoded.withShowLunar(false).entryGesture==1,"lunar toggle preserves entry");
  check(decoded.withSwapMode(0).entryGesture==1,"swap toggle preserves entry");
  for(int mode=1;mode<3;mode++) {
   WorkspaceOptions v=decoded.withLandscapeVideo(mode).withShowLunar(false).withSwapMode(0).withKeepOnLock(true).withScrollSideTasks(true);
   check(WorkspaceSettingsStore.write(c,v),"landscape preference persists");
   check(WorkspaceSettingsStore.read(c).landscapeVideo==mode,"landscape preference reads back");
   check(WorkspaceSettingsStore.decode(WorkspaceSettingsStore.bundle(v)).landscapeVideo==mode,"landscape snapshot reaches Host");
  }
  check(decoded.withLandscapeVideo(0).landscapeVideo==1,"retired top playback falls back to main landscape");
  check(WorkspaceSettingsStore.decode(new Bundle()).landscapeVideo==1,"existing installations retain main landscape");
  check(decoded.withLandscapeVideo(99).landscapeVideo==1,"invalid landscape mode uses main landscape");
  check(!WorkspaceSettingsStore.decode(new Bundle()).hideSideRecents,"old snapshot keeps visible recents");
  c.prefs.values.put("rounded_windows",true);
  for(boolean hide:new boolean[]{true,false}) {
   WorkspaceOptions v=decoded.withHideSideRecents(hide).withLandscapeVideo(1).withShowLunar(false).withSwapMode(1).withKeepOnLock(true).withScrollSideTasks(true).withEntryGesture(1);
   check(v.hideSideRecents==hide,"all setters preserve recents");
   check(WorkspaceSettingsStore.write(c,v),"new choices persist");
   WorkspaceOptions nativeRead=WorkspaceSettingsStore.decode(WorkspaceSettingsStore.bundle(WorkspaceSettingsStore.read(c)));
   check(nativeRead.hideSideRecents==hide,"snapshot carries recents choices");
   check(c.prefs.values.get("rounded_windows")==null,"obsolete rounded preference removed");
  }
  check(!WorkspaceSettingsStore.decode(new Bundle()).parkMainOnLaunch,"old snapshots never enable automatic collection");
  c.prefs.values.put("allow_secure_capture",true);
  check(!WorkspaceSettingsStore.read(c).parkMainOnLaunch,"legacy secure-capture choice must not become automatic collection");
  check(WorkspaceSettingsStore.write(c,decoded.withParkMainOnLaunch(true)),"automatic collection choice persists");
  check(WorkspaceSettingsStore.decode(WorkspaceSettingsStore.bundle(WorkspaceSettingsStore.read(c))).parkMainOnLaunch,"provider snapshot carries automatic collection");
  check(!c.prefs.values.containsKey("allow_secure_capture"),"retired capture setting is removed on write");
  check(WorkspaceSettingsStore.write(c,decoded.withParkMainOnLaunch(false)),"automatic collection can be disabled");
  check(!WorkspaceSettingsStore.decode(WorkspaceSettingsStore.bundle(WorkspaceSettingsStore.read(c))).parkMainOnLaunch,"disabled collection survives reload");
  Settings.Global.hidden=0;
  check(EntryGesturePolicy.effectiveMode(decoded.entryGesture,EntryGestureSettings.lineHidden(c))==0,"line reappears uses original corner");
  check(WorkspaceSettingsStore.write(c,decoded.withShowLunar(false)),"other settings still save while bottom blocked");
  check(WorkspaceSettingsStore.read(c).entryGesture==1,"runtime fallback does not overwrite choice");
  check(WorkspaceSettingsStore.write(c,decoded.withEntryGesture(0)),"corner can always be selected");
  Settings.Global.hidden=2;
  check(!WorkspaceSettingsStore.write(c,bottom),"unknown line state does not enable");
  Settings.Global.hidden=1;Settings.Global.fail=true;
  check(!WorkspaceSettingsStore.write(c,bottom),"failed system read does not enable");
  Settings.Global.fail=false;
  c.prefs.commit=false;
  int notifications=c.resolver.notifications;
  check(!WorkspaceSettingsStore.write(c,bottom),"commit failure is reported");
  check(c.resolver.notifications==notifications,"failed write does not notify");
  check(new WorkspaceOptions(0,0,99).entryGesture==0,"unknown entry stored value uses corner");
  check(WorkspaceSettingsStore.decode(new Bundle()).entryGesture==0,"old provider snapshot retains corner");
  try {WorkspaceSettingsStore.decode(null);throw new AssertionError("missing snapshot accepted");}
  catch(IllegalArgumentException expected){checks++;}
  c.prefs.commit=true;
  check(!WorkspaceSettingsStore.read(c).deleteSharedImages,"new cleanup defaults off on existing installs");
  check(WorkspaceSettingsStore.write(c,WorkspaceSettingsStore.read(c).withDeleteSharedImages(true)),"cleanup option saves");
  check(WorkspaceSettingsStore.decode(WorkspaceSettingsStore.bundle(WorkspaceSettingsStore.read(c))).deleteSharedImages,"cleanup reaches provider clients");
  check(!WorkspaceSettingsStore.decode(new Bundle()).deleteSharedImages,"old provider snapshots never enable cleanup");
  check(WorkspaceSettingsStore.write(c,WorkspaceSettingsStore.read(c).withDeleteSharedImages(false)),"cleanup can be disabled");
  check(!WorkspaceSettingsStore.read(c).deleteSharedImages,"disabled cleanup survives reload");
  System.out.println("production settings migration, lock preference and gesture gate checks="+checks+" passed");
 }
}'''
}
paths = []
for name, body in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf-8')
    paths.append(str(path))
paths += [str(src / name) for name in [
    'WorkspaceOptions.java', 'WorkspaceSettingsStore.java', 'EntryGesturePolicy.java',
    'EntryGestureSettings.java', 'Actions.java', 'Packages.java']]
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), *paths], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.SettingsRegression'], check=True)
