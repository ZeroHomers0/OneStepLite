package io.github.zerohomers0.onesteplite;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

/** Small synchronous control messages; image bytes are bounded before crossing Binder. */
final class PortalWire {
    static final String PACKAGE="com.miui.contentextension";
    static final String DESCRIPTOR="io.github.zerohomers0.onesteplite.PortalSource";
    static final int STATUS=1,OFFER=2;
    private PortalWire(){}
    static Bundle call(IBinder target,int code,Bundle input) {
        if(target==null)return null;
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);data.writeBundle(input);
            if(!target.transact(code,data,reply,0))return null;
            reply.readException();return reply.readBundle(PortalWire.class.getClassLoader());
        } catch(Exception unavailable){return null;}
        finally{data.recycle();reply.recycle();}
    }
}
