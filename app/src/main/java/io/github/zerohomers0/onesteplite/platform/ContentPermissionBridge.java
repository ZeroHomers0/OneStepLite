package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.provider.MediaStore;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** WM capability bridge preserves the real drag source UID; no public grant endpoint exists. */
final class ContentPermissionBridge {
    private ContentPermissionBridge(){}

    static void install(ClassLoader loader,BooleanSupplier interactive) {
        try {
            Class<?> handler=XposedHelpers.findClass("com.android.server.wm.DragAndDropPermissionsHandler",loader);
            Class<?> stub=XposedHelpers.findClass("com.android.internal.view.IDragAndDropPermissions$Stub",loader);
            installGrantDiagnostics(handler,loader);
            XposedBridge.hookAllMethods(stub,"onTransact",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if(!Integer.valueOf(DropPermissionContract.DELEGATE).equals(call.args[0]) || !handler.isInstance(call.thisObject))return;
                    Parcel reply=(Parcel)call.args[2];if(reply==null){call.setResult(true);return;}
                    try {
                        Parcel data=(Parcel)call.args[1];data.enforceInterface(DropPermissionContract.DESCRIPTOR);
                        Context context=(Context)XposedHelpers.callMethod(XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.app.ActivityThread",loader),"currentActivityThread"),"getSystemContext");
                        ClipData clip=data.readTypedObject(ClipData.CREATOR);IBinder owner=data.readStrongBinder();
                        IBinder lease=delegate(context,call.thisObject,clip,owner,interactive.getAsBoolean());
                        reply.writeNoException();reply.writeStrongBinder(lease);
                    } catch(Throwable failure) {
                        android.util.Log.w(Tags.PROBE,"scoped drop delegation rejected",failure);
                        reply.writeException(new SecurityException("Cannot delegate this drop"));
                    }
                    call.setResult(true);
                }
            });
        } catch(Throwable unavailable) {android.util.Log.w(Tags.PROBE,"drop bridge unavailable on this ROM",unavailable);}
    }

    private static void installGrantDiagnostics(Class<?> handler,ClassLoader loader) {
        try {
            XposedBridge.hookAllConstructors(handler,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if(!call.hasThrowable() && diagnosticImage(call.thisObject)!=null){
                        android.util.Log.i("OneStepLiteShare",BuildIdentity.RUNTIME_MARKER);
                        logGrant(ShareDiagnostics.Code.RECEIVER_HANDLER_CREATED);
                    }
                }
            });
            if(XposedBridge.hookAllMethods(handler,"doTake",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if(diagnosticImage(call.thisObject)!=null)
                        logGrant(ShareDiagnostics.Code.RECEIVER_TAKE_STARTED);
                }
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    Uri uri=diagnosticImage(call.thisObject);
                    if(uri==null)return;
                    if(call.hasThrowable())logGrant(ShareDiagnostics.Code.RECEIVER_TAKE_FAILED);
                    logReceiverGrants(call.thisObject,loader,uri);
                }
            }).isEmpty())logGrant(ShareDiagnostics.Code.RECEIVER_HOOK_UNAVAILABLE);
        }catch(RuntimeException unavailable){
            logGrant(ShareDiagnostics.Code.RECEIVER_HOOK_UNAVAILABLE);
        }
    }

    private static Uri diagnosticImage(Object handler) {
        try {
            if(!"com.tencent.mm".equals(XposedHelpers.getObjectField(handler,"mTargetPackage"))
                    || XposedHelpers.getIntField(handler,"mSourceUserId")!=XposedHelpers.getIntField(handler,"mTargetUserId")
                    || (XposedHelpers.getIntField(handler,"mMode")&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0)
                return null;
            Object payload=XposedHelpers.getObjectField(handler,"mUris");
            if(!(payload instanceof java.util.List<?> uris) || uris.size()!=1
                    || !(uris.get(0) instanceof Uri uri) || !"media".equals(uri.getAuthority()))return null;
            java.util.List<String> path=uri.getPathSegments();
            if(path.size()!=4 || !("external_primary".equals(path.get(0)) || "external".equals(path.get(0)))
                    || !"images".equals(path.get(1)) || !"media".equals(path.get(2))
                    || ContentUris.parseId(uri)<0)return null;
            return uri;
        }catch(RuntimeException unavailable){
            return null;
        }
    }

    private static void logReceiverGrants(Object handler,ClassLoader loader,Uri image) {
        long identity=Binder.clearCallingIdentity();
        try {
            Class<?> activityThread=XposedHelpers.findClass("android.app.ActivityThread",loader);
            Object thread=XposedHelpers.callStaticMethod(activityThread,"currentActivityThread");
            Context context=(Context)XposedHelpers.callMethod(thread,"getSystemContext");
            int user=XposedHelpers.getIntField(handler,"mTargetUserId");
            String target=(String)XposedHelpers.getObjectField(handler,"mTargetPackage");
            Context userContext=(Context)XposedHelpers.callMethod(context,"createContextAsUser",
                    android.os.UserHandle.getUserHandleForUid(user*100000),0);
            int uid=userContext.getPackageManager().getPackageUid(target,0);
            Uri file=MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY,ContentUris.parseId(image));
            boolean imageGranted=explicitReadGrant(userContext,image,uid);
            boolean fileGranted=explicitReadGrant(userContext,file,uid);
            logGrant(imageGranted?ShareDiagnostics.Code.RECEIVER_IMAGE_GRANTED:ShareDiagnostics.Code.RECEIVER_IMAGE_NOT_GRANTED);
            logGrant(fileGranted?ShareDiagnostics.Code.RECEIVER_FILE_GRANTED:ShareDiagnostics.Code.RECEIVER_FILE_NOT_GRANTED);
        }catch(Exception unavailable){
            logGrant(ShareDiagnostics.Code.RECEIVER_CHECK_UNAVAILABLE);
        }finally{
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static boolean explicitReadGrant(Context context,Uri uri,int uid) {
        return context.checkUriPermission(uri,-1,uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ==android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private static void logGrant(ShareDiagnostics.Code result) {
        ShareDiagnostics.event(ShareDiagnostics.Stage.GRANT,result);
    }

    private static IBinder delegate(Context context,Object source,ClipData clip,IBinder owner,boolean active) throws Exception {
        int caller=Binder.getCallingUid();
        int host=context.getPackageManager().getApplicationInfo(Packages.SYSTEM_UI,0).uid;
        int recipient=context.getPackageManager().getApplicationInfo(ContentInboxProvider.MODULE,0).uid;
        int sourceUser=XposedHelpers.getIntField(source,"mSourceUserId");
        boolean read=(XposedHelpers.getIntField(source,"mMode")&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0;
        boolean unlocked=!context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();
        if(!DropDelegationPolicy.allowed(caller,host,sourceUser,recipient,read,active&&unlocked,
                Packages.SYSTEM_UI.equals(XposedHelpers.getObjectField(source,"mTargetPackage"))))
            throw new SecurityException("Not an interactive same-user SystemUI drop");
        if(clip==null || owner==null || !ContentSharePolicy.validSize(clip.getItemCount(),0))
            throw new SecurityException("Missing drop capability");
        Set<String> allowed=new HashSet<>();
        for(Object uri:(java.util.List<?>)XposedHelpers.getObjectField(source,"mUris"))allowed.add(key((Uri)uri,sourceUser));
        for(int i=0;i<clip.getItemCount();i++) {
            ClipData.Item item=clip.getItemAt(i);
            if(item.getIntent()!=null || item.getUri()==null || !allowed.contains(key(item.getUri(),sourceUser)))
                throw new SecurityException("URI is not part of this drop");
        }
        Object delegated=XposedHelpers.newInstance(source.getClass(),XposedHelpers.getObjectField(source,"mGlobalLock"),
                clip,XposedHelpers.getIntField(source,"mSourceUid"),ContentInboxProvider.MODULE,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,sourceUser,sourceUser);
        try {
            XposedHelpers.callMethod(delegated,"takeTransient");
            return new Lease(delegated,owner,caller,recipient);
        } catch(Throwable failure) {
            XposedHelpers.callMethod(delegated,"release");throw new SecurityException("Grant failed",failure);
        }
    }

    private static String key(Uri uri,int user) {
        if(uri==null || !"content".equals(uri.getScheme()) || uri.getEncodedAuthority()==null)
            throw new SecurityException("Not a content URI");
        String authority=uri.getEncodedAuthority();int at=authority.indexOf('@');
        if(at>=0){
            int embedded=Integer.parseInt(authority.substring(0,at));
            if(embedded!=user)throw new SecurityException("Cross-user URI delegation is disabled");
            authority=authority.substring(at+1);
        }
        return user+"|"+uri.buildUpon().encodedAuthority(authority).build();
    }

    private static final class Lease extends Binder implements IBinder.DeathRecipient {
        private final Object grant;
        private final IBinder owner;
        private final int host,module;
        private final Handler handler=new Handler(Looper.getMainLooper());
        private final Runnable expire=this::release;
        private boolean closed;
        Lease(Object grant,IBinder owner,int host,int module) throws Exception {
            this.grant=grant;this.owner=owner;this.host=host;this.module=module;
            owner.linkToDeath(this,0);handler.postDelayed(expire,60_000L);
        }
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws android.os.RemoteException {
            if(code!=IBinder.FIRST_CALL_TRANSACTION)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(DropPermissionContract.DESCRIPTOR);
            if(Binder.getCallingUid()!=host && Binder.getCallingUid()!=module)throw new SecurityException("Wrong grant owner");
            release();if(reply!=null)reply.writeNoException();return true;
        }
        @Override public void binderDied(){release();}
        private synchronized void release(){
            if(closed)return;closed=true;handler.removeCallbacks(expire);owner.unlinkToDeath(this,0);
            try{XposedHelpers.callMethod(grant,"release");}
            catch(Throwable failure){android.util.Log.w(Tags.PROBE,"delegated drop cleanup failed",failure);}
        }
    }
}
