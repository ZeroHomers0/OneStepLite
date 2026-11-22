"""Execute modal/screenshot layer ownership and restoration with WM doubles."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root)
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/content/Context.java':r'''package android.content;public class Context {
 public static class PM {public String[] getPackagesForUid(int uid){return uid==1000?new String[]{"com.android.systemui"}:new String[]{"foreign"};}}
 public PM getPackageManager(){return new PM();}
}''',
'android/view/WindowManager.java':r'''package android.view;public class WindowManager {public static class LayoutParams {
 public static final int FLAG_SHOW_WHEN_LOCKED=524288;public int type=2017,flags;public String packageName="com.android.systemui",title="OneStepLiteQuickAppPicker";
 public CharSequence getTitle(){return title;}
}}''',
'de/robv/android/xposed/XposedHelpers.java':r'''package de.robv.android.xposed;public class XposedHelpers {
 public static Object getObjectField(Object o,String f){try{return o.getClass().getField(f).get(o);}catch(Exception e){throw new IllegalStateException(e);}}
 public static int getIntField(Object o,String f){return (Integer)getObjectField(o,f);}
 public static void setObjectField(Object o,String f,Object v){try{o.getClass().getField(f).set(o,v);}catch(Exception e){throw new IllegalStateException(e);}}
 public static void setIntField(Object o,String f,int v){setObjectField(o,f,v);}
 public static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new IllegalStateException(e);}}
}''',
'io/github/zerohomers0/onesteplite/ModalLayersRegression.java':r'''package io.github.zerohomers0.onesteplite;
import java.util.*;import java.util.function.Consumer;import android.view.WindowManager.LayoutParams;
public class ModalLayersRegression {
 public static class Service {public android.content.Context mContext=new android.content.Context();}
 public static class Display {public Service mWmService=new Service();public List<Window> windows=new ArrayList<>();public void forAllWindows(Consumer<Object> c,boolean top){for(Window w:windows)c.accept(w);}}
 public static class Surface {public boolean valid=true;public boolean isValid(){return valid;}}
 public static class Session {public int mUid=1000;}
 public static class Window {
  public Display display;public LayoutParams mAttrs=new LayoutParams();public int mOwnerUid=1000,mLastLayer=77,assigns,restores;public Session mSession=new Session();public Object mLastRelativeToLayer;public Surface surface=new Surface();public boolean visible=true;
  public Object getDisplayContent(){return display;}public boolean isVisible(){return visible;}public Object getSurfaceControl(){return surface;}
  public void assignRelativeLayer(Object tx,Object anchor,int layer,boolean force){assigns++;mLastRelativeToLayer=anchor;mLastLayer=layer;}
  public void setRelativeLayer(Object tx,Object anchor,int layer){restores++;mLastRelativeToLayer=anchor;mLastLayer=layer;}
  public void setLayer(Object tx,int layer){restores++;mLastRelativeToLayer=null;mLastLayer=layer;}
 }
 static Display dc;static Window modal,shot,other;static ModalWindowLayers layers;static Object tx=new Object();static int checks;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static void fresh(){layers=new ModalWindowLayers();dc=new Display();modal=new Window();modal.display=dc;shot=new Window();shot.display=dc;shot.mAttrs.type=2036;shot.mAttrs.packageName="com.miui.screenshot";shot.mAttrs.title="ScreenshotAnimation";shot.mLastLayer=900;other=new Window();other.display=dc;other.mAttrs.title="PermissionDialog";other.mAttrs.type=2008;dc.windows.addAll(List.of(shot,modal,other));}
 static void apply(){layers.reconcile(dc,tx);}
 public static void main(String[]args){
  fresh();apply();check(modal.mLastRelativeToLayer==shot.surface&&modal.mLastLayer==1,"own picker must sit above screenshot anchor");
  check(shot.assigns==0&&shot.restores==0&&shot.mLastLayer==900&&other.assigns==0,"native screenshot/permission windows must not be mutated");
  apply();shot.visible=false;apply();check(modal.mLastLayer==77&&modal.mLastRelativeToLayer==null&&modal.restores==1,"preview expiry restores original native layer after repeated reconciliation");
  fresh();Object normal=new Object();modal.mLastRelativeToLayer=normal;modal.mLastLayer=23;apply();modal.visible=false;apply();check(modal.mLastRelativeToLayer==normal&&modal.mLastLayer==23,"picker dismissal restores prior relative layer");
  fresh();apply();modal.mLastRelativeToLayer=null;modal.mLastLayer=88;apply();shot.visible=false;apply();check(modal.mLastLayer==88,"native reassignment must refresh the restore point");
  fresh();apply();Surface old=shot.surface;shot.surface=new Surface();apply();check(modal.mLastRelativeToLayer==shot.surface&&modal.mLastRelativeToLayer!=old,"replacement screenshot gets its current surface anchor");shot.visible=false;apply();check(modal.mLastLayer==77,"anchor replacement cannot overwrite original modal order");
  for(int gate=0;gate<9;gate++){
   fresh();if(gate==0)modal.mAttrs.packageName="foreign";if(gate==1)modal.mAttrs.title="foreign";if(gate==2)modal.mAttrs.type=2038;
   if(gate==3)modal.mOwnerUid=2000;if(gate==4)modal.mSession.mUid=2000;if(gate==5)modal.mAttrs.flags=524288;
   if(gate==6)modal.visible=false;if(gate==7)modal.display=new Display();if(gate==8)modal.surface.valid=false;
   apply();check(modal.assigns==0,"untrusted/inactive modal acquired native layer at gate "+gate);
  }
  for(int gate=0;gate<5;gate++){
   fresh();if(gate==0)shot.mAttrs.packageName="foreign";if(gate==1)shot.mAttrs.title="foreign";if(gate==2)shot.mAttrs.type=2009;if(gate==3)shot.visible=false;if(gate==4)shot.surface.valid=false;
   apply();check(modal.assigns==0,"unrelated system window became screenshot anchor at gate "+gate);
  }
  fresh();apply();modal.surface.valid=false;shot.visible=false;apply();check(modal.restores==0,"destroyed surfaces must not be restored");
  fresh();apply();modal.surface=new Surface();modal.mLastLayer=99;modal.mLastRelativeToLayer=null;apply();shot.visible=false;apply();check(modal.mLastLayer==99,"replacement modal surface must use its own native restore point");
  fresh();apply();Display second=new Display();layers.reconcile(second,tx);check(modal.mLastRelativeToLayer==shot.surface,"another display traversal must not retire this display's modal lease");shot.visible=false;apply();check(modal.mLastLayer==77,"original display still restores its lease");
  System.out.println("Modal screenshot layering checks="+checks+" passed (WM doubles; visual/input acceptance pending)");
 }
}'''
}
paths=[]
for name,body in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
for name in ['ModalWindowLayers','WindowIdentity','WindowTypes','Packages']:paths.append(str(src/(name+'.java')))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.ModalLayersRegression'],check=True)
