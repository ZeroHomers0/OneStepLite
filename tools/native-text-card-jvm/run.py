"""Replay native text payload filtering and authenticated source-window admission."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2]
src=package_dir(root)
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=(src/'NativeContentDragCard.java').read_text(encoding='utf-8-sig')
def member(signature):
    start=source.index(signature);end=source.index('{',start)+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
methods='\n'.join(member(x) for x in ['    private static void replace(', '    private static String plainText('])
files={
'android/os/IBinder.java':'package android.os;public interface IBinder{}',
'android/os/IInterface.java':'package android.os;public interface IInterface{IBinder asBinder();}',
'android/content/ClipDescription.java':'''package android.content;public class ClipDescription{public boolean text=true;public int offset;
public ClipDescription(){}public ClipDescription(ClipDescription d){text=d.text;offset=d.offset;}public boolean hasMimeType(String t){return "image/*".equals(t)?!text:text;}}''',
'android/content/ClipData.java':'''package android.content;public class ClipData{public ClipDescription d;public Item item;public int count=1;
public ClipData(ClipDescription d,Item item){this.d=d;this.item=item;}public ClipDescription getDescription(){return d;}
public int getItemCount(){return count;}public Item getItemAt(int i){return item;}
public static class Item{public String text;public android.net.Uri uri;public Object intent;public Item(String t){text=t;}
public CharSequence getText(){return text;}public android.net.Uri getUri(){return uri;}public Object getIntent(){return intent;}}}''',
'android/content/Context.java':'''package android.content;public class Context{public static class Metrics{public float density=3;}
public static class Resources{public Metrics getDisplayMetrics(){return new Metrics();}}public Resources getResources(){return new Resources();}}''',
'android/net/Uri.java':'package android.net;public class Uri{public String scheme="content";public String getScheme(){return scheme;}}',
'android/graphics/Point.java':'package android.graphics;public class Point{public int x,y;}',
'android/view/View.java':'package android.view;public class View{public static final int DRAG_FLAG_GLOBAL=256;}',
'android/view/WindowManager.java':'''package android.view;public class WindowManager{public static class LayoutParams{
public static final int FLAG_SECURE=8192;public String packageName="source.app";public int flags;}}''',
'android/view/SurfaceControl.java':'package android.view;public class SurfaceControl{}',
'android/util/Log.java':'package android.util;public class Log{public static int i(String t,String s){return 0;}}',
'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;public class XC_MethodHook{
public static class MethodHookParam{public Object[] args;public void setObjectExtra(String k,Object v){}}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers{
public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
public static Object callMethod(Object o,String n){try{return o.getClass().getMethod(n).invoke(o);}catch(Exception e){throw new RuntimeException(e);}}}''',
'io/github/zerohomers0/onesteplite/NativeTextRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.content.*;import android.graphics.Point;import android.view.*;import android.os.*;import java.util.*;
import de.robv.android.xposed.XC_MethodHook;import de.robv.android.xposed.XposedHelpers;
class Tags{static final String PROBE="test";}class Packages{static final String SYSTEM_UI="com.android.systemui";}
class ContentInboxProvider{static final String MODULE="module";}
class ContentDragShadow{ContentDragShadow(String t,float d){}static int offset(ClipDescription d){return d.offset;}
void recycleThumbnail(){}void mark(ClipData c){c.d.offset=86;}void onProvideShadowMetrics(Point size,Point touch){size.x=128;size.y=92;touch.x=64;touch.y=89;}}
public class NativeTextRegression{
static final String ORIGINAL="original",REPLACEMENT="replacement";
static int imageCalls;static ContentDragShadow imageCard(SurfaceControl s,Context c){imageCalls++;return new ContentDragShadow("image",3);}
static SurfaceControl draw(ContentDragShadow c,Point s){return new SurfaceControl();}
__METHODS__
public static class Token implements IBinder{}public static class Client implements IInterface{public IBinder token=new Token();public IBinder asBinder(){return token;}}
public static class Task{public int mode=1;public int getWindowingMode(){return mode;}}
public static class Wm{public Map<IBinder,Object> mWindowMap=new HashMap<>();public Object mGlobalLock=new Object();public Context mContext=new Context();}
public static class Display{public Wm mWmService=new Wm();}
public static class Win{public Display display;public int mOwnerUid=10001;public boolean visible=true;public Task task=new Task();
public WindowManager.LayoutParams mAttrs=new WindowManager.LayoutParams();public Object getDisplayContent(){return display;}
public boolean isVisible(){return visible;}public Object getTask(){return task;}}
static int checks;static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
static XC_MethodHook.MethodHookParam call(Client client,ClipData clip){XC_MethodHook.MethodHookParam c=new XC_MethodHook.MethodHookParam();
c.args=new Object[]{1,10001,client,256,new SurfaceControl(),0,0,0,100f,100f,3f,4f,clip};return c;}
static void reject(Display d,Client client,ClipData clip){XC_MethodHook.MethodHookParam c=call(client,clip);Object old=c.args[4];replace(c,d);check(c.args[4]==old&&c.args[12]==clip);}
public static void main(String[] args){
Display d=new Display();Client client=new Client();Win win=new Win();win.display=d;d.mWmService.mWindowMap.put(client.token,win);
ClipData clip=new ClipData(new ClipDescription(),new ClipData.Item("你好 😀"));
check(plainText(clip).equals("你好 😀"));clip.count=2;check(plainText(clip)==null);clip.count=1;
clip.item.uri=new android.net.Uri();check(plainText(clip)==null);clip.item.uri=null;
clip.item.intent=new Object();check(plainText(clip)==null);clip.item.intent=null;
clip.d.text=false;check(plainText(clip)==null);clip.d.text=true;
clip.item.text="x".repeat(100001);check(plainText(clip)==null);clip.item.text="你好 😀";
reject(null,client,clip);win.visible=false;reject(d,client,clip);win.visible=true;
win.mOwnerUid=10002;reject(d,client,clip);win.mOwnerUid=110001;reject(d,client,clip);win.mOwnerUid=10001;
win.mAttrs.flags=8192;reject(d,client,clip);win.mAttrs.flags=0;
win.mAttrs.packageName=Packages.SYSTEM_UI;reject(d,client,clip);win.mAttrs.packageName="module";reject(d,client,clip);win.mAttrs.packageName="source.app";
win.task.mode=5;reject(d,client,clip);win.task.mode=1;
clip.d.offset=86;reject(d,client,clip);clip.d.offset=0;
XC_MethodHook.MethodHookParam call=call(client,clip);Object old=call.args[4];replace(call,d);
check(call.args[4]!=old);check((Float)call.args[10]==64f&&(Float)call.args[11]==89f);
check(call.args[12]!=clip&&((ClipData)call.args[12]).d.offset==86&&clip.d.offset==0);
check(((ClipData)call.args[12]).item==clip.item);
clip.d.text=false;clip.item.uri=new android.net.Uri();win.mAttrs.flags=8192;reject(d,client,clip);check(imageCalls==0);
win.mAttrs.flags=0;clip.item.uri.scheme="file";reject(d,client,clip);check(imageCalls==0);
clip.item.uri.scheme="content";call=call(client,clip);old=call.args[4];replace(call,d);check(imageCalls==1&&call.args[4]!=old);
System.out.println("Production native text card checks="+checks+" passed (surface rendering requires device)");
}}
'''.replace('__METHODS__',methods)
}
paths=[]
for name,body in files.items():
    path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf-8');paths.append(str(path))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths,str(src/'ContentSharePolicy.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.NativeTextRegression'],check=True)
