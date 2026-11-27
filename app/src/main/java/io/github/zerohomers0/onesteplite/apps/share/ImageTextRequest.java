package io.github.zerohomers0.onesteplite;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** One explicit local OCR request owns its image, binding, timeout and UI callback. */
final class ImageTextRequest implements AutoCloseable {
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Context context;
    private final Uri source;
    private final java.util.concurrent.atomic.AtomicReference<Bitmap> pendingImage;
    private final BiConsumer<String,String> result;
    private final CountDownLatch connected=new CountDownLatch(1);
    private final String address=UUID.randomUUID().toString();
    private volatile IBinder service;
    private volatile boolean closed;
    private boolean bound;
    private boolean started;
    private final Runnable timeout=()->finish(null,"识别超时，请稍后重试");
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){service=binder;connected.countDown();}
        @Override public void onServiceDisconnected(ComponentName name){service=null;connected.countDown();}
        @Override public void onNullBinding(ComponentName name){connected.countDown();}
        @Override public void onBindingDied(ComponentName name){service=null;connected.countDown();}
    };
    ImageTextRequest(Context context,Uri source,BiConsumer<String,String> result) {
        this(context,source,null,result);
    }
    ImageTextRequest(Context context,Bitmap image,BiConsumer<String,String> result) {
        this(context,null,image,result);
    }
    private ImageTextRequest(Context context,Uri source,Bitmap image,BiConsumer<String,String> result) {
        this.context=context;this.source=source;this.result=result;
        pendingImage=new java.util.concurrent.atomic.AtomicReference<>(image);
    }
    void start() {
        if(closed || started)return;started=true;
        if(!BUSY.compareAndSet(false,true)){finish(null,"已有图片正在识别，请稍后重试");return;}
        try {
            Intent intent=new Intent("com.xiaomi.aicr.access.AICR_ENGINE").setComponent(new ComponentName(
                    "com.xiaomi.aicr","com.xiaomi.aicr.access.AiCrCoreService")).putExtra("bind_type",address);
            bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);
            if(!bound)throw new IllegalStateException("无法连接系统识字服务");
            main.postDelayed(timeout,25_000L);BackgroundExecutor.execute(this::recognize);
        }catch(RuntimeException unavailable){BUSY.set(false);finish(null,"系统识字服务不可用");}
    }
    private void recognize() {
        Bitmap bitmap=null;IBinder core=null;String text=null,error=null;
        try {
            if(!connected.await(10,TimeUnit.SECONDS) || closed || service==null)throw new IllegalStateException("系统识字服务未就绪");
            core=service;
            bitmap=pendingImage.getAndSet(null);
            if(bitmap==null && source!=null)bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(),source),(decoder,info,input)->{
                int width=info.getSize().getWidth(),height=info.getSize().getHeight();
                if((long)width*height>ContentCapturePolicy.MAX_CAPTURE_PIXELS)throw new IllegalArgumentException("Image too large");
                int[] size=ContentCapturePolicy.fit(width,height);decoder.setTargetSize(size[0],size[1]);
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            if(!closed && bitmap!=null)text=OcrWire.recognize(OcrWire.acquire(core,address),bitmap);
            if(text==null || text.trim().isEmpty())error="没有识别到文字";
        }catch(Exception unavailable){error="图片识字失败，请确认系统智能服务可用";
            android.util.Log.w(Tags.PROBE,"image text recognition unavailable",unavailable);
        }finally{
            if(core!=null)try{OcrWire.release(core,address);}catch(Exception ignored){/* Binding cleanup remains mandatory. */}
            if(bitmap!=null)bitmap.recycle();BUSY.set(false);
        }
        String recognized=text,failure=error;main.post(()->finish(recognized,failure));
    }
    private void finish(String text,String error) {
        if(closed)return;close();result.accept(text,error);
    }
    @Override public void close() {
        if(closed)return;closed=true;main.removeCallbacks(timeout);connected.countDown();
        Bitmap pending=pendingImage.getAndSet(null);if(pending!=null)pending.recycle();
        if(bound){bound=false;try{context.unbindService(connection);}catch(RuntimeException unavailable){
            android.util.Log.w(Tags.PROBE,"OCR binding cleanup failed",unavailable);}}
    }
}
