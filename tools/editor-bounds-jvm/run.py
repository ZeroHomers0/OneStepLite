"""Replay the real authenticated geometry relay and IME monitor restoration; no app text is read."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/os/RemoteException.java':'package android.os;public class RemoteException extends Exception{}',
'android/os/IBinder.java':'''package android.os;public interface IBinder{interface DeathRecipient{void binderDied();}void linkToDeath(DeathRecipient d,int f)throws RemoteException;boolean unlinkToDeath(DeathRecipient d,int f);}''',
'android/os/Binder.java':'''package android.os;public class Binder implements IBinder{public static int uid=10042;public boolean alive=true;java.util.List<DeathRecipient> deaths=new java.util.ArrayList<>();
public static int getCallingUid(){return uid;}public static long clearCallingIdentity(){int old=uid;uid=10021;return old;}public static void restoreCallingIdentity(long value){uid=(int)value;}
public void linkToDeath(DeathRecipient d,int f)throws RemoteException{if(!alive)throw new RemoteException();deaths.add(d);}public boolean unlinkToDeath(DeathRecipient d,int f){return deaths.remove(d);}
public void die(){alive=false;for(var d:new java.util.ArrayList<>(deaths))d.binderDied();}}''',
'android/os/Process.java':'package android.os;public class Process{public static int myUid(){return 10021;}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long now=100;public static long uptimeMillis(){return now;}}',
'android/os/Looper.java':'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'''package android.os;public class Handler{public java.util.List<Runnable> jobs=new java.util.ArrayList<>();public Handler(Looper l){}
public void post(Runnable r){r.run();}public void postDelayed(Runnable r,long ms){jobs.add(r);}public void removeCallbacks(Runnable r){jobs.remove(r);}}''',
'android/os/Bundle.java':'''package android.os;public class Bundle extends java.util.HashMap<String,Object>{public Bundle(){}public Bundle(Bundle other){super(other);}
public void putString(String k,String v){put(k,v);}public String getString(String k){return (String)get(k);}
public void putInt(String k,int v){put(k,v);}public int getInt(String k){return getInt(k,0);}public int getInt(String k,int d){return (Integer)getOrDefault(k,d);}
public void putBinder(String k,IBinder v){put(k,v);}public IBinder getBinder(String k){return (IBinder)get(k);}
public void putFloatArray(String k,float[] v){put(k,v);}public float[] getFloatArray(String k){return (float[])get(k);}
public void putStringArray(String k,String[] v){put(k,v);}public String[] getStringArray(String k){return (String[])get(k);}
public void putParcelable(String k,Object v){put(k,v);}public <T>T getParcelable(String k,Class<T> c){return c.cast(get(k));}}''',
'android/graphics/RectF.java':'''package android.graphics;public class RectF{public float left,top,right,bottom;public RectF(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
public RectF(RectF r){this(r.left,r.top,r.right,r.bottom);}public boolean isEmpty(){return right<=left||bottom<=top;}}''',
'android/graphics/Matrix.java':'''package android.graphics;public class Matrix{public float scale=1,x,y;public void mapRect(RectF r){r.left=r.left*scale+x;r.right=r.right*scale+x;r.top=r.top*scale+y;r.bottom=r.bottom*scale+y;}}''',
'android/content/ComponentName.java':'''package android.content;public record ComponentName(String pkg){public String getPackageName(){return pkg;}
public static ComponentName unflattenFromString(String s){return s==null?null:new ComponentName(s.split("/")[0]);}}''',
'android/content/ClipDescription.java':'''package android.content;public class ClipDescription{String[] types;public ClipDescription(String s,String[] t){types=t;}
public boolean hasMimeType(String t){for(String s:types)if(s.equals(t))return true;return false;}public int getMimeTypeCount(){return types.length;}public String getMimeType(int i){return types[i];}
public static boolean compareMimeTypes(String t,String accepted){return t.equals(accepted)||accepted.equals("*/*")||accepted.endsWith("/*")&&t.startsWith(accepted.substring(0,accepted.length()-1));}}''',
'android/content/Intent.java':'''package android.content;public class Intent{public android.os.Bundle data=new android.os.Bundle();public boolean active;public String pkg;
public Intent(String action){}public Intent setPackage(String p){pkg=p;return this;}public Intent putExtra(String k,String v){data.putString(k,v);return this;}
public Intent putExtra(String k,boolean v){active=v;return this;}public Intent putExtras(android.os.Bundle b){data.putAll(b);return this;}
public android.os.Bundle getExtras(){return data;}public String getStringExtra(String k){return data.getString(k);}public boolean getBooleanExtra(String k,boolean d){return active;}}''',
'android/content/IntentFilter.java':'package android.content;public class IntentFilter{public IntentFilter(String action){}}',
'android/content/BroadcastReceiver.java':'package android.content;public abstract class BroadcastReceiver{public abstract void onReceive(Context c,Intent i);}',
'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager{public static class NameNotFoundException extends Exception{}
public static class Info{public int uid;}public Info getApplicationInfo(String pkg,int flags)throws NameNotFoundException{Info result=new Info();result.uid=pkg.equals("ime")?12000:13000;return result;}}''',
'android/content/ContentResolver.java':'''package android.content;public class ContentResolver{public java.util.List<android.os.Bundle> packets=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
public android.os.Bundle call(Object u,String m,String a,android.os.Bundle data){packets.add(new android.os.Bundle(data));return new android.os.Bundle();}}''',
'android/content/Context.java':'''package android.content;public class Context{public static final int RECEIVER_EXPORTED=2;public String pkg="module",permission;
public BroadcastReceiver receiver;public ContentResolver resolver=new ContentResolver();public java.util.List<Intent> broadcasts=new java.util.ArrayList<>();
public Context getApplicationContext(){return this;}public String getPackageName(){return pkg;}public ContentResolver getContentResolver(){return resolver;}
public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}public void sendBroadcast(Intent i){broadcasts.add(i);}
public void registerReceiver(BroadcastReceiver r,IntentFilter f,String p,Object h,int flags){receiver=r;permission=p;}public void unregisterReceiver(BroadcastReceiver r){receiver=null;}}''',
'android/provider/Settings.java':'package android.provider;public class Settings{public static class Secure{public static final String DEFAULT_INPUT_METHOD="ime";public static String current="ime/Service";public static String getString(android.content.ContentResolver c,String key){return current;}}}',
'android/view/inputmethod/InputConnection.java':'package android.view.inputmethod;public interface InputConnection{int CURSOR_UPDATE_IMMEDIATE=1,CURSOR_UPDATE_MONITOR=2,CURSOR_UPDATE_FILTER_EDITOR_BOUNDS=4;boolean requestCursorUpdates(int mode,int filter);}',
'android/view/inputmethod/EditorInfo.java':'package android.view.inputmethod;public class EditorInfo{public int fieldId=17;}',
'android/view/inputmethod/InputBinding.java':'package android.view.inputmethod;public class InputBinding{public int getUid(){return 10001;}}',
'android/view/inputmethod/EditorBoundsInfo.java':'package android.view.inputmethod;public class EditorBoundsInfo{public android.graphics.RectF rect;public android.graphics.RectF getEditorBounds(){return rect;}}',
'android/view/inputmethod/CursorAnchorInfo.java':'package android.view.inputmethod;public class CursorAnchorInfo{public EditorBoundsInfo editor=new EditorBoundsInfo();public android.graphics.Matrix matrix=new android.graphics.Matrix();public EditorBoundsInfo getEditorBoundsInfo(){return editor;}public android.graphics.Matrix getMatrix(){return matrix;}}',
'android/inputmethodservice/InputMethodService.java':'''package android.inputmethodservice;public class InputMethodService extends android.content.Context{
public android.view.inputmethod.InputConnection connection;public android.view.inputmethod.EditorInfo editor=new android.view.inputmethod.EditorInfo();
public InputMethodService(){pkg="ime";}public android.view.inputmethod.InputConnection getCurrentInputConnection(){return connection;}
public android.view.inputmethod.EditorInfo getCurrentInputEditorInfo(){return editor;}public android.view.inputmethod.InputBinding getCurrentInputBinding(){return new android.view.inputmethod.InputBinding();}}''',
'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;public class XC_MethodHook{protected void beforeHookedMethod(MethodHookParam c)throws Throwable{}protected void afterHookedMethod(MethodHookParam c)throws Throwable{}
public static class MethodHookParam{public Object thisObject,result;public Object[] args;public Object getResult(){return result;}}}''',
'de/robv/android/xposed/XposedBridge.java':'''package de.robv.android.xposed;public class XposedBridge{static java.util.Map<Class<?>,java.util.List<XC_MethodHook>> hooks=new java.util.HashMap<>();
public static void hookAllMethods(Class<?> c,String name,XC_MethodHook h){if(name.equals("requestCursorUpdates"))hooks.computeIfAbsent(c,k->new java.util.ArrayList<>()).add(h);}
public static void fired(Object o,Object[] args,boolean result){for(var e:hooks.entrySet())if(e.getKey().isInstance(o))for(var h:e.getValue())try{
var call=new XC_MethodHook.MethodHookParam();call.thisObject=o;call.args=args;call.result=result;h.afterHookedMethod(call);}catch(Throwable error){throw new RuntimeException(error);}}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers{
static java.util.Map<Object,java.util.Map<String,Object>> extras=new java.util.WeakHashMap<>();public static Object getAdditionalInstanceField(Object o,String k){return extras.getOrDefault(o,java.util.Map.of()).get(k);}
public static void setAdditionalInstanceField(Object o,String k,Object v){extras.computeIfAbsent(o,x->new java.util.HashMap<>()).put(k,v);}
public static Class<?> findClassIfExists(String name,ClassLoader l){return io.github.zerohomers0.onesteplite.EditorBoundsRegression.Connection.class;}
public static Class<?> findClass(String name,ClassLoader l){return Object.class;}
public static Object getObjectField(Object o,String k){try{return o.getClass().getField(k).get(o);}catch(Exception e){throw new RuntimeException(e);}}}''',
'io/github/zerohomers0/onesteplite/EditorBoundsRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.os.*;import android.content.*;import android.graphics.*;import android.inputmethodservice.*;import android.view.inputmethod.*;
class ContentInboxProvider{static final Object ROOT=new Object();}
class EditorPasteCompat{static Bundle current;static Bundle relay(Context c,String m,Bundle b){return current==null?null:new Bundle(current);}}
public class EditorBoundsRegression{
static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
static Bundle state(){Bundle b=new Bundle();b.putInt("uid",10001);b.putInt("field",17);b.putBinder("window",new Binder());b.putBinder("editor",new Binder());b.putFloatArray("geometry_transform",new float[]{.75f,100,500});return b;}
static Bundle start(Context c,Binder life,String mime){Bundle in=new Bundle();in.putParcelable("types",new ClipDescription("drag",new String[]{mime}));in.putBinder("owner",life);return EditorBoundsRelay.call(c,"editor_bounds_start",in);}
static Bundle packet(String id,RectF r){Bundle p=new Bundle();p.putString("ticket",id);p.putInt("uid",10001);p.putInt("field",17);if(r!=null)p.putParcelable("bounds",r);return p;}
static void offer(Context c,Bundle p){Binder.uid=12000;EditorBoundsRelay.call(c,"editor_bounds_offer",p);Binder.uid=10042;}
static RectF read(Context c,String id){Bundle p=new Bundle();p.putString("ticket",id);return EditorBoundsRelay.call(c,"editor_bounds_read",p).getParcelable("bounds",RectF.class);}
static void denied(Runnable r,String why){boolean failed=false;try{r.run();}catch(SecurityException expected){failed=true;}check(failed,why);}
public static class Connection implements InputConnection{public int mode,filter,calls;boolean accept=true;
public boolean requestCursorUpdates(int m,int f){calls++;if(accept){mode=m;filter=f;}de.robv.android.xposed.XposedBridge.fired(this,new Object[]{m,f},accept);return accept;}}
static Object publisher(InputMethodService s){return de.robv.android.xposed.XposedHelpers.getAdditionalInstanceField(s,"editor_bounds_publisher");}
static void invoke(Object o,String method,Class<?> type,Object arg)throws Exception{var m=o.getClass().getDeclaredMethod(method,type);m.setAccessible(true);m.invoke(o,arg);}
static Intent command(String token,boolean on,Binder owner){Bundle b=new Bundle();b.putBinder("owner",owner);return new Intent(EditorBoundsRelay.ACTION).putExtra("ticket",token).putExtra("active",on).putExtras(b);}
public static void main(String[] args)throws Exception{
Context c=new Context();EditorPasteCompat.current=state();Binder life=new Binder();String id=start(c,life,"text/plain").getString("ticket");
check(id!=null&&c.broadcasts.get(0).active,"verified editor arms only current IME");
Bundle p=packet(id,new RectF(0,1800,900,1900));Binder.uid=13333;denied(()->EditorBoundsRelay.call(c,"editor_bounds_offer",p),"foreign geometry publisher rejected");
offer(c,p);RectF r=read(c,id);check(r!=null&&r.left==100&&r.top==1850&&r.right==775&&r.bottom==1925,"native magnification applied exactly once");
offer(c,packet("wrong",new RectF(1,1,2,2)));check(read(c,id).left==100,"stale ticket cannot overwrite newer geometry");
Bundle wrong=packet(id,new RectF(1,1,2,2));wrong.putInt("field",99);offer(c,wrong);check(read(c,id)==null,"different editor clears feedback");
offer(c,p);EditorPasteCompat.current.putBinder("editor",new Binder());check(read(c,id)==null,"same field ID with new connection rejected");
EditorPasteCompat.current=state();life=new Binder();id=start(c,life,"text/plain").getString("ticket");offer(c,packet(id,new RectF(Float.NaN,1,2,3)));check(read(c,id)==null,"invalid bounds never displayed");
offer(c,packet(id,new RectF(0,1800,900,1900)));android.provider.Settings.Secure.current="other/Service";check(read(c,id)==null,"keyboard replacement invalidates old geometry");
android.provider.Settings.Secure.current="ime/Service";life.die();check(read(c,id)==null&&!c.broadcasts.get(c.broadcasts.size()-1).active,"owner death disarms IME and geometry");
EditorPasteCompat.current=state();check(start(c,new Binder(),"image/jpeg").getString("ticket")==null,"unadvertised image acceptance not guessed");
EditorPasteCompat.current.putStringArray("content_types",new String[]{"image/*"});check(start(c,new Binder(),"image/jpeg").getString("ticket")!=null,"advertised image family allowed");
check(start(c,new Binder(),"application/pdf").getString("ticket")==null,"unadvertised file MIME rejected");
check(EditorBoundsRelay.accepts(state(),new ClipDescription("t",new String[]{"text/plain","text/html"})),"plain and styled text share native text target");
Binder.uid=110042;denied(()->EditorBoundsRelay.call(c,"editor_bounds_read",new Bundle()),"cross-user geometry rejected");Binder.uid=10042;
id=start(c,new Binder(),"text/plain").getString("ticket");SystemClock.now=60101;check(read(c,id)==null,"drag capability expires");SystemClock.now=100;
EditorPasteCompat.current=null;check(start(c,new Binder(),"text/plain").getString("ticket")==null,"no verified focus never arms monitor");
InputMethodService service=new InputMethodService();Connection connection=new Connection();service.connection=connection;
EditorBoundsIme.install(EditorBoundsRegression.class.getClassLoader());EditorBoundsIme.attach(service);
check(EditorBoundsRelay.PERMISSION.equals(service.permission),"IME receiver accepts only signature-authorized sender");
connection.requestCursorUpdates(2,16);String token=java.util.UUID.randomUUID().toString();Binder owner=new Binder();
service.receiver.onReceive(service,command(token,true,owner));check(connection.mode==3&&connection.filter==20,"editor bounds added without losing existing cursor filter");
CursorAnchorInfo info=new CursorAnchorInfo();info.editor.rect=new RectF(1,2,10,20);info.matrix.scale=2;info.matrix.x=5;invoke(publisher(service),"offer",CursorAnchorInfo.class,info);
long until=System.currentTimeMillis()+2000;while(service.resolver.packets.isEmpty()&&System.currentTimeMillis()<until)Thread.sleep(2);
Bundle sent=service.resolver.packets.get(0);RectF rect=sent.getParcelable("bounds",RectF.class);
check(rect!=null&&rect.left==7&&rect.right==25,"local editor bounds transformed by cursor matrix");check(sent.size()==4,"only capability, UID, field and rectangle leave IME");
service.receiver.onReceive(service,command(token,false,owner));check(connection.mode==2&&connection.filter==16,"end restores previous monitor configuration");
token=java.util.UUID.randomUUID().toString();service.receiver.onReceive(service,command(token,true,owner));connection.requestCursorUpdates(3,20);
service.receiver.onReceive(service,command(token,false,owner));check(connection.mode==3&&connection.filter==20,"keyboard's newer identical request is not overwritten");
connection.requestCursorUpdates(0,0);token=java.util.UUID.randomUUID().toString();owner=new Binder();service.receiver.onReceive(service,command(token,true,owner));
check(connection.mode==3&&connection.filter==4,"bounds-only monitoring when keyboard had none");owner.die();check(connection.mode==0&&connection.filter==0,"owner death restores no-monitor state");
var close=publisher(service).getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(publisher(service));check(service.receiver==null,"destroy removes IME receiver");
var worker=EditorBoundsIme.class.getDeclaredField("WORKER");worker.setAccessible(true);var executor=(java.util.concurrent.ExecutorService)worker.get(null);executor.shutdown();executor.awaitTermination(2,java.util.concurrent.TimeUnit.SECONDS);
System.out.println("Production editor geometry relay/IME checks="+checks+" passed");}}
'''
}
paths=[]
for name,body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths += [str(src/'EditorBoundsRelay.java'),str(src/'EditorBoundsIme.java')]
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.EditorBoundsRegression'],check=True)
