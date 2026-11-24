package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.UserManager;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Explicit clipboard clicks may paste only into the same verified live editor and window. */
final class EditorPasteCompat {
    private static final String DESCRIPTOR="io.github.zerohomers0.onesteplite.EditorPaste";
    private static final String OWNER="editor_paste_owner";
    private static IBinder host;
    private static final Binder REGISTRY=new Binder();
    private EditorPasteCompat(){}
    static boolean available(Context context) {
        try{return context.getContentResolver().call(ContentInboxProvider.ROOT,"paste_status",null,null)!=null;}
        catch(RuntimeException unavailable){return false;}
    }
    static boolean paste(Context context,String text) {
        try {
            Bundle state=context.getContentResolver().call(ContentInboxProvider.ROOT,"paste_status",null,null);
            if(state==null)return false;state.putString("text",text);
            Bundle reply=context.getContentResolver().call(ContentInboxProvider.ROOT,"paste_commit",null,state);
            return reply!=null && reply.getBoolean("accepted");
        }catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"clipboard editor paste unavailable",unavailable);return false;}
    }
    static Bundle relay(Context context,String method,Bundle input) {
        if(Binder.getCallingUid()/100000!=android.os.Process.myUid()/100000)throw new SecurityException("Different paste user");
        if("paste_register".equals(method)) {
            if(Binder.getCallingUid()!=1000)throw new SecurityException("Not system server");
            synchronized(EditorPasteCompat.class){host=input==null?null:input.getBinder("host");}
            Bundle reply=new Bundle();reply.putBinder("registry",REGISTRY);return reply;
        }
        IBinder target;synchronized(EditorPasteCompat.class){target=host;}
        if(target==null || !target.isBinderAlive())return null;
        int code="paste_status".equals(method)?1:"paste_commit".equals(method)?2:"conversation_list".equals(method)?3:0;
        if(code==0)throw new IllegalArgumentException("Unknown paste operation");
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);data.writeBundle(input);
            if(!target.transact(code,data,reply,0))return null;
            reply.readException();return reply.readBundle(EditorPasteCompat.class.getClassLoader());
        }catch(Exception unavailable){return null;}
        finally{data.recycle();reply.recycle();}
    }
    static void install(ClassLoader loader,Supplier<Object> display,BooleanSupplier active) {
        try {
            Class<?> type=XposedHelpers.findClass("com.android.server.inputmethod.InputMethodManagerService",loader);
            XposedBridge.hookAllConstructors(type,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if(XposedHelpers.getAdditionalInstanceField(call.thisObject,OWNER)!=null)return;
                    try{Server server=new Server(call.thisObject,loader,display,active);
                        XposedHelpers.setAdditionalInstanceField(call.thisObject,OWNER,server);server.watch();}
                    catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"editor paste service unavailable",unavailable);}
                }
            });
            XposedBridge.hookAllMethods(type,"startInputOrWindowGainedFocus",new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    Object server=XposedHelpers.getAdditionalInstanceField(call.thisObject,OWNER);
                    if(server instanceof Server)((Server)server).register();
                }
            });
        }catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"editor paste hook unavailable",unavailable);}
    }
    private static Object field(Object object,String...names) {
        for(String name:names)try{return XposedHelpers.getObjectField(object,name);}catch(Throwable ignored){}
        return null;
    }
    private static final class Server {
        private final Object service,lock;
        private final Context context;
        private final Supplier<Object> display;
        private final BooleanSupplier active;
        private final AtomicBoolean registering=new AtomicBoolean();
        private volatile IBinder registry;
        private final Binder endpoint=new Binder(){
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws android.os.RemoteException {
                if(code!=1 && code!=2 && code!=3)return super.onTransact(code,data,reply,flags);
                data.enforceInterface(DESCRIPTOR);
                try{if(Binder.getCallingUid()!=context.getPackageManager().getApplicationInfo(ContentInboxProvider.MODULE,0).uid)
                    throw new SecurityException("Not module paste relay");}
                catch(android.content.pm.PackageManager.NameNotFoundException missing){throw new SecurityException("Module missing",missing);}
                Bundle input=data.readBundle(EditorPasteCompat.class.getClassLoader());long identity=Binder.clearCallingIdentity();
                try{Bundle result=handle(code,input);reply.writeNoException();reply.writeBundle(result);return true;}
                finally{Binder.restoreCallingIdentity(identity);}
            }
        };
        Server(Object service,ClassLoader loader,Supplier<Object> display,BooleanSupplier active) {
            this.service=service;this.display=display;this.active=active;
            context=(Context)field(service,"mContext");lock=XposedHelpers.findClass("com.android.server.inputmethod.ImfLock",loader);
        }
        void watch() {
            context.registerReceiver(new android.content.BroadcastReceiver(){
                @Override public void onReceive(Context ignored,android.content.Intent intent){register();}
            },new android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED),Context.RECEIVER_NOT_EXPORTED);
            register();
        }
        void register() {
            if(registry!=null || !context.getSystemService(UserManager.class).isUserUnlocked() || !registering.compareAndSet(false,true))return;
            new Thread(()->{
                try{Bundle request=new Bundle();request.putBinder("host",endpoint);
                    Bundle reply=context.getContentResolver().call(ContentInboxProvider.ROOT,"paste_register",null,request);
                    IBinder token=reply==null?null:reply.getBinder("registry");
                    if(token!=null){registry=token;token.linkToDeath(()->registry=null,0);}
                }catch(Exception unavailable){registry=null;android.util.Log.w(Tags.PROBE,"editor paste registration unavailable",unavailable);}
                finally{registering.set(false);}
            },"EditorPasteRegistration").start();
        }
        private Bundle snapshot() {
            if(!active.getAsBoolean() || context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked())return null;
            Bundle result=new Bundle();
            synchronized(lock) {
                Object data=service;Object user=field(service,"mCurrentImeUserId");
                if(user instanceof Integer)try{data=XposedHelpers.callMethod(service,"getUserData",user);}catch(Throwable older){data=service;}
                EditorInfo editor=(EditorInfo)field(data,"mCurEditorInfo","mCurAttribute");
                Object connection=field(data,"mCurInputConnection","mCurInputContext"),client=field(data,"mCurClient");
                Object binding=field(data,"mImeBindingState");
                Object window=field(binding,"mFocusedWindow","mCurFocusedWindow");
                if(!(window instanceof IBinder))window=field(data,"mCurFocusedWindow");
                if(editor==null || editor.inputType==0 || Packages.SYSTEM_UI.equals(editor.packageName)
                        || !(connection instanceof IInterface) || !(window instanceof IBinder) || client==null)return null;
                int uid=XposedHelpers.getIntField(client,"mUid");if(uid/100000!=0)return null;
                result.putInt("uid",uid);result.putInt("field",editor.fieldId);result.putBinder("window",(IBinder)window);
                result.putBinder("editor",((IInterface)connection).asBinder());result.putLong("issued",android.os.SystemClock.uptimeMillis());
            }
            return focused(result)?result:null;
        }
        private boolean focused(Bundle snapshot) {
            Object target=display.get();if(target==null)return false;
            Object wm=field(target,"mWmService"),wmLock=field(wm,"mGlobalLock");if(wmLock==null)return false;
            synchronized(wmLock) {
                Object focus=field(target,"mCurrentFocus");if(focus==null)return false;
                Object client=field(focus,"mClient");
                return client instanceof IInterface && snapshot.getBinder("window").equals(((IInterface)client).asBinder())
                        && snapshot.getInt("uid")==XposedHelpers.getIntField(focus,"mOwnerUid")
                        && Boolean.TRUE.equals(XposedHelpers.callMethod(focus,"isVisible"));
            }
        }
        private Bundle handle(int code,Bundle input) {
            if(code==3)return ConversationShareCompat.query(context,input);
            try {
                Bundle current=snapshot();if(code==1)return current;
                Bundle reply=new Bundle();String text=input==null?null:input.getString("text");
                boolean same=current!=null && input!=null && EditorPastePolicy.sameTarget(input.getInt("uid"),current.getInt("uid"),
                        input.getInt("field"),current.getInt("field"),current.getBinder("window").equals(input.getBinder("window")),
                        current.getBinder("editor").equals(input.getBinder("editor")));
                long age=input==null?-1:android.os.SystemClock.uptimeMillis()-input.getLong("issued");
                if(!EditorPastePolicy.allowed(same,text==null?0:text.length(),age))return reply;
                context.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("剪贴板",text));
                Object manager=XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.hardware.input.InputManagerGlobal",null),"getInstance");
                long time=android.os.SystemClock.uptimeMillis();boolean accepted=false;
                for(int action:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP}) {
                    KeyEvent event=new KeyEvent(time,android.os.SystemClock.uptimeMillis(),action,KeyEvent.KEYCODE_PASTE,0,0,-1,0,
                            KeyEvent.FLAG_FROM_SYSTEM,android.view.InputDevice.SOURCE_KEYBOARD);
                    XposedHelpers.callMethod(event,"setDisplayId",0);
                    boolean sent=Boolean.TRUE.equals(XposedHelpers.callMethod(manager,"injectInputEvent",event,2,current.getInt("uid")));
                    if(action==KeyEvent.ACTION_DOWN)accepted=sent;
                }
                reply.putBoolean("accepted",accepted);return reply;
            }catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"editor paste rejected",unavailable);return null;}
        }
    }
}
