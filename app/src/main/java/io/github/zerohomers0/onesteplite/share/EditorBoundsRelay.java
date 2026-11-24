package io.github.zerohomers0.onesteplite;

import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.RectF;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.UUID;

/** Same-user drag capability joins IME geometry to a verified input connection; never carries text. */
final class EditorBoundsRelay {
    static final String ACTION="io.github.zerohomers0.onesteplite.EDITOR_DRAG_STATE";
    static final String PERMISSION="io.github.zerohomers0.onesteplite.CONTENT_ACCESS";
    private static String ticket,imePackage;
    private static Bundle target,geometry;
    private static long deadline;
    private static IBinder owner;
    private static IBinder.DeathRecipient death;
    private EditorBoundsRelay(){}

    static Bundle call(Context context,String method,Bundle input){
        if(Binder.getCallingUid()/100000!=android.os.Process.myUid()/100000)throw new SecurityException("Different editor user");
        if("editor_bounds_offer".equals(method)){offer(context,input);return new Bundle();}
        if("editor_bounds_start".equals(method))return start(context,input);
        if("editor_bounds_end".equals(method)){
            end(context,input==null?null:input.getString("ticket"));return new Bundle();
        }
        if(!"editor_bounds_read".equals(method))throw new IllegalArgumentException("Unknown editor geometry operation");
        String token=input==null?null:input.getString("ticket");Bundle expected,cached;
        ComponentName currentIme=ComponentName.unflattenFromString(Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD));
        synchronized(EditorBoundsRelay.class){
            if(token==null || !token.equals(ticket) || SystemClock.uptimeMillis()>deadline)return new Bundle();
            if(currentIme==null || !currentIme.getPackageName().equals(imePackage))return new Bundle();
            expected=target;cached=geometry==null?null:new Bundle(geometry);
        }
        Bundle current=EditorPasteCompat.relay(context,"paste_status",null);
        if(!same(expected,current) || cached==null)return new Bundle();
        float[] transform=current.getFloatArray("geometry_transform");if(transform==null || transform.length!=3)return new Bundle();
        RectF rect=cached.getParcelable("bounds",RectF.class);if(rect==null)return new Bundle();
        rect=new RectF(rect.left*transform[0]+transform[1],rect.top*transform[0]+transform[2],
                rect.right*transform[0]+transform[1],rect.bottom*transform[0]+transform[2]);
        Bundle result=new Bundle();result.putParcelable("bounds",rect);return result;
    }
    private static Bundle start(Context context,Bundle input){
        ComponentName ime=ComponentName.unflattenFromString(Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD));
        Bundle snapshot=EditorPasteCompat.relay(context,"paste_status",null);
        ClipDescription description=input==null?null:input.getParcelable("types",ClipDescription.class);
        IBinder client=input==null?null:input.getBinder("owner");
        if(ime==null || snapshot==null || description==null || client==null || snapshot.getFloatArray("geometry_transform")==null
                || !accepts(snapshot,description))return new Bundle();
        String token=UUID.randomUUID().toString(),old,oldIme;boolean linked;
        synchronized(EditorBoundsRelay.class){old=ticket;oldIme=imePackage;ticket=token;imePackage=ime.getPackageName();
            if(owner!=null && death!=null)owner.unlinkToDeath(death,0);
            owner=client;Context app=context.getApplicationContext();death=()->end(app,token);
            target=new Bundle(snapshot);geometry=null;deadline=SystemClock.uptimeMillis()+60_000L;
            try{client.linkToDeath(death,0);linked=true;}catch(android.os.RemoteException gone){clear();linked=false;}}
        if(old!=null)signal(context,oldIme,old,false);
        if(!linked)return new Bundle();
        signal(context,ime.getPackageName(),token,true,client);Bundle result=new Bundle();result.putString("ticket",token);return result;
    }
    private static void offer(Context context,Bundle input){
        ComponentName ime=ComponentName.unflattenFromString(Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD));
        try{if(ime==null || context.getPackageManager().getApplicationInfo(ime.getPackageName(),0).uid!=Binder.getCallingUid())
            throw new SecurityException("Not current input method");}
        catch(android.content.pm.PackageManager.NameNotFoundException missing){throw new SecurityException(missing);}
        synchronized(EditorBoundsRelay.class){
            if(input==null || ticket==null || !ticket.equals(input.getString("ticket")) || SystemClock.uptimeMillis()>deadline)return;
            geometry=null;
            if(input.getInt("uid",-1)!=target.getInt("uid") || input.getInt("field",-1)!=target.getInt("field"))return;
            RectF bounds=input.getParcelable("bounds",RectF.class);
            if(bounds==null || !finite(bounds) || bounds.isEmpty())return;
            geometry=new Bundle();geometry.putParcelable("bounds",new RectF(bounds));
        }
    }
    static boolean accepts(Bundle snapshot,ClipDescription types){
        boolean text=types.hasMimeType("text/plain");
        for(int i=0;i<types.getMimeTypeCount();i++)if(!types.getMimeType(i).startsWith("text/"))text=false;
        if(text)return true;
        String[] accepted=snapshot.getStringArray("content_types");if(accepted==null)return false;
        for(int i=0;i<types.getMimeTypeCount();i++){
            boolean supported=false;for(String mime:accepted)if(ClipDescription.compareMimeTypes(types.getMimeType(i),mime))supported=true;
            if(!supported)return false;
        }
        return types.getMimeTypeCount()>0;
    }
    static boolean same(Bundle a,Bundle b){
        return a!=null && b!=null && a.getInt("uid")==b.getInt("uid") && a.getInt("field")==b.getInt("field")
                && a.getBinder("window")!=null && a.getBinder("window").equals(b.getBinder("window"))
                && a.getBinder("editor")!=null && a.getBinder("editor").equals(b.getBinder("editor"));
    }
    static boolean finite(RectF rect){return Float.isFinite(rect.left)&&Float.isFinite(rect.top)&&Float.isFinite(rect.right)&&Float.isFinite(rect.bottom);}
    private static void signal(Context context,String keyboard,String token,boolean active){
        signal(context,keyboard,token,active,null);
    }
    private static void signal(Context context,String keyboard,String token,boolean active,IBinder client){
        if(keyboard==null)return;long identity=Binder.clearCallingIdentity();
        try{Bundle data=new Bundle();data.putBinder("owner",client);
            context.sendBroadcast(new Intent(ACTION).setPackage(keyboard).putExtra("ticket",token).putExtra("active",active).putExtras(data));}
        finally{Binder.restoreCallingIdentity(identity);}
    }
    private static void end(Context context,String token){
        String keyboard;synchronized(EditorBoundsRelay.class){if(token==null || !token.equals(ticket))return;keyboard=imePackage;clear();}
        signal(context,keyboard,token,false);
    }
    private static void clear(){if(owner!=null && death!=null)owner.unlinkToDeath(death,0);
        owner=null;death=null;ticket=null;imePackage=null;target=null;geometry=null;deadline=0;}
}
