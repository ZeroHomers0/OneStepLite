package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.io.Closeable;

/** Only an existing drop Binder can issue a bounded module-read capability. */
final class DropPermissionContract implements Closeable {
    static final int DELEGATE=IBinder.FIRST_CALL_TRANSACTION+0x535400;
    static final String DESCRIPTOR="io.github.zerohomers0.onesteplite.DropRead";
    final IBinder token;
    private final IBinder owner;
    private boolean closed;

    private DropPermissionContract(IBinder token,IBinder owner){this.token=token;this.owner=owner;}

    static DropPermissionContract request(IBinder source,ClipData uris) throws Exception {
        IBinder owner=new Binder();Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);data.writeTypedObject(uris,0);data.writeStrongBinder(owner);
            if(!source.transact(DELEGATE,data,reply,0))throw new SecurityException("Drop bridge unavailable");
            reply.readException();IBinder token=reply.readStrongBinder();
            if(token==null)throw new SecurityException("Drop bridge rejected");
            return new DropPermissionContract(token,owner);
        } finally {data.recycle();reply.recycle();}
    }

    static void release(IBinder token) {
        if(token==null)return;Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try {data.writeInterfaceToken(DESCRIPTOR);if(token.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0))reply.readException();}
        catch(Exception failure){android.util.Log.w(Tags.PROBE,"delegated drop release unavailable",failure);}
        finally {data.recycle();reply.recycle();}
    }

    @Override public void close(){if(!closed){closed=true;release(token);}}
}
