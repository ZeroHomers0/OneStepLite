package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** One authenticated acknowledgement distinguishes accepted DROP from successful preparation. */
final class ShareDelivery implements AutoCloseable {
    private static final String DESCRIPTOR="io.github.zerohomers0.onesteplite.ShareDelivery";
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Context context;
    private final BiConsumer<Boolean,ClipData> completed;
    final IBinder token=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws android.os.RemoteException {
            if(code!=IBinder.FIRST_CALL_TRANSACTION && code!=IBinder.FIRST_CALL_TRANSACTION+1)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(DESCRIPTOR);
            try{if(Binder.getCallingUid()!=context.getPackageManager().getApplicationInfo(ContentInboxProvider.MODULE,0).uid)
                throw new SecurityException("Not delivery owner");}
            catch(android.content.pm.PackageManager.NameNotFoundException missing){throw new SecurityException(missing);}
            if(code==IBinder.FIRST_CALL_TRANSACTION+1){reply.writeNoException();reply.writeInt(closed.get()?0:1);return true;}
            Bundle result=data.readBundle(ShareDelivery.class.getClassLoader());
            ClipData clip=result==null?null:result.getParcelable("clip",ClipData.class);
            if(clip!=null){SharePayload payload=new SharePayload(clip);
                if(payload.uris.stream().anyMatch(uri->!ContentInboxProvider.AUTHORITY.equals(uri.getAuthority())))clip=null;}
            ClipData retained=clip;boolean success=result!=null && result.getBoolean("success");
            if(closed.compareAndSet(false,true))new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(()->completed.accept(success,retained));
            reply.writeNoException();return true;
        }
    };
    ShareDelivery(Context context,BiConsumer<Boolean,ClipData> completed){this.context=context;this.completed=completed;}
    @Override public void close(){closed.set(true);}
    static boolean active(IBinder token) {
        if(token==null)return true;Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(DESCRIPTOR);
            if(!token.transact(IBinder.FIRST_CALL_TRANSACTION+1,data,reply,0))return false;
            reply.readException();return reply.readInt()==1;
        }catch(Exception unavailable){return false;}finally{data.recycle();reply.recycle();}
    }
    static void report(IBinder token,boolean success,ClipData clip) {
        if(token==null)return;Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{Bundle result=new Bundle();result.putBoolean("success",success);result.putParcelable("clip",clip);
            data.writeInterfaceToken(DESCRIPTOR);data.writeBundle(result);
            if(token.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0))reply.readException();
        }catch(Exception unavailable){android.util.Log.w(Tags.PROBE,"delivery receipt unavailable",unavailable);}
        finally{data.recycle();reply.recycle();}
    }
}
