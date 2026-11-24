"""Replay authenticated delivery receipts and dwell cancellation with deterministic Binder/UI doubles."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/os/RemoteException.java':'package android.os;public class RemoteException extends Exception{}',
'android/os/IBinder.java':'''package android.os;public interface IBinder{int FIRST_CALL_TRANSACTION=1;boolean transact(int c,Parcel d,Parcel r,int f)throws RemoteException;}''',
'android/os/Binder.java':'''package android.os;public class Binder implements IBinder{public static int uid=12345;public static int getCallingUid(){return uid;}
public boolean transact(int c,Parcel d,Parcel r,int f)throws RemoteException{return onTransact(c,d,r,f);}protected boolean onTransact(int c,Parcel d,Parcel r,int f)throws RemoteException{return false;}}''',
'android/os/Parcel.java':'''package android.os;public class Parcel{String descriptor;Bundle bundle;int value;
public static Parcel obtain(){return new Parcel();}public void writeInterfaceToken(String s){descriptor=s;}public void enforceInterface(String s){if(!s.equals(descriptor))throw new SecurityException();}
public void writeBundle(Bundle b){bundle=b;}public Bundle readBundle(ClassLoader l){return bundle;}
public void writeNoException(){}public void readException(){}public void writeInt(int i){value=i;}public int readInt(){return value;}public void recycle(){}}''',
'android/os/Bundle.java':'''package android.os;public class Bundle extends java.util.HashMap<String,Object>{
public void putBoolean(String k,boolean v){put(k,v);}public boolean getBoolean(String k){return Boolean.TRUE.equals(get(k));}
public void putParcelable(String k,Object v){put(k,v);}public <T>T getParcelable(String k,Class<T> c){return c.cast(get(k));}}''',
'android/os/Looper.java':'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'''package android.os;public class Handler{public static java.util.List<Runnable> pending=new java.util.ArrayList<>();public Handler(Looper l){}
public void post(Runnable r){pending.add(r);}public static void drain(){var copy=new java.util.ArrayList<>(pending);pending.clear();for(Runnable r:copy)r.run();}}''',
'android/net/Uri.java':'package android.net;public record Uri(String authority){public String getAuthority(){return authority;}}',
'android/content/ClipData.java':'package android.content;public class ClipData{public java.util.List<android.net.Uri> uris=new java.util.ArrayList<>();}',
'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager{public static class NameNotFoundException extends Exception{}
public static class Info{public int uid=12345;}public Info getApplicationInfo(String p,int f)throws NameNotFoundException{return new Info();}}''',
'android/content/Context.java':'package android.content;public class Context{public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}}',
'android/util/Log.java':'package android.util;public class Log{public static int w(String t,String m,Throwable e){return 0;}}',
'android/view/HapticFeedbackConstants.java':'package android.view;public class HapticFeedbackConstants{public static final int CLOCK_TICK=4;}',
'android/view/View.java':'''package android.view;public class View{public java.util.List<Runnable> pending=new java.util.ArrayList<>();public int haptics;public long delay;
public void postDelayed(Runnable r,long d){pending.add(r);delay=d;}public void removeCallbacks(Runnable r){pending.remove(r);}public boolean performHapticFeedback(int kind){haptics++;return true;}
public void drain(){var copy=new java.util.ArrayList<>(pending);pending.clear();for(Runnable r:copy)r.run();}}''',
'io/github/zerohomers0/onesteplite/ShareDeliveryRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.os.*;import android.content.*;import android.view.*;
class ContentInboxProvider{static final String MODULE="module",AUTHORITY="owned";}
class Tags{static final String PROBE="test";}
class SharePayload{java.util.List<android.net.Uri> uris;SharePayload(ClipData clip){uris=clip.uris;}}
public class ShareDeliveryRegression{static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
public static void main(String[] args){Context c=new Context();int[] outcomes={0};ClipData[] retained={null};
ShareDelivery receipt=new ShareDelivery(c,(ok,clip)->{outcomes[0]++;retained[0]=clip;});
check(ShareDelivery.active(receipt.token),"fresh receipt active");Binder.uid=54321;
check(!ShareDelivery.active(receipt.token),"foreign caller cannot inspect receipt");ShareDelivery.report(receipt.token,true,null);
Handler.drain();check(outcomes[0]==0,"foreign caller cannot forge success");Binder.uid=12345;
ClipData clip=new ClipData();clip.uris.add(new android.net.Uri("owned"));ShareDelivery.report(receipt.token,false,clip);
check(!ShareDelivery.active(receipt.token),"receipt consumed before callback");Handler.drain();
check(outcomes[0]==1&&retained[0]==clip,"failure returns owned retry data");ShareDelivery.report(receipt.token,true,null);Handler.drain();
check(outcomes[0]==1,"duplicate completion ignored");receipt=new ShareDelivery(c,(ok,data)->outcomes[0]++);receipt.close();
ShareDelivery.report(receipt.token,true,null);Handler.drain();check(outcomes[0]==1&&!ShareDelivery.active(receipt.token),"cancel blocks late delivery");
receipt=new ShareDelivery(c,(ok,data)->retained[0]=data);clip.uris.add(new android.net.Uri("external"));ShareDelivery.report(receipt.token,false,clip);Handler.drain();
check(retained[0]==null,"unowned transient URI never retained");
View owner=new View();boolean[] live={true};String[] identity={"task:1:epoch1"};int[] switches={0};
WindowDragHover hover=new WindowDragHover(owner,()->live[0]);hover.bind(pkg->pkg==null?null:identity[0],key->switches[0]++);
hover.move("notes");check(owner.pending.size()==1&&owner.delay==650,"one bounded dwell starts");hover.move("notes");
check(owner.pending.size()==1,"locations do not restart dwell");owner.drain();check(switches[0]==1&&owner.haptics==1,"stable dwell switches once");
hover.move("notes");owner.drain();check(switches[0]==1,"same task cannot repeatedly switch");
hover.close();hover.move("notes");hover.move(null);owner.drain();check(switches[0]==1,"exit cancels dwell");
hover.move("notes");identity[0]="task:1:epoch2";hover.move("notes");check(owner.pending.size()==1,"new task binding replaces callback");
live[0]=false;owner.drain();check(switches[0]==1,"inactive workspace blocks pending switch");
live[0]=true;hover.close();hover.move("notes");hover.close();owner.drain();check(switches[0]==1&&owner.pending.isEmpty(),"close releases callback");
System.out.println("Production share delivery and dwell checks="+checks+" passed");}}
'''
}
paths=[]
for name,body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths += [str(src/'ShareDelivery.java'),str(src/'WindowDragHover.java')]
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.ShareDeliveryRegression'],check=True)
