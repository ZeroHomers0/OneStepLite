package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Adapts validated native content shadows; original payload and source input stay untouched. */
final class NativeContentDragCard {
    private static final String ORIGINAL="native_text_original_surface";
    private static final String REPLACEMENT="native_text_card_surface";
    private static boolean geometryReady;
    private NativeContentDragCard(){}
    static void install(ClassLoader loader,Supplier<Object> display,BooleanSupplier active) {
        try {
            geometryReady=NativeDragGeometry.install(loader,active);
            Class<?> controller=XposedHelpers.findClass("com.android.server.wm.DragDropController",loader);
            XposedBridge.hookAllMethods(controller,"performDrag",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if(!active.getAsBoolean() || (call.args.length!=11 && call.args.length!=13) || !(call.args[4] instanceof SurfaceControl)
                            || !(call.args[call.args.length-1] instanceof ClipData))return;
                    try{replace(call,display.get());}
                    catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"native text card left unchanged",unavailable);}
                }
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    Object owned=call.getObjectExtra("native_card_geometry");
                    if(owned instanceof ContentDragShadow){
                        ContentDragShadow card=(ContentDragShadow)owned;
                        if(geometryReady && !call.hasThrowable() && call.getResult()!=null)
                            NativeDragGeometry.attach(call.thisObject,display.get(),card,(SurfaceControl)call.args[4],active);
                        else card.recycleThumbnail();
                    }
                    try{if(!call.hasThrowable() && call.getResult()!=null && active.getAsBoolean())glass(call,display.get());}
                    catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"drag glass unavailable",unavailable);}
                    try {
                        Object original=call.getObjectExtra(ORIGINAL);if(original instanceof SurfaceControl)((SurfaceControl)original).release();
                        if(call.hasThrowable() || call.getResult()==null) {
                            Object surface=call.getObjectExtra(REPLACEMENT);
                            if(surface instanceof SurfaceControl)remove((SurfaceControl)surface);
                        }
                    }catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"native text card cleanup failed",unavailable);}
                }
            });
        }catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"native text card hook unavailable",unavailable);}
    }
    private static void glass(XC_MethodHook.MethodHookParam call,Object display) {
        if(display==null || (call.args.length!=11 && call.args.length!=13) || !(call.args[call.args.length-1] instanceof ClipData))return;
        ClipData clip=(ClipData)call.args[call.args.length-1];int offset=ContentDragShadow.offset(clip.getDescription());
        android.os.PersistableBundle extras=clip.getDescription().getExtras();
        int touch=extras==null?0:extras.getInt("onesteplite_shadow_touch_y",0);
        float unit=(touch-offset)/3f,width=((Number)call.args[call.args.length-3]).floatValue()*2;
        if(offset<=0 || unit<.5f || unit>8 || !Float.isFinite(width) || width<=8*unit || width>2048 || touch>2048)return;
        Object wm=XposedHelpers.getObjectField(display,"mWmService");
        synchronized(XposedHelpers.getObjectField(wm,"mGlobalLock")) {
            Object state=XposedHelpers.getObjectField(call.thisObject,"mDragState");
            if(state==null || XposedHelpers.getObjectField(state,"mData")!=clip
                    || XposedHelpers.getObjectField(state,"mSurfaceControl")!=call.args[4])return;
            float corner=12*unit;
            float[][] regions={{Math.min(80,24*unit),1,corner,corner,corner,corner,4*unit,12*unit,width-4*unit,touch-3*unit}};
            try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()) {
                XposedHelpers.callMethod(transaction,"setBlurRegions",call.args[4],regions);transaction.apply();
            }catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"drag glass uses translucent fallback",unavailable);}
        }
    }
    private static void replace(XC_MethodHook.MethodHookParam call,Object display) {
        int end=call.args.length-1;
        ClipData original=(ClipData)call.args[end];String text=plainText(original);
        boolean image=images(original),file=files(original);
        if(display==null || text==null && !image && !file
                || (((Integer)call.args[3])&View.DRAG_FLAG_GLOBAL)==0)return;
        Object wm=XposedHelpers.getObjectField(display,"mWmService");Context context;
        float scale=1,offsetX=0,offsetY=0;
        float physicalX=Float.NaN,physicalY=Float.NaN;
        String sourcePackage;
        synchronized(XposedHelpers.getObjectField(wm,"mGlobalLock")) {
            Map<?,?> windows=(Map<?,?>)XposedHelpers.getObjectField(wm,"mWindowMap");
            android.os.IBinder token=((android.os.IInterface)call.args[2]).asBinder();Object source=windows.get(token);
            if(source==null || XposedHelpers.callMethod(source,"getDisplayContent")!=display
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(source,"isVisible")))return;
            int uid=XposedHelpers.getIntField(source,"mOwnerUid");
            if(uid!=(Integer)call.args[1] || uid/100000!=0)return;
            WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)XposedHelpers.getObjectField(source,"mAttrs");
            sourcePackage=attrs.packageName;
            boolean module=ContentProtocol.MODULE.equals(attrs.packageName);
            context=(Context)XposedHelpers.getObjectField(wm,"mContext");
            if(module){
                android.os.PersistableBundle data=original.getDescription().getExtras();
                if(data==null || ContentDragShadow.offset(original.getDescription())<=0)return;
                try{if(context.getPackageManager().getApplicationInfo(ContentProtocol.MODULE,0).uid!=uid)return;}
                catch(Exception unavailable){return;}
                physicalX=(float)data.getDouble("onesteplite_module_pointer_x",Double.NaN);
                physicalY=(float)data.getDouble("onesteplite_module_pointer_y",Double.NaN);
                if(!Float.isFinite(physicalX) || !Float.isFinite(physicalY) || physicalX<0 || physicalY<0
                        || physicalX>=context.getResources().getDisplayMetrics().widthPixels || physicalY>=context.getResources().getDisplayMetrics().heightPixels)return;
            }
            boolean own=WindowIdentity.ownsPicker(source,display) || module;
            if(own && !geometryReady)return;
            if(own && "OneStepLitePortalSource".contentEquals(attrs.getTitle())){
                Object origin=XposedHelpers.getObjectField(attrs,"accessibilityTitle");
                if(origin instanceof CharSequence identity)sourcePackage=PortalDragInput.sourcePackage(identity);
            }
            if((Packages.SYSTEM_UI.equals(attrs.packageName) && !own)
                    || (attrs.flags&WindowManager.LayoutParams.FLAG_SECURE)!=0)return;
            Object task=XposedHelpers.callMethod(source,"getTask");
            if(!own && (task==null || !Integer.valueOf(1).equals(XposedHelpers.callMethod(task,"getWindowingMode"))))return;
            Object spec=own?null:XposedHelpers.getObjectField(display,"mMagnificationSpec");
            if(spec!=null){
                scale=XposedHelpers.getFloatField(spec,"scale");offsetX=XposedHelpers.getFloatField(spec,"offsetX");
                offsetY=XposedHelpers.getFloatField(spec,"offsetY");
                if(!Float.isFinite(scale) || scale<=0 || scale>1 || !Float.isFinite(offsetX) || !Float.isFinite(offsetY))return;
            }
            context=(Context)XposedHelpers.getObjectField(wm,"mContext");
        }
        float startX=Float.isFinite(physicalX)?physicalX:((Number)call.args[end-4]).floatValue()*scale+offsetX;
        float startY=Float.isFinite(physicalY)?physicalY:((Number)call.args[end-3]).floatValue()*scale+offsetY;
        if(!Float.isFinite(startX) || !Float.isFinite(startY))return;
        ContentDragShadow card=text!=null?new ContentDragShadow(text,context.getResources().getDisplayMetrics().density,original.getItemCount())
                :image?imageCard((SurfaceControl)call.args[4],context,original.getItemCount())
                :new ContentDragShadow(original.getDescription().getLabel()==null?"文件":original.getDescription().getLabel().toString(),
                        context.getResources().getDisplayMetrics().density,original.getItemCount(),false);
        if(card==null)return;
        card.setDark(HyperOsStyle.dark(context));
        Point size=new Point(),touch=new Point();card.onProvideShadowMetrics(size,touch);
        SurfaceControl surface;
        try{surface=draw(card,size);}catch(Throwable failed){card.recycleThumbnail();throw failed;}
        ClipData clip=new ClipData(new ClipDescription(original.getDescription()),original.getItemAt(0));
        for(int i=1;i<original.getItemCount();i++)clip.addItem(original.getItemAt(i));
        card.mark(clip);call.setObjectExtra(ORIGINAL,call.args[4]);call.setObjectExtra(REPLACEMENT,surface);
        if(geometryReady)NativeDragGeometry.mark(clip);
        android.os.PersistableBundle metadata=clip.getDescription().getExtras();
        metadata.putString("onesteplite_source_package",sourcePackage);clip.getDescription().setExtras(metadata);
        call.setObjectExtra("native_card_geometry",card);
        // performDrag starts in the source's logical space; later pointer events are physical.
        // Normalize the first frame once, retaining the new card's physical hotspot unchanged.
        call.args[4]=surface;call.args[end-4]=startX;call.args[end-3]=startY;
        call.args[3]=(Integer)call.args[3]|View.DRAG_FLAG_OPAQUE;
        call.args[end-2]=(float)touch.x;call.args[end-1]=(float)touch.y;call.args[end]=clip;
        android.util.Log.i(Tags.PROBE,"native content drag card applied");
    }
    static boolean images(ClipData clip) {
        if(clip.getItemCount()<1 || clip.getItemCount()>ContentSharePolicy.MAX_ITEMS || !clip.getDescription().hasMimeType("image/*"))return false;
        for(int i=0;i<clip.getItemCount();i++) {
            ClipData.Item item=clip.getItemAt(i);
            if(item.getIntent()!=null || item.getUri()==null || !"content".equals(item.getUri().getScheme()))return false;
        }
        return true;
    }
    static boolean files(ClipData clip) {
        if(clip.getItemCount()<1 || clip.getItemCount()>ContentSharePolicy.MAX_ITEMS)return false;
        ClipDescription types=clip.getDescription();boolean binary=false;
        for(int i=0;i<types.getMimeTypeCount();i++){
            String mime=types.getMimeType(i);if(!ContentSharePolicy.accepts(mime))return false;
            if(!mime.startsWith("text/"))binary=true;
        }
        if(!binary)return false;
        for(int i=0;i<clip.getItemCount();i++){
            ClipData.Item item=clip.getItemAt(i);
            if(item.getIntent()!=null || item.getUri()==null || !"content".equals(item.getUri().getScheme()))return false;
        }
        return true;
    }
    static ContentDragShadow imageCard(SurfaceControl source,Context context,int count) {
        Object hardware=null;android.graphics.Bitmap bitmap=null,copy=null;SurfaceControl mirror=null;
        try {
            // Never show or reparent the application's surface. Only the short-lived mirror is made visible,
            // on an unbound stack which is checked against every currently connected display first.
            for(android.view.Display display:context.getSystemService(android.hardware.display.DisplayManager.class).getDisplays())
                if(Integer.valueOf(Integer.MAX_VALUE).equals(XposedHelpers.callMethod(display,"getLayerStack")))return null;
            mirror=(SurfaceControl)XposedHelpers.callStaticMethod(SurfaceControl.class,"mirrorSurface",source);
            try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()) {
                java.util.concurrent.CountDownLatch committed=new java.util.concurrent.CountDownLatch(1);
                transaction.addTransactionCommittedListener(Runnable::run,committed::countDown);
                XposedHelpers.callMethod(transaction,"setLayerStack",mirror,Integer.MAX_VALUE);
                XposedHelpers.callMethod(transaction,"show",mirror);transaction.apply();
                if(!committed.await(120,java.util.concurrent.TimeUnit.MILLISECONDS))return null;
            }
            ClassLoader loader=SurfaceControl.class.getClassLoader();Class<?> capture;
            try{capture=XposedHelpers.findClass("android.window.ScreenCaptureInternal",loader);}
            catch(Throwable older){capture=XposedHelpers.findClass("android.window.ScreenCapture",loader);}
            Class<?> builder=XposedHelpers.findClass(capture.getName()+"$LayerCaptureArgs$Builder",loader);
            Object args=XposedHelpers.newInstance(builder,mirror);
            XposedHelpers.callMethod(args,"setChildrenOnly",false);
            XposedHelpers.callMethod(args,"setSourceCrop",new android.graphics.Rect(0,0,1024,1024));
            XposedHelpers.callMethod(args,"setFrameScale",.5f);
            Object result=XposedHelpers.callStaticMethod(capture,"captureLayers",XposedHelpers.callMethod(args,"build"));
            if(result==null)return null;hardware=XposedHelpers.callMethod(result,"getHardwareBuffer");
            if(Boolean.TRUE.equals(XposedHelpers.callMethod(result,"containsSecureLayers")))return null;
            bitmap=(android.graphics.Bitmap)XposedHelpers.callMethod(result,"asBitmap");
            if(bitmap==null || bitmap.getWidth()>512 || bitmap.getHeight()>512)return null;
            copy=bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888,false);
            if(copy==null)return null;
            android.graphics.Bitmap preview=trim(copy);return preview==null?null:new ContentDragShadow(preview,
                    context.getResources().getDisplayMetrics().density,count);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return null;}
        catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"native image shadow retained",unavailable);return null;}
        finally{
            if(bitmap!=null)bitmap.recycle();if(copy!=null)copy.recycle();
            try{if(hardware!=null)XposedHelpers.callMethod(hardware,"close");}
            finally{if(mirror!=null)remove(mirror);}
        }
    }
    private static android.graphics.Bitmap trim(android.graphics.Bitmap bitmap) {
        int width=bitmap.getWidth(),height=bitmap.getHeight(),left=width,top=height,right=-1,bottom=-1;
        int[] pixels=new int[width*height];bitmap.getPixels(pixels,0,width,0,0,width,height);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)if((pixels[y*width+x]>>>24)>32){
            left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
        }
        if(right<left || bottom<top)return null;
        android.graphics.Bitmap cropped=android.graphics.Bitmap.createBitmap(bitmap,left,top,right-left+1,bottom-top+1);
        return cropped==bitmap?bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888,false):cropped;
    }
    private static String plainText(ClipData clip) {
        if(clip.getItemCount()<1 || clip.getItemCount()>ContentSharePolicy.MAX_ITEMS || !clip.getDescription().hasMimeType("text/plain"))return null;
        StringBuilder result=new StringBuilder();
        for(int i=0;i<clip.getItemCount();i++) {
            ClipData.Item item=clip.getItemAt(i);CharSequence text=item.getText();
            if(item.getIntent()!=null || item.getUri()!=null || text==null || text.length()==0
                    || text.length()>ContentSharePolicy.MAX_TEXT-result.length()-(i>0?1:0))return null;
            if(i>0)result.append('\n');result.append(text);
        }
        return result.toString();
    }
    private static SurfaceControl draw(ContentDragShadow card,Point size) {
        SurfaceControl control=new SurfaceControl.Builder().setName("OneStepLiteTextDragCard")
                .setBufferSize(size.x,size.y).setFormat(PixelFormat.TRANSLUCENT).build();
        Surface surface=null;boolean drawn=false;
        try {
            surface=new Surface(control);
            Canvas canvas=surface.lockCanvas(null);
            try{canvas.drawColor(android.graphics.Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR);card.onDrawShadow(canvas);}
            finally{surface.unlockCanvasAndPost(canvas);}
            drawn=true;return control;
        }finally{if(surface!=null)surface.release();if(!drawn)remove(control);}
    }
    private static void remove(SurfaceControl surface) {
        try{if(surface.isValid())try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()){
            XposedHelpers.callMethod(transaction,"remove",surface);transaction.apply();}}
        finally{surface.release();}
    }
}
