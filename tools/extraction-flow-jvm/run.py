"""Replay real extraction traversal, format bounds, caller authentication and content-free diagnostic records."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/net/Uri.java':'package android.net;public class Uri{public static Uri parse(String value){return new Uri();}}',
'android/os/Bundle.java':'''package android.os;public class Bundle extends java.util.HashMap<String,Object>{public Bundle(){}public Bundle(Bundle b){super(b);}public void putAll(Bundle b){super.putAll(b);}public void putIntArray(String k,int[] v){put(k,v);}public void putFloatArray(String k,float[] v){put(k,v);}public void putString(String k,String v){put(k,v);}public String getString(String k){return (String)get(k);}public String[] getStringArray(String k){return (String[])get(k);}public void putByteArray(String k,byte[] v){put(k,v);}public void putBoolean(String k,boolean v){put(k,v);}public boolean getBoolean(String k){return Boolean.TRUE.equals(get(k));}}''',
'android/os/Binder.java':'''package android.os;public class Binder{public static int uid=10100;public static int getCallingUid(){return uid;}public static long clearCallingIdentity(){int old=uid;uid=20200;return old;}public static void restoreCallingIdentity(long old){uid=(int)old;}}''',
'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long now=100;public static long uptimeMillis(){return now;}}',
'android/graphics/PointF.java':'package android.graphics;public class PointF{public final float x,y;public PointF(float x,float y){this.x=x;this.y=y;}}',
'android/graphics/Rect.java':'package android.graphics;public class Rect{public final int left,top,right,bottom;public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}public boolean isEmpty(){return left>=right||top>=bottom;}}',
'android/graphics/Bitmap.java':'''package android.graphics;public class Bitmap{public int width=32,height=16;public boolean recycled,transparent;public int dot=-1;public enum Config{HARDWARE,SOFTWARE}public Config getConfig(){return Config.SOFTWARE;}public boolean hasAlpha(){return true;}public int getPixel(int x,int y){return transparent&&x+y*width!=dot?0:0xff000000;}public void getPixels(int[] row,int offset,int stride,int x,int y,int w,int h){for(int i=0;i<w;i++)row[offset+i]=getPixel(x+i,y);}public int getWidth(){return width;}public int getHeight(){return height;}public boolean isRecycled(){return recycled;}public void recycle(){recycled=true;}public static Bitmap createScaledBitmap(Bitmap b,int w,int h,boolean f){if(w==b.width&&h==b.height)return b;Bitmap v=new Bitmap();v.width=w;v.height=h;return v;}}''',
'android/content/Context.java':'''package android.content;public class Context{public static Context current;public boolean authorized=true;public int statuses,offers;public boolean live=true,preferImage;public String readySource;public int reject;public java.util.List<android.os.Bundle> payloads=new java.util.ArrayList<>();
public Manager getPackageManager(){return new Manager();}public class Manager{public String[] getPackagesForUid(int uid){return new String[]{authorized?"source.app":"foreign"};}}
public Resolver getContentResolver(){return new Resolver();}public class Resolver{public android.os.Bundle call(Object u,String method,String arg,android.os.Bundle b){
if(android.os.Binder.uid!=20200)throw new AssertionError("Binder identity not cleared");if(method.equals("portal_status")){statuses++;if(!live)return null;android.os.Bundle state=new android.os.Bundle();state.putString("gesture","fixed");state.putBoolean("prefer_image",preferImage);state.putString("ready_image_source",readySource);return state;}
offers++;payloads.add(b);android.os.Bundle result=new android.os.Bundle();result.put("accepted",offers>reject);return result;}}}''',
'android/util/Log.java':'package android.util;public class Log{public static java.util.List<String> logs=new java.util.ArrayList<>();public static int i(String t,String v){logs.add(v);return 0;}public static int w(String t,String s,Throwable e){return 0;}}',
'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;public class XC_MethodHook{
protected void beforeHookedMethod(MethodHookParam p){}protected void afterHookedMethod(MethodHookParam p){}
public void fire(MethodHookParam p){beforeHookedMethod(p);}public void complete(MethodHookParam p){afterHookedMethod(p);}
public static class MethodHookParam{public Object[] args;public Object thisObject;public boolean handled,failed;
private final java.util.Map<String,Object> extras=new java.util.HashMap<>();
public void setResult(Object v){handled=true;}public boolean hasThrowable(){return failed;}
public void setObjectExtra(String key,Object value){extras.put(key,value);}public Object getObjectExtra(String key){return extras.get(key);}}}''',
'de/robv/android/xposed/XposedBridge.java':'''package de.robv.android.xposed;public class XposedBridge{public static XC_MethodHook hook;
public static final java.util.Map<String,XC_MethodHook> hooks=new java.util.HashMap<>();
public static java.util.Set<Object> hookAllMethods(Class<?> c,String n,XC_MethodHook h){hook=h;hooks.put(n,h);return java.util.Set.of(h);}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers{public static Class<?> findClass(String n,ClassLoader l){return Object.class;}public static Object callStaticMethod(Class<?> c,String n){return android.content.Context.current;}public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new IllegalArgumentException(e);}}public static Object callMethod(Object o,String n,Object... args){try{Class<?>[] types=args.length==0?new Class<?>[0]:new Class<?>[]{String.class};return o.getClass().getMethod(n,types).invoke(o,args);}catch(Exception e){throw new IllegalArgumentException(e);}}}''',
'io/github/zerohomers0/onesteplite/ExtractionRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.os.*;import android.content.*;import android.graphics.*;import de.robv.android.xposed.*;import java.util.*;
class ContentInboxProvider{static final Object ROOT=new Object();}class Tags{static final String PROBE="test";}class CaptureImageEncoder{static byte[] encode(Bitmap b){return new byte[]{1,2,3};}}
public class ExtractionRegression{public static class Injector{public String getPkgName(){return "source.app";}}public static class Result{public Map<Object,Object> mResultMap=new LinkedHashMap<>();public Injector getInjectorToken(){return new Injector();}}
public static class Pick {public Object image=Map.of("image_pick_mode",bundle("imgUrl","https://example.invalid/image"));
public Injector getInjectorToken(){return new Injector();}public Object getParams(String key){return image;}}
static XC_MethodHook.MethodHookParam params(Object receiver,Object arg){var p=new XC_MethodHook.MethodHookParam();p.thisObject=receiver;p.args=new Object[]{arg};return p;}
static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}static Bundle bundle(String key,Object value){Bundle b=new Bundle();b.put(key,value);return b;}
static XC_MethodHook.MethodHookParam offer(Context c,Result r){Context.current=c;Binder.uid=10100;var p=new XC_MethodHook.MethodHookParam();p.args=new Object[]{r};XposedBridge.hook.fire(p);return p;}
public static void main(String[] args){SystemExtractHooks.install(null);Context c=new Context();Result r=new Result();
r.mResultMap.put("com.miui.contentextension#first#pick_mode",bundle("viewContent",new Object()));r.mResultMap.put("com.miui.contentextension#second#pick_mode",bundle("viewContent","valid text"));
check(offer(c,r).handled,"malformed first candidate does not hide later valid text");check(c.statuses==1&&c.offers==1,"one gesture lookup for bounded traversal");check(c.payloads.get(0).getString("text").equals("valid text"),"correct accepted payload");check(Binder.uid==10100,"identity restored after relay");
c=new Context();c.reject=1;r=new Result();r.mResultMap.put("com.miui.contentextension#a#pick_mode","first");r.mResultMap.put("com.miui.contentextension#b#pick_mode","second");check(offer(c,r).handled&&c.offers==2,"rejected candidate does not suppress later accepted result");
c=new Context();c.authorized=false;check(!offer(c,r).handled&&c.offers==0&&c.statuses==0,"foreign original caller cannot enter relay");
c=new Context();c.live=false;check(!offer(c,r).handled&&c.statuses==1&&c.offers==0,"inactive workspace leaves OEM behavior intact");
c=new Context();r=new Result();r.mResultMap.put("other#pick_mode","not ours");check(!offer(c,r).handled&&c.statuses==0,"unrelated callbacks do not query hidden workspace");
c=new Context();c.preferImage=true;r=new Result();
r.mResultMap.put("com.miui.contentextension#a#pick_mode","image caption");
r.mResultMap.put("com.miui.contentextension#b#image_pick_mode",bundle("viewBitmap",new Bitmap()));
check(offer(c,r).handled&&c.offers==1&&c.payloads.get(0).containsKey("image"),"single-finger mixed results prefer the valid image");
c=new Context();c.preferImage=true;r.mResultMap.put("com.miui.contentextension#b#image_pick_mode",bundle("viewBitmap",new Object()));
check(!offer(c,r).handled&&c.offers==1&&"image caption".equals(c.payloads.get(0).getString("text")),"text fallback leaves the unresolved image on the OEM route");
check(c.payloads.get(0).getBoolean("await_image"),"text fallback allows extra time for the OEM image");
c=new Context();c.preferImage=true;r.mResultMap.put("com.miui.contentextension#a#pick_mode","图片");
check(!offer(c,r).handled&&c.offers==0,"the image placeholder cannot consume a mixed extraction");
Injector injector=new Injector();Bundle out=new Bundle();out.putString("gesture","keep");
check(!SystemExtractPayload.append(injector,bundle("viewContent",new Object()),false,out)&&out.size()==1,"failed parse cannot mutate session state");
check(SystemExtractPayload.append(injector,Map.of("viewContent",new String[]{"Hello 😀","next line"}),false,out),"map and text-array alternative supported");check(out.getString("text").equals("Hello 😀\\nnext line"),"full Unicode, spaces and order preserved");
out=new Bundle();check(SystemExtractPayload.append(injector,bundle("pick_mode",bundle("viewContent",List.of("one","two"))),false,out),"bounded wrapper bundle supported");
out=new Bundle();check(!SystemExtractPayload.append(injector,bundle("pick_mode",bundle("pick_mode",bundle("pick_mode",bundle("viewContent","deep")))),false,out),"excessive nesting rejected");
check(!SystemExtractPayload.append(injector,"x".repeat(100001),false,new Bundle()),"oversized text rejected");check(!SystemExtractPayload.append(injector,"  \\n  ",false,new Bundle()),"empty semantic text rejected");
check(!SystemExtractPayload.append(injector,Collections.nCopies(33,"part"),false,new Bundle()),"text list bounded");check(!SystemExtractPayload.append(injector,List.of("one",4),false,new Bundle()),"no arbitrary object toString extraction");
Bitmap image=new Bitmap();out=new Bundle();check(SystemExtractPayload.append(injector,Map.of("viewBitmap",image),true,out),"map bitmap result supported");check(out.containsKey("image")&&!image.recycled,"caller bitmap retained");
check(SystemExtractPayload.append(injector,Map.of("image_pick_mode",image),true,new Bundle()),"known image mode accepts a direct bitmap");
check(SystemExtractPayload.append(injector,bundle("image_pick_mode",Map.of("viewBitmap",image)),true,new Bundle()),"known mode accepts a typed bitmap field inside nested wrappers");
check(SystemExtractPayload.value(Map.of("viewBitmap",new Object(),"image_pick_mode",bundle("viewBitmap",image)),
    "viewBitmap",true,0)==image,"outer metadata cannot hide a wrapped bitmap");
check(SystemExtractPayload.value(Map.of("viewBitmap",new Bitmap(),"image_pick_mode",image),
    "viewBitmap",true,0)==image,"explicit image mode takes priority over an outer bitmap");
check(SystemExtractPayload.value(Map.of("viewBitmap",image,"image_pick_mode",new Object()),
    "viewBitmap",true,0)==image,"a direct bitmap remains usable with an unsupported wrapper");
check(SystemExtractPayload.append(injector,Map.of("pick_mode","wrapped text"),false,new Bundle()),"known text mode accepts direct text");
Bundle configured=bundle("apkName","source.app");configured.put("apkParamList",new String[]{"unrelated","miuiCE_description"});
configured.putString("description","保留原文　");out=new Bundle();
check(SystemExtractPayload.append(injector,Map.of("pick_mode",configured),false,out)
    &&out.getString("text").equals("保留原文　"),"declared OEM text branch preserves original text");
configured.putString("apkName","other.app");
check(!SystemExtractPayload.append(injector,configured,false,new Bundle()),"configured text must match the source package");
configured.putString("apkName","source.app");configured.put("apkParamList",new String[]{"description","miuiCE_missing"});
check(!SystemExtractPayload.append(injector,configured,false,new Bundle()),"unadvertised fields are ignored");
image.recycled=true;check(!SystemExtractPayload.append(injector,image,true,new Bundle()),"recycled bitmap rejected");image=new Bitmap();image.width=4000;image.height=4000;check(!SystemExtractPayload.append(injector,image,true,new Bundle()),"image pixels bounded before encoding");
check(!SystemExtractPayload.append(injector,bundle("uri","content://private/image"),true,new Bundle()),"untrusted returned URI is not dereferenced");
Bitmap emptyImage=new Bitmap();emptyImage.transparent=true;Bundle geometry=bundle("viewBitmap",emptyImage);
geometry.put("view_rect",new Rect(10,20,90,110));geometry.put("touch_points",new PointF[]{new PointF(30,40)});
check(!SystemExtractPayload.append(injector,geometry,true,new Bundle()),"fully transparent image is discarded");
emptyImage.dot=0;check(!SystemExtractPayload.hasNoVisiblePixels(emptyImage),"one visible pixel outside the sample grid is preserved");
out=new Bundle();check(SystemExtractPayload.appendRegion(injector,Map.of("image_pick_mode",geometry),out)
    &&out.getBoolean("region_only")&&!out.containsKey("image"),"geometry-only result can prepare a screenshot");
geometry.put("touch_points",new PointF[]{new PointF(Float.NaN,40)});
check(!SystemExtractPayload.appendRegion(injector,geometry,new Bundle()),"invalid touch geometry cannot prepare a screenshot");
PortalHooks.install(null);Binder.uid=20200;
var pickHook=XposedBridge.hooks.get("onPickImage");var bitmapHook=XposedBridge.hooks.get("setBitmap");
c=new Context();var pick=params(c,new Pick());pickHook.fire(pick);
check(!pick.handled&&c.offers==0,"URL-only payload leaves OEM loading intact");
bitmapHook.complete(params(null,new Bitmap()));pickHook.complete(pick);
check(c.statuses==1&&c.offers==1&&c.payloads.get(0).containsKey("image")&&!pick.handled,
      "resolved bitmap offered once with original gesture and OEM result preserved");
bitmapHook.complete(params(null,new Bitmap()));pick=params(c,new Pick());pickHook.fire(pick);pickHook.complete(pick);
check(c.offers==1,"bitmap written outside this pick cannot leak into it");
pick=params(c,new Pick());pickHook.fire(pick);bitmapHook.complete(params(null,new Bitmap()));
pick.failed=true;pickHook.complete(pick);check(c.offers==1,"failed OEM pick does not offer its bitmap");
Pick direct=new Pick();direct.image=bundle("viewBitmap",new Bitmap());pick=params(c,direct);
pickHook.fire(pick);pickHook.complete(pick);check(pick.handled&&c.offers==2,"direct bitmap is still offered before OEM work");
c.live=false;pick=params(c,new Pick());pickHook.fire(pick);bitmapHook.complete(params(null,new Bitmap()));pickHook.complete(pick);
check(c.offers==2,"resolved bitmap is ignored without an original live gesture");
c.live=true;var textHook=XposedBridge.hooks.get("onPickText");Pick words=new Pick();
words.image=Map.of("pick_mode","  图片  ");pick=params(c,words);textHook.fire(pick);textHook.complete(pick);
check(!pick.handled&&c.offers==2,"Portal image placeholder does not become a text candidate");
words.image=Map.of("pick_mode","查看图片");pick=params(c,words);textHook.fire(pick);textHook.complete(pick);
check(pick.handled&&c.offers==3&&c.payloads.get(2).getString("text").equals("查看图片"),
      "actual text containing the image label is preserved");
c=new Context();c.preferImage=true;c.readySource="source.app";pick=params(c,new Pick());
pickHook.fire(pick);pickHook.complete(pick);
check(pick.handled&&c.offers==0,"an already staged image from this source suppresses the competing OEM sidebar");
c.readySource="other.app";pick=params(c,new Pick());pickHook.fire(pick);pickHook.complete(pick);
check(!pick.handled,"a staged image from another source cannot suppress the OEM sidebar");
c.readySource="source.app";c.preferImage=false;pick=params(c,new Pick());
pickHook.fire(pick);pickHook.complete(pick);
check(!pick.handled,"two-finger image behavior stays on the OEM path");
var postHook=XposedBridge.hooks.get("postToMain");
var sidebarHook=XposedBridge.hooks.get("startPickImageTask");
c=new Context();c.preferImage=true;pick=params(c,new Pick());pickHook.fire(pick);
bitmapHook.complete(params(null,new Bitmap()));
Context owner=c;var sidebar=params(owner,null);
var scheduled=params(new Object(),(Runnable)()->sidebarHook.fire(sidebar));
postHook.fire(scheduled);pickHook.complete(pick);((Runnable)scheduled.args[0]).run();
check(sidebar.handled&&c.offers==1,"a late bitmap is accepted before its queued sidebar opens");
var unrelated=params(owner,null);sidebarHook.fire(unrelated);
check(!unrelated.handled,"sidebar suppression ends with the matching task");
c=new Context();c.preferImage=true;c.reject=1;pick=params(c,new Pick());pickHook.fire(pick);
bitmapHook.complete(params(null,new Bitmap()));var rejected=params(c,null);
scheduled=params(new Object(),(Runnable)()->sidebarHook.fire(rejected));
postHook.fire(scheduled);pickHook.complete(pick);((Runnable)scheduled.args[0]).run();
check(!rejected.handled&&c.offers==1,"rejected late bitmap leaves the queued OEM sidebar intact");
SystemClock.now=100000;ShareDiagnostics.event(ShareDiagnostics.Stage.DELIVERY,ShareDiagnostics.Code.READY);int count=android.util.Log.logs.size();ShareDiagnostics.event(ShareDiagnostics.Stage.DELIVERY,ShareDiagnostics.Code.READY);check(android.util.Log.logs.size()==count,"repeated normal stages are rate-limited");
ShareDiagnostics.event(ShareDiagnostics.Stage.DELIVERY,ShareDiagnostics.Code.FAILED);ShareDiagnostics.event(ShareDiagnostics.Stage.DELIVERY,ShareDiagnostics.Code.FAILED);check(android.util.Log.logs.size()==count+2,"failures remain observable");
for(int i=0;i<80;i++){SystemClock.now++;ShareDiagnostics.event(ShareDiagnostics.Stage.COPY,ShareDiagnostics.Code.FAILED);}String report=ShareDiagnostics.snapshot();check(report.split("\\n").length==64,"diagnostic memory bounded");check(!report.contains("valid text")&&!report.contains("source.app")&&!report.contains("content://"),"diagnostics contain codes only");
System.out.println("Production extraction and diagnostic checks="+checks+" passed");}}
'''
}
paths=[]
for name,body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths += [str(src/'SystemExtractHooks.java'),str(src/'SystemExtractPayload.java'),
str(src/'ContentProtocol.java'),str(src/'PortalHooks.java'),str(src/'ShareDiagnostics.java'),str(src/'ContentSharePolicy.java'),str(src/'ContentCapturePolicy.java')]
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.ExtractionRegression'],check=True)
