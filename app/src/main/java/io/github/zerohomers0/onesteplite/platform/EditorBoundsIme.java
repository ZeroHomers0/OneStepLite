package io.github.zerohomers0.onesteplite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;
import java.util.HashSet;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Optional system-IME adapter requests only editor geometry during an authenticated drag. */
final class EditorBoundsIme {
    private static final String OWNER="editor_bounds_publisher";
    private static final Map<InputConnection,int[]> MODES=new WeakHashMap<>();
    private static final HashSet<Class<?>> HOOKED=new HashSet<>();
    private static final ThreadLocal<Publisher> REQUEST=new ThreadLocal<>();
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private EditorBoundsIme(){}
    static void install(ClassLoader loader){
        Class<?> remote=XposedHelpers.findClassIfExists("com.android.internal.inputmethod.RemoteInputConnection",loader);
        if(remote!=null)trackType(remote);
        XposedBridge.hookAllMethods(InputMethodService.class,"onCreate",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam call){attach((InputMethodService)call.thisObject);}
        });
        XposedBridge.hookAllMethods(InputMethodService.class,"onStartInput",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam call){track(((InputMethodService)call.thisObject).getCurrentInputConnection());}
        });
        XposedBridge.hookAllMethods(InputMethodService.class,"onDestroy",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam call){Publisher owner=owner((InputMethodService)call.thisObject);if(owner!=null)owner.close();}
        });
        XposedBridge.hookAllMethods(InputMethodService.class,"onFinishInput",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam call){Publisher owner=owner((InputMethodService)call.thisObject);if(owner!=null)owner.stop();}
        });
        Class<?> session=XposedHelpers.findClass("android.inputmethodservice.InputMethodService$InputMethodSessionImpl",loader);
        XposedBridge.hookAllMethods(session,"updateCursorAnchorInfo",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam call){
                Object service=XposedHelpers.getObjectField(call.thisObject,"this$0");
                if(service instanceof InputMethodService && call.args.length==1 && call.args[0] instanceof CursorAnchorInfo){
                    Publisher owner=owner((InputMethodService)service);if(owner!=null)owner.offer((CursorAnchorInfo)call.args[0]);}
            }
        });
    }
    private static Publisher owner(InputMethodService service){return (Publisher)XposedHelpers.getAdditionalInstanceField(service,OWNER);}
    static void attach(InputMethodService service){
        if(owner(service)!=null)return;
        android.content.ComponentName current=android.content.ComponentName.unflattenFromString(android.provider.Settings.Secure.getString(
                service.getContentResolver(),android.provider.Settings.Secure.DEFAULT_INPUT_METHOD));
        if(current==null || !service.getPackageName().equals(current.getPackageName()))return;
        Publisher publisher=new Publisher(service);
        service.registerReceiver(publisher.receiver,new IntentFilter(EditorBoundsRelay.ACTION),EditorBoundsRelay.PERMISSION,null,Context.RECEIVER_EXPORTED);
        XposedHelpers.setAdditionalInstanceField(service,OWNER,publisher);
    }
    private static void track(InputConnection connection){
        if(connection==null)return;
        for(Class<?> type=connection.getClass();type!=null && InputConnection.class.isAssignableFrom(type);type=type.getSuperclass())trackType(type);
    }
    private static void trackType(Class<?> type){
        synchronized(HOOKED){if(!HOOKED.add(type))return;}
        XposedBridge.hookAllMethods(type,"requestCursorUpdates",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam call){
                if(!Boolean.TRUE.equals(call.getResult()) || call.args.length<1 || !(call.args[0] instanceof Integer))return;
                int mode=(Integer)call.args[0],filter=call.args.length>1 && call.args[1] instanceof Integer?(Integer)call.args[1]:mode&~3;
                synchronized(MODES){int[] old=MODES.get(call.thisObject);int sequence=old==null?1:old[2]+1;
                    MODES.put((InputConnection)call.thisObject,new int[]{mode&3,filter,sequence});
                    Publisher caller=REQUEST.get();if(caller!=null && caller.connection==call.thisObject)caller.requestSequence=sequence;}
            }
        });
    }
    private static final class Publisher {
        final InputMethodService service;
        final Handler main=new Handler(Looper.getMainLooper());
        String ticket;
        InputConnection connection;
        int[] previous,requested;
        int requestSequence;
        Bundle pending;
        boolean sending;
        android.os.IBinder life;
        android.os.IBinder.DeathRecipient death;
        final Runnable expiry=this::stop;
        final BroadcastReceiver receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                String token=intent.getStringExtra("ticket");
                if(token==null || token.length()!=36)return;
                if(intent.getBooleanExtra("active",false))begin(token,intent.getExtras().getBinder("owner"));else if(token.equals(ticket))stop();
            }
        };
        Publisher(InputMethodService service){this.service=service;}
        void begin(String token,android.os.IBinder client){
            stop();if(client==null)return;ticket=token;life=client;death=()->main.post(()->{if(token.equals(ticket))stop();});
            try{client.linkToDeath(death,0);}catch(android.os.RemoteException gone){stop();return;}
            connection=service.getCurrentInputConnection();if(connection==null){stop();return;}
            track(connection);synchronized(MODES){int[] value=MODES.get(connection);previous=value==null?new int[]{0,0,0}:value.clone();}
            int filter=(previous[0]&2)!=0 && previous[1]==0?0:previous[1]|InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS;
            requested=new int[]{InputConnection.CURSOR_UPDATE_IMMEDIATE|InputConnection.CURSOR_UPDATE_MONITOR,filter};
            main.postDelayed(expiry,60_000L);
            requestSequence=0;REQUEST.set(this);
            try{if(!connection.requestCursorUpdates(requested[0],filter))stop();}
            catch(RuntimeException unsupported){stop();}
            finally{REQUEST.remove();}
        }
        void offer(CursorAnchorInfo info){
            if(ticket==null || connection!=service.getCurrentInputConnection())return;
            Bundle packet=packet();if(packet==null)return;
            android.view.inputmethod.EditorBoundsInfo editor=info.getEditorBoundsInfo();
            RectF rect=editor==null?null:editor.getEditorBounds();
            if(rect!=null){rect=new RectF(rect);info.getMatrix().mapRect(rect);if(EditorBoundsRelay.finite(rect))packet.putParcelable("bounds",rect);}
            send(packet);
        }
        Bundle packet(){
            EditorInfo editor=service.getCurrentInputEditorInfo();InputBinding binding=service.getCurrentInputBinding();
            if(ticket==null || editor==null || binding==null)return null;
            Bundle result=new Bundle();result.putString("ticket",ticket);result.putInt("uid",binding.getUid());result.putInt("field",editor.fieldId);return result;
        }
        void send(Bundle value){
            synchronized(this){pending=value;if(sending)return;sending=true;}
            WORKER.execute(()->{
                while(true){Bundle next;synchronized(this){next=pending;pending=null;if(next==null){sending=false;return;}}
                    try{service.getContentResolver().call(ContentInboxProvider.ROOT,"editor_bounds_offer",null,next);}
                    catch(RuntimeException unavailable){/* UI remains unhighlighted when the module cannot verify geometry. */}
                }
            });
        }
        void stop(){
            main.removeCallbacks(expiry);Bundle clear=packet();if(clear!=null)send(clear);ticket=null;
            if(life!=null && death!=null)life.unlinkToDeath(death,0);life=null;death=null;
            InputConnection old=connection;connection=null;int[] actual;synchronized(MODES){actual=MODES.get(old);}
            if(old!=null && old==service.getCurrentInputConnection() && previous!=null && requested!=null && actual!=null
                    && requestSequence>0 && actual[2]==requestSequence && actual[0]==requested[0] && actual[1]==requested[1])try{old.requestCursorUpdates(previous[0]&InputConnection.CURSOR_UPDATE_MONITOR,previous[1]);}
                    catch(RuntimeException ended){/* The previous editor may already be detached. */}
            previous=null;requested=null;
        }
        void close(){stop();service.unregisterReceiver(receiver);}
    }
}
