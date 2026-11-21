package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.view.DragEvent;
import java.io.Closeable;
import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Adapted from NeXtep's drop capability handoff, scoped to OneStepLite's exact native task registry. */
final class DropPermissions {
    private static final String DESCRIPTOR = "io.github.zerohomers0.onesteplite.ContentDropPermissions";
    private static final int DELEGATE = IBinder.FIRST_CALL_TRANSACTION + 0x4D5300;
    static final int MAX_ITEMS = 32;
    private static final long LEASE_MS = 15 * 60_000L;

    static IBinder source(DragEvent event) {
        try { return ((IInterface) XposedHelpers.callMethod(event, "getDragAndDropPermissions")).asBinder(); }
        catch (Throwable unavailable) { return null; }
    }

    static Closeable delegate(IBinder source, IBinder task, int id, int user, String pkg, ClipData clip) throws Exception {
        if (source == null) throw new IllegalStateException("The source did not grant file access");
        IBinder owner = new Binder();
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        final IBinder token;
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(task); data.writeInt(id); data.writeInt(user); data.writeString(pkg);
            data.writeTypedObject(clip, 0); data.writeStrongBinder(owner);
            if (!source.transact(DELEGATE, data, reply, 0)) throw new IllegalStateException("Drop bridge unavailable");
            reply.readException(); token = reply.readStrongBinder();
            if (token == null) throw new IllegalStateException("Drop was not authorized");
        } finally { data.recycle(); reply.recycle(); }
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            Parcel request = Parcel.obtain(), result = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR); request.writeStrongBinder(owner);
                if (token.transact(IBinder.FIRST_CALL_TRANSACTION, request, result, 0)) result.readException();
            } catch (Exception ignored) { /* Native death/timeout also closes the lease. */ }
            finally { request.recycle(); result.recycle(); }
        };
    }

    static void install(ClassLoader loader, Supplier<Object> activeDisplay) {
        try {
            Class<?> handler = XposedHelpers.findClass("com.android.server.wm.DragAndDropPermissionsHandler", loader);
            Class<?> stub = XposedHelpers.findClass("com.android.internal.view.IDragAndDropPermissions$Stub", loader);
            XposedHelpers.findAndHookMethod(stub, "onTransact", int.class, Parcel.class, Parcel.class, int.class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!Integer.valueOf(DELEGATE).equals(p.args[0]) || !handler.isInstance(p.thisObject)) return;
                    Parcel data = (Parcel) p.args[1], reply = (Parcel) p.args[2];
                    if (reply == null) { p.setResult(true); return; }
                    Object grant = null;
                    try {
                        data.enforceInterface(DESCRIPTOR);
                        Object display = activeDisplay.get();
                        if (display == null) throw new SecurityException("OneStepLite is not active");
                        Object wms = XposedHelpers.getObjectField(display, "mWmService");
                        Context context = (Context) XposedHelpers.getObjectField(wms, "mContext");
                        int caller = Binder.getCallingUid();
                        if (caller != context.getPackageManager().getApplicationInfo(Packages.SYSTEM_UI, 0).uid
                                || !Packages.SYSTEM_UI.equals(XposedHelpers.getObjectField(p.thisObject, "mTargetPackage"))
                                || !EntryGuard.canOpen(context)) throw new SecurityException("Not an interactive SystemUI drop");
                        IBinder taskToken = data.readStrongBinder(); int id = data.readInt(), user = data.readInt();
                        String pkg = data.readString(); ClipData clip = data.readTypedObject(ClipData.CREATOR);
                        IBinder owner = data.readStrongBinder(); data.enforceNoDataAvail();
                        if (owner == null || taskToken == null || clip == null || clip.getItemCount() < 1
                                || clip.getItemCount() > MAX_ITEMS) throw new SecurityException("Invalid drop");
                        int sourceUser = XposedHelpers.getIntField(p.thisObject, "mSourceUserId");
                        // Profile sharing requires a separate managed-profile policy. Never infer permission from UID 1000.
                        if (sourceUser != user) throw new SecurityException("Cross-profile drop is not supported");
                        int mode = XposedHelpers.getIntField(p.thisObject, "mMode");
                        if ((mode & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new SecurityException("No read grant");
                        Set<String> allowed = new HashSet<>();
                        for (Object uri : (List<?>) XposedHelpers.getObjectField(p.thisObject, "mUris"))
                            allowed.add(canonical((Uri) uri, sourceUser));
                        for (int i=0; i<clip.getItemCount(); i++) {
                            ClipData.Item item = clip.getItemAt(i);
                            if (item.getIntent() != null || item.getUri() == null
                                    || !allowed.contains(canonical(item.getUri(), sourceUser)))
                                throw new SecurityException("URI was not in this drop");
                        }
                        synchronized (XposedHelpers.getObjectField(wms, "mGlobalLock")) {
                            Class<?> container = XposedHelpers.findClass("com.android.server.wm.WindowContainer", loader);
                            Object task = XposedHelpers.callStaticMethod(container, "fromBinder", taskToken);
                            if (task == null || XposedHelpers.callMethod(task, "getDisplayContent") != display
                                    || DesktopLaunchBridge.dropLease(task) == null)
                                throw new SecurityException("Target left OneStepLite");
                            Object info = XposedHelpers.callMethod(task, "getTaskInfo");
                            ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
                            if (XposedHelpers.getIntField(info, "taskId") != id
                                    || XposedHelpers.getIntField(info, "userId") != user || base == null
                                    || !base.getPackageName().equals(pkg)) throw new SecurityException("Target identity changed");
                            Constructor<?> constructor = null;
                            for (Constructor<?> candidate : handler.getDeclaredConstructors()) {
                                Class<?>[] types = candidate.getParameterTypes();
                                if (types.length == 7 && types[1] == ClipData.class && types[2] == int.class
                                        && types[3] == String.class && types[4] == int.class
                                        && types[5] == int.class && types[6] == int.class) constructor = candidate;
                            }
                            if (constructor == null) throw new IllegalStateException("Native drag permissions unavailable");
                            constructor.setAccessible(true);
                            grant = constructor.newInstance(XposedHelpers.getObjectField(p.thisObject, "mGlobalLock"),
                                    clip, XposedHelpers.getIntField(p.thisObject, "mSourceUid"), pkg,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION, sourceUser, user);
                            XposedHelpers.callMethod(grant, "takeTransient");
                        }
                        Lease lease = new Lease(grant, owner, caller);
                        reply.writeNoException(); reply.writeStrongBinder(lease);
                    } catch (Throwable error) {
                        if (grant != null) try { XposedHelpers.callMethod(grant, "release"); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
                        reply.writeException(new SecurityException("Cannot forward this drop's read permission"));
                        XposedBridge.log("OneStepLiteProbe: content permission refused: " + error);
                    }
                    p.setResult(true);
                }
            });
        } catch (Throwable unavailable) { XposedBridge.log("OneStepLiteProbe: content drop bridge unavailable: " + unavailable); }
    }

    private static String canonical(Uri uri, int user) {
        if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null) throw new SecurityException("Not a content URI");
        String authority = uri.getAuthority(); int at = authority.indexOf('@');
        int owner = at < 0 ? user : Integer.parseInt(authority.substring(0, at));
        if (owner != user) throw new SecurityException("Cross-user URI is not supported");
        return owner + ":" + uri.buildUpon().authority(at < 0 ? authority : authority.substring(at+1)).build();
    }
    private static final class Lease extends Binder implements IBinder.DeathRecipient {
        final Object grant; final IBinder owner; final int uid;
        final AtomicBoolean closed = new AtomicBoolean();
        final Handler timer = new Handler(Looper.getMainLooper());
        final Runnable expire = this::close;
        Lease(Object grant, IBinder owner, int uid) throws Exception {
            this.grant=grant; this.owner=owner; this.uid=uid;
            owner.linkToDeath(this, 0); timer.postDelayed(expire, LEASE_MS);
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            if (Binder.getCallingUid() != uid || data.readStrongBinder() != owner) throw new SecurityException("Wrong owner");
            close(); if (reply != null) reply.writeNoException(); return true;
        }
        @Override public void binderDied() { close(); }
        void close() {
            if (!closed.compareAndSet(false, true)) return;
            timer.removeCallbacks(expire);
            try { owner.unlinkToDeath(this, 0); } catch (Throwable alreadyDead) { /* best effort: the owner may already be gone */ }
            try { XposedHelpers.callMethod(grant, "release"); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
        }
    }
    private DropPermissions() {}
}
