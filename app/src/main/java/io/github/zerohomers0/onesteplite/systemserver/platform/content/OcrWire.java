package io.github.zerohomers0.onesteplite;

import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.function.Consumer;
import java.util.function.Function;

/** AICR V2 wire adapter, independent of the Portal application and its classes. */
final class OcrWire {
    private static final String CORE="com.xiaomi.aicr.IAiCrCoreService";
    private static final String VISION="com.xiaomi.aicr.plugin.IVisionService";
    private OcrWire(){}
    static IBinder acquire(IBinder core,String address) throws RemoteException {
        if(!CORE.equals(core.getInterfaceDescriptor()))throw new IllegalStateException("不支持的识字服务");
        Binder callback=new Binder(){
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
                if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString("com.xiaomi.aicr.ICoreServiceCallback");return true;}
                if(code==1 || code==2){data.enforceInterface("com.xiaomi.aicr.ICoreServiceCallback");
                    if(code==1)data.readStrongBinder();else{data.readString();data.readLong();}
                    if(reply!=null)reply.writeNoException();return true;}
                return super.onTransact(code,data,reply,flags);
            }
        };
        return exchange(core,CORE,1,data->{data.writeStrongBinder(callback);data.writeString(address);
            data.writeString(VISION);data.writeString("V2");},reply->{
                IBinder vision=reply.readStrongBinder();Bundle status=reply.readTypedObject(Bundle.CREATOR);
                if(status==null || !status.containsKey("Status") || status.getInt("Status")!=0 || vision==null)
                    throw new IllegalStateException("系统识字引擎尚未就绪，请稍后重试");
                return vision;
            });
    }
    static String recognize(IBinder vision,Bitmap image) throws RemoteException {
        if(!VISION.equals(vision.getInterfaceDescriptor()) || !"V2".equals(exchange(vision,VISION,6,p->{},Parcel::readString)))
            throw new IllegalStateException("不支持的识字引擎版本");
        int session=exchange(vision,VISION,1,p->p.writeTypedObject(image,0),Parcel::readInt);
        if(session<0)throw new IllegalStateException("识字引擎无法读取图片");
        int lines=exchange(vision,VISION,3,p->p.writeInt(session),Parcel::readInt);
        if(lines<=0)throw new IllegalStateException("没有识别到文字");
        return exchange(vision,VISION,4,p->p.writeInt(session),OcrResultReader::read);
    }
    static void release(IBinder core,String address) throws RemoteException {
        exchange(core,CORE,2,p->p.writeString(address),p->null);
    }
    private static <T>T exchange(IBinder target,String descriptor,int code,Consumer<Parcel> write,Function<Parcel,T> read)
            throws RemoteException {
        Parcel input=Parcel.obtain(),output=Parcel.obtain();
        try{
            input.writeInterfaceToken(descriptor);write.accept(input);
            if(!target.transact(code,input,output,0))throw new RemoteException("Unsupported OCR operation");
            output.readException();return read.apply(output);
        }finally{input.recycle();output.recycle();}
    }
}
