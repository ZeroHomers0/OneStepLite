"""Replay production paste admission and native focus matching; no real keyboard injection."""
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
source = (src / 'EditorPasteCompat.java').read_text(encoding='utf-8-sig')
def method(signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]
methods = '\n'.join(method(x) for x in ['    private static Object field(', '        private boolean focused(', '        private Bundle handle('])
files = {
'android/os/IBinder.java': 'package android.os;public interface IBinder {}',
'android/os/IInterface.java': 'package android.os;public interface IInterface {IBinder asBinder();}',
'android/os/SystemClock.java': 'package android.os;public class SystemClock {public static long uptimeMillis(){return 5000;}}',
'android/os/Bundle.java': '''package android.os;public class Bundle extends java.util.HashMap<String,Object>{
public void putInt(String k,int v){put(k,v);}public int getInt(String k){return (Integer)getOrDefault(k,0);}
public void putLong(String k,long v){put(k,v);}public long getLong(String k){return (Long)getOrDefault(k,0L);}
public void putBoolean(String k,boolean v){put(k,v);}public boolean getBoolean(String k){return (Boolean)getOrDefault(k,false);}
public void putString(String k,String v){put(k,v);}public String getString(String k){return (String)get(k);}
public void putBinder(String k,IBinder v){put(k,v);}public IBinder getBinder(String k){return (IBinder)get(k);}}''',
'android/content/ClipData.java': '''package android.content;public class ClipData{public String text;
public static ClipData newPlainText(String label,String value){ClipData c=new ClipData();c.text=value;return c;}}''',
'android/content/ClipboardManager.java': '''package android.content;public class ClipboardManager{public int writes;public String text;
public void setPrimaryClip(ClipData clip){writes++;text=clip.text;}}''',
'android/content/Context.java': '''package android.content;public class Context{public ClipboardManager clipboard=new ClipboardManager();
public <T>T getSystemService(Class<T> type){return type.cast(clipboard);}}''',
'android/view/InputDevice.java': 'package android.view;public class InputDevice{public static final int SOURCE_KEYBOARD=257;}',
'android/view/KeyEvent.java': '''package android.view;public class KeyEvent{public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_PASTE=279,FLAG_FROM_SYSTEM=8;
public int action,display=-1;public KeyEvent(long d,long t,int a,int c,int repeat,int meta,int device,int scan,int flags,int source){action=a;}}''',
'android/util/Log.java': 'package android.util;public class Log{public static int w(String a,String b,Throwable t){return 0;}}',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
import io.github.zerohomers0.onesteplite.PasteRegression;
public class XposedHelpers{
public static Object getObjectField(Object o,String f){try{return o.getClass().getField(f).get(o);}catch(Exception e){throw new RuntimeException(e);}}
public static int getIntField(Object o,String f){return (Integer)getObjectField(o,f);}
public static Class<?> findClass(String n,ClassLoader l){return Object.class;}
public static Object callStaticMethod(Class<?> c,String n,Object...a){return PasteRegression.input;}
public static Object callMethod(Object o,String n,Object...a){
if(n.equals("injectInputEvent"))return PasteRegression.input.send((android.view.KeyEvent)a[0],(Integer)a[1],(Integer)a[2]);
if(n.equals("setDisplayId")){((android.view.KeyEvent)o).display=(Integer)a[0];return null;}
try{return o.getClass().getMethod(n).invoke(o);}catch(Exception e){throw new RuntimeException(e);}}
}''',
'io/github/zerohomers0/onesteplite/PasteRegression.java': '''package io.github.zerohomers0.onesteplite;
import android.os.*;import android.content.*;import android.view.*;import java.util.function.*;
import de.robv.android.xposed.XposedHelpers;
class Tags{static final String PROBE="test";}
class ConversationShareCompat{static Bundle query(Context context,Bundle input){Bundle reply=new Bundle();reply.putBoolean("conversation",true);return reply;}}
public class PasteRegression{
public static class Token implements IBinder{}
public static class Client implements IInterface{public IBinder token=new Token();public IBinder asBinder(){return token;}}
public static class Win{public Client mClient=new Client();public int mOwnerUid=10001;public boolean visible=true;public boolean isVisible(){return visible;}}
public static class Wm{public Object mGlobalLock=new Object();}
public static class Display{public Wm mWmService=new Wm();public Win mCurrentFocus=new Win();}
public static class Input{public int events,uid,mode;public boolean accept=true;
public boolean send(KeyEvent event,int mode,int uid){if(event.display!=0)throw new AssertionError("display");events++;this.uid=uid;this.mode=mode;return accept;}}
public static Input input=new Input();
static class Replay{Context context=new Context();Display target=new Display();Supplier<Object> display=()->target;Bundle state;
Bundle snapshot(){return state;}
__METHODS__
}
static int checks;static void check(boolean condition){checks++;if(!condition)throw new AssertionError("check "+checks);}
static Bundle copy(Bundle b){Bundle result=new Bundle();result.putAll(b);return result;}
public static void main(String[] args){
Replay replay=new Replay();Bundle state=new Bundle();state.putInt("uid",10001);state.putInt("field",7);state.putLong("issued",5000);
state.putBinder("window",replay.target.mCurrentFocus.mClient.token);state.putBinder("editor",new Token());state.putString("text","你好 Hello 😀");replay.state=state;
check(replay.focused(state));replay.target.mCurrentFocus.visible=false;check(!replay.focused(state));replay.target.mCurrentFocus.visible=true;
replay.target.mCurrentFocus.mOwnerUid=10002;check(!replay.focused(state));replay.target.mCurrentFocus.mOwnerUid=10001;
Bundle wrong=copy(state);wrong.putBinder("window",new Token());check(!replay.focused(wrong));
check(replay.handle(1,null)==state);check(replay.handle(2,state).getBoolean("accepted"));
check(replay.context.clipboard.writes==1&&replay.context.clipboard.text.equals("你好 Hello 😀"));
check(input.events==2&&input.uid==10001&&input.mode==2);
String[] mismatches={"uid","field","window","editor","issued","text"};
for(String mismatch:mismatches){Bundle bad=copy(state);
if(mismatch.equals("uid")||mismatch.equals("field"))bad.putInt(mismatch,55);
else if(mismatch.equals("window")||mismatch.equals("editor"))bad.putBinder(mismatch,new Token());
else if(mismatch.equals("issued"))bad.putLong(mismatch,0);else bad.putString(mismatch,"");
check(!replay.handle(2,bad).getBoolean("accepted"));}
check(input.events==2&&replay.context.clipboard.writes==1);
replay.state=null;check(replay.handle(2,state)!=null&&!replay.handle(2,state).getBoolean("accepted"));
check(input.events==2);replay.state=state;input.accept=false;check(!replay.handle(2,state).getBoolean("accepted"));
check(input.events==4);
System.out.println("Production editor paste guard checks="+checks+" passed (input doubles; device paste pending)");
}}
'''.replace('__METHODS__',methods)
}
paths = []
for name, body in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf-8')
    paths.append(str(path))
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths,
                str(src/'EditorPastePolicy.java'),str(src/'ContentSharePolicy.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.PasteRegression'],check=True)
