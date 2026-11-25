"""Exercise real shortcut provider ownership, persistence, and client failure handling."""
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
files = {
'android/net/Uri.java': 'package android.net;public record Uri(String value) {public static Uri parse(String value){return new Uri(value);}}',
'android/os/ParcelFileDescriptor.java': 'package android.os;public class ParcelFileDescriptor {}',
'android/os/Bundle.java': '''package android.os;import java.util.*;public class Bundle {
private final Map<String,Object> data=new HashMap<>();public void putString(String k,String v){data.put(k,v);}
public String getString(String k){return (String)data.get(k);}public void putBoolean(String k,boolean v){data.put(k,v);}
public boolean getBoolean(String k){return Boolean.TRUE.equals(data.get(k));}}''',
'android/os/Binder.java': 'package android.os;public class Binder {public static int uid=12345;public static int getCallingUid(){return uid;}}',
'android/os/Process.java': 'package android.os;public class Process {public static final int SYSTEM_UID=1000;public static int myUid(){return 12345;}}',
'android/database/Cursor.java': 'package android.database;public interface Cursor {}',
'android/content/ContentValues.java': 'package android.content;public class ContentValues {}',
'android/content/pm/PackageManager.java': '''package android.content.pm;public class PackageManager {
public String[] getPackagesForUid(int uid){return uid==10042||uid==110042?new String[]{"com.android.systemui"}:new String[]{"untrusted"};}}''',
'android/content/SharedPreferences.java': '''package android.content;import java.util.*;public class SharedPreferences {
public Map<String,String> data=new HashMap<>();public boolean commit=true;public String getString(String k,String fallback){return data.getOrDefault(k,fallback);}
public Editor edit(){return new Editor();}public class Editor {String key,value;
public Editor putString(String k,String v){key=k;value=v;return this;}public boolean commit(){if(commit)data.put(key,value);return commit;}}}''',
'android/content/ContentResolver.java': '''package android.content;public class ContentResolver {
public io.github.zerohomers0.onesteplite.WorkspaceSettingsProvider provider;public int notifications;
public android.os.Bundle call(android.net.Uri uri,String method,String arg,android.os.Bundle data){return provider.call(method,arg,data);}
public void notifyChange(android.net.Uri uri,Object observer){notifications++;}}''',
'android/content/Context.java': '''package android.content;public class Context {
public static final int MODE_PRIVATE=0;public SharedPreferences prefs=new SharedPreferences();public ContentResolver resolver=new ContentResolver();
public Context createDeviceProtectedStorageContext(){return this;}public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
public ContentResolver getContentResolver(){return resolver;}public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}}''',
'android/content/ContentProvider.java': '''package android.content;public abstract class ContentProvider {
public Context context;public Context getContext(){return context;}public abstract boolean onCreate();
public android.os.Bundle call(String m,String a,android.os.Bundle b){return null;}
public android.os.ParcelFileDescriptor openFile(android.net.Uri u,String mode)throws java.io.FileNotFoundException{return null;}
public abstract String getType(android.net.Uri uri);public abstract android.database.Cursor query(android.net.Uri u,String[] p,String s,String[] a,String o);
public abstract android.net.Uri insert(android.net.Uri u,ContentValues v);public abstract int delete(android.net.Uri u,String s,String[] a);
public abstract int update(android.net.Uri u,ContentValues v,String s,String[] a);}''',
'io/github/zerohomers0/onesteplite/Packages.java': 'package io.github.zerohomers0.onesteplite;class Packages {static final String SYSTEM_UI="com.android.systemui";}',
'io/github/zerohomers0/onesteplite/WorkspaceBackground.java': '''package io.github.zerohomers0.onesteplite;class WorkspaceBackground {
static final android.net.Uri URI=android.net.Uri.parse("background");
static android.os.ParcelFileDescriptor open(android.content.Context c){return new android.os.ParcelFileDescriptor();}}''',
'io/github/zerohomers0/onesteplite/ShareActions.java': 'package io.github.zerohomers0.onesteplite;class ShareActions {static android.os.Bundle settings(android.content.Context c){return new android.os.Bundle();}}',
'io/github/zerohomers0/onesteplite/WorkspaceSettingsStore.java': '''package io.github.zerohomers0.onesteplite;class WorkspaceSettingsStore {
static final String READ="read";static final android.net.Uri URI=android.net.Uri.parse("options");
static Object read(android.content.Context c){return null;}static android.os.Bundle bundle(Object options){return new android.os.Bundle();}}''',
'io/github/zerohomers0/onesteplite/ShortcutSettingsRegression.java': '''package io.github.zerohomers0.onesteplite;
import android.content.*;import android.os.*;
public class ShortcutSettingsRegression {
static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
static void denied(Runnable call,String message){boolean rejected=false;try{call.run();}catch(RuntimeException expected){rejected=true;}check(rejected,message);}
static void rejectedFile(WorkspaceSettingsProvider p,int uid,android.net.Uri uri,String mode){
Binder.uid=uid;boolean rejected=false;try{p.openFile(uri,mode);}catch(SecurityException|java.io.FileNotFoundException expected){rejected=true;}
check(rejected,"unexpected background access "+uid+" "+mode);}
public static void main(String[] args)throws Exception{
Context context=new Context();WorkspaceSettingsProvider provider=new WorkspaceSettingsProvider();provider.context=context;context.resolver.provider=provider;
check(provider.onCreate(),"provider initializes");
Binder.uid=10042;check(provider.openFile(WorkspaceBackground.URI,"r")!=null,"same-user SystemUI can read background");
rejectedFile(provider,10042,WorkspaceBackground.URI,"rw");
rejectedFile(provider,110042,WorkspaceBackground.URI,"r");
rejectedFile(provider,12346,WorkspaceBackground.URI,"r");
rejectedFile(provider,12345,android.net.Uri.parse("options"),"r");
Binder.uid=12345;
check(ShortcutSettingsClient.read(context,"apps")==null,"missing app override preserves legacy lookup");
check(ShortcutSettingsClient.read(context,"share")==null,"missing share override preserves legacy lookup");
check(ShortcutSettingsClient.readForUser(context,"apps",10)==null,"foreign-user quick pins retain their legacy lookup");
check(Boolean.TRUE.equals(ShortcutSettingsClient.write(context,"apps","a/A\\nb/B")),"module can save app selection");
check("a/A\\nb/B".equals(ShortcutSettingsClient.read(context,"apps")),"app order persists exactly");
check(context.resolver.notifications==2,"successful save notifies shortcut and Host channels once");
check(ShortcutSettingsClient.read(context,"share")==null,"app save never changes share selection");
check(Boolean.TRUE.equals(ShortcutSettingsClient.write(context,"share","s/S")),"module can save share selection");
Binder.uid=10042;
check(ShortcutSettingsClient.write(context,"share","other/S")==null,"same-user SystemUI reads but cannot edit the module selection");
check("s/S".equals(ShortcutSettingsClient.read(context,"share")),"settings and SystemUI read the same share value");
check("a/A\\nb/B".equals(ShortcutSettingsClient.read(context,"apps")),"share save never changes app order");
Binder.uid=android.os.Process.myUid();
check(Boolean.TRUE.equals(ShortcutSettingsClient.write(context,"apps","")),"explicit empty selection is valid");
check("".equals(ShortcutSettingsClient.read(context,"apps")),"empty override remains distinct from absent legacy override");
context.prefs.commit=false;int notifications=context.resolver.notifications;
check(Boolean.FALSE.equals(ShortcutSettingsClient.write(context,"apps","lost/A")),"commit failure reaches caller");
check("".equals(ShortcutSettingsClient.read(context,"apps")),"failed commit preserves saved selection");
check(context.resolver.notifications==notifications,"failed commit never notifies Host");context.prefs.commit=true;
denied(()->ShortcutSettingsClient.read(context,"other"),"unknown shortcut kind rejected");
check(ShortcutSettingsClient.write(context,"apps","x".repeat(32769))==null,"oversized payload rejected");
check(ShortcutSettingsClient.write(context,"apps",("a/A\\n").repeat(257))==null,"oversized entry count rejected");
Binder.uid=2000;denied(()->ShortcutSettingsClient.read(context,"apps"),"untrusted caller cannot read pins");
check(ShortcutSettingsClient.write(context,"share","bad/A")==null,"untrusted caller cannot overwrite pins");
Binder.uid=110042;denied(()->ShortcutSettingsClient.read(context,"share"),"another user's SystemUI cannot read this user's pins");
check(ShortcutSettingsClient.write(context,"share","bad/A")==null,"another user's SystemUI cannot overwrite pins");
Binder.uid=1000;check("s/S".equals(ShortcutSettingsClient.read(context,"share")),"same-user system identity remains supported");
Binder.uid=12345;denied(()->provider.call("write_options",null,null),"existing options remain read only over IPC");
System.out.println("Production shortcut settings checks="+checks+" passed");
}}''',
}
paths = []
for name, body in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf-8')
    paths.append(str(path))
paths += [str(src / 'WorkspaceSettingsProvider.java'), str(src / 'ShortcutSettingsStore.java'),
          str(src / 'ShortcutSettingsClient.java')]
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), *paths], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.ShortcutSettingsRegression'], check=True)
