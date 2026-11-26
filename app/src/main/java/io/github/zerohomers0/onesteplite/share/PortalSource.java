package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.Context;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Host-side source window exists only for one validated Portal-to-native handoff. */
final class PortalSource implements AutoCloseable {
    private final View anchor;
    private final BooleanSupplier ready;
    private final Supplier<Rect> bounds;
    private final java.util.function.Consumer<android.net.Uri> imagePreview;
    private final SourceCaptureController fallbackCapture;
    private java.util.function.Predicate<String> activeViewSource=pkg->false;
    private Runnable fallbackJob;
    private boolean fallbackIssued;
    private float fallbackX,fallbackY;
    private static final java.util.concurrent.ExecutorService PREVIEW_WORKER=
            new java.util.concurrent.ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(1),
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile Bundle liftCandidate;
    private long liftAt;
    private float downX,downY;
    private boolean liftEligible,startingDrag;
    private final Handler main=new Handler(Looper.getMainLooper());
    private PortalInputCompat input;
    private MotionEvent pointer;
    private FrameLayout window;
    private android.app.Dialog selection;
    private ModalIslandLease island;
    private boolean selectingDrag;
    private final Runnable expire=this::clearWindow;
    private int generation;
    private long issued;
    private boolean validGesture;
    private Runnable pendingWords;
    private float wordX,wordY;
    private final IBinder endpoint=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws android.os.RemoteException {
            if(code!=PortalWire.STATUS && code!=PortalWire.OFFER)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(PortalWire.DESCRIPTOR);
            if(!moduleCaller())throw new SecurityException("Source relay caller");
            Bundle request=data.readBundle(PortalSource.class.getClassLoader());
            Bundle result=dispatch(code,request);reply.writeNoException();reply.writeBundle(result);return true;
        }
    };
    PortalSource(View anchor,BooleanSupplier ready,Supplier<Rect> bounds,java.util.function.Consumer<android.net.Uri> imagePreview){
        this.anchor=anchor;this.ready=ready;this.bounds=bounds;this.imagePreview=imagePreview;
        fallbackCapture=new SourceCaptureController(anchor,()->ready.getAsBoolean() && selection==null && !selectingDrag,bounds);
    }
    void setActiveViewSource(java.util.function.Predicate<String> source){activeViewSource=source;}
    void start() {
        if(input!=null)return;
        try {
            input=new PortalInputCompat(anchor.getContext().getClassLoader(),this::motion);
            Bundle request=new Bundle();request.putBinder("host",endpoint);
            anchor.getContext().getContentResolver().call(ContentInboxProvider.ROOT,"portal_register",null,request);
            ShareDiagnostics.event(ShareDiagnostics.Stage.POINTER,ShareDiagnostics.Code.READY);
        } catch(Throwable failure){close();android.util.Log.w(Tags.PROBE,"portal source unavailable",failure);}
    }
    void dragStarted(){
        generation++;issued=0;clearLiftCandidate();
        if(pendingWords!=null){main.removeCallbacks(pendingWords);pendingWords=null;}
        if(fallbackJob!=null){main.removeCallbacks(fallbackJob);fallbackJob=null;}
        fallbackCapture.close();validGesture=false;
    }
    private boolean moduleCaller() {
        try{return Binder.getCallingUid()==anchor.getContext().getPackageManager().getApplicationInfo(ContentInboxProvider.MODULE,0).uid;}
        catch(Exception unavailable){return false;}
    }
    private void motion(MotionEvent event) {
        int action=event.getActionMasked();
        if(window!=null && startingDrag){
            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL
                    || action==MotionEvent.ACTION_DOWN || event.getPointerCount()!=1)clearWindow();
            else if(action==MotionEvent.ACTION_MOVE && pointer!=null){
                if(event.getDownTime()!=pointer.getDownTime() || event.getDeviceId()!=pointer.getDeviceId()
                        || event.getPointerId(0)!=pointer.getPointerId(0))clearWindow();
                else {pointer.recycle();pointer=MotionEvent.obtainNoHistory(event);}
            }
        }
        if(window!=null || selection!=null || selectingDrag)return;
        if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_UP
                || action==MotionEvent.ACTION_CANCEL || event.getPointerCount()!=1)clearLiftCandidate();
        if(fallbackJob!=null && (event.getPointerCount()!=2 || event.getActionMasked()==MotionEvent.ACTION_UP
                || event.getActionMasked()==MotionEvent.ACTION_POINTER_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL || Math.hypot(event.getRawX()-fallbackX,event.getRawY()-fallbackY)>
                android.view.ViewConfiguration.get(anchor.getContext()).getScaledTouchSlop())){
            main.removeCallbacks(fallbackJob);fallbackJob=null;
        }
        if(pendingWords!=null && (event.getActionMasked()==MotionEvent.ACTION_UP
                || event.getActionMasked()==MotionEvent.ACTION_CANCEL || event.getPointerCount()!=2
                || event.getActionMasked()==MotionEvent.ACTION_POINTER_UP
                || Math.hypot(event.getRawX()-wordX,event.getRawY()-wordY)>
                        android.view.ViewConfiguration.get(anchor.getContext()).getScaledTouchSlop())) {
            main.removeCallbacks(pendingWords);pendingWords=null;
        }
        if(pointer!=null){pointer.recycle();pointer=null;}
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
            generation++;validGesture=event.getPointerCount()==1;fallbackIssued=false;
            liftEligible=validGesture;downX=event.getRawX();downY=event.getRawY();
        }
        if(event.getPointerCount()!=1)liftEligible=false;
        if(event.getPointerCount()>2)validGesture=false;
        if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL
                || event.getActionMasked()==MotionEvent.ACTION_POINTER_UP)return;
        if(validGesture && event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN))pointer=MotionEvent.obtainNoHistory(event);
        if(action==MotionEvent.ACTION_MOVE)moveLiftCandidate(event);
        if(event.getPointerCount()==2 && !fallbackIssued && pendingWords==null && selection==null && live()){
            fallbackIssued=true;fallbackX=event.getRawX();fallbackY=event.getRawY();
            int token=generation;
            fallbackJob=()->{fallbackJob=null;if(live() && pointer.getPointerCount()==2 && pendingWords==null && selection==null)
                fallbackCapture.extractText(text->{if(token==generation && ready.getAsBoolean()
                        && selection==null && window==null && pendingWords==null)openWords(text);});};
            main.postDelayed(fallbackJob,900L);
        }
    }

    private void moveLiftCandidate(MotionEvent event) {
        int slop=android.view.ViewConfiguration.get(anchor.getContext()).getScaledTouchSlop();
        long held=android.os.SystemClock.uptimeMillis()-event.getDownTime();
        int longPress=android.view.ViewConfiguration.getLongPressTimeout();
        double distance=Math.hypot(event.getRawX()-downX,event.getRawY()-downY);
        if(held<longPress && distance>slop)
            liftEligible=false;
        if(liftCandidate==null)return;
        if(held<longPress){if(!liftEligible)clearLiftCandidate();return;}
        if(distance<=slop*1.5f)return;
        if(held<longPress+150L || android.os.SystemClock.uptimeMillis()<liftAt || !live())return;
        Bundle request=liftCandidate;liftCandidate=null;
        request.putBoolean("lift",true);offer(request,new Bundle[1],new CountDownLatch(1),new AtomicBoolean());
    }

    private void clearLiftCandidate() {
        Bundle candidate=liftCandidate;liftCandidate=null;
        discardLiftCandidate(candidate);
    }

    private void discardLiftCandidate(Bundle candidate) {
        if(candidate==null)return;
        android.graphics.Bitmap bitmap=candidate.getParcelable("preview",android.graphics.Bitmap.class);
        if(bitmap!=null)bitmap.recycle();
        String id=candidate.getString("view_capture_id");
        if(id==null)return;
        android.content.ContentResolver resolver=anchor.getContext().getContentResolver();
        java.util.concurrent.CompletableFuture.runAsync(()->{
            try{resolver.call(ContentInboxProvider.ROOT,"discard_capture",id,null);}
            catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"unused image cleanup deferred",unavailable);}
        });
    }
    private boolean live() {
        Rect mainBounds=bounds.get();
        return pointer!=null && input!=null && ContentAccessCompat.currentProcessUser()
                && PortalGesturePolicy.live(ready.getAsBoolean(),anchor.isAttachedToWindow(),pointer.getPointerCount(),
                        pointer.getDownTime(),android.os.SystemClock.uptimeMillis())
                && mainBounds!=null && mainBounds.contains((int)downX,(int)downY);
    }
    private Bundle dispatch(int code,Bundle request) {
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean expired=new AtomicBoolean();Bundle[] result={null};
        main.post(()->{
            try {
            if(expired.get()){done.countDown();return;}
            if(code==PortalWire.STATUS)result[0]=status(request);
            else if(request!=null && live() && window==null && selection==null && !selectingDrag
                    && request.getInt("generation")==generation
                    && PortalGesturePolicy.offer(request.getLong("down"),pointer.getDownTime(),issued,android.os.SystemClock.uptimeMillis()))
                offer(request,result,done,expired);
            if(window==null || code==PortalWire.STATUS)done.countDown();
            }catch(RuntimeException unavailable){clearWindow();closeWords();done.countDown();
                android.util.Log.w(Tags.PROBE,"source handoff unavailable",unavailable);}
        });
        try{if(!done.await(700,TimeUnit.MILLISECONDS)){expired.set(true);main.post(this::clearWindow);return null;}}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();expired.set(true);return null;}
        return result[0];
    }
    private Bundle status(Bundle request) {
        if(!live() || window!=null || selection!=null)return null;
        if(request!=null && (request.getLong("down")!=pointer.getDownTime() || !liftEligible
                || pointer.getPointerCount()!=1 || !activeViewSource.test(request.getString("source"))))return null;
        issued=android.os.SystemClock.uptimeMillis();Bundle state=new Bundle();
        state.putLong("down",pointer.getDownTime());state.putInt("generation",generation);
        state.putBoolean("prefer_image",pointer.getPointerCount()==1);return state;
    }
    private void offer(Bundle request,Bundle[] result,CountDownLatch done,AtomicBoolean expired) {
        String uri=request.getString("uri"),text=request.getString("text");
        boolean region=request.getBoolean("region_only");
        if(request.getBoolean("view_source") && (pointer.getPointerCount()!=1
                || !activeViewSource.test(request.getString("source")))){done.countDown();return;}
        if(!region && uri==null && (text==null || text.isEmpty() || text.length()>ContentSharePolicy.MAX_TEXT)){done.countDown();return;}
        Rect rect=sourceRect(request);
        if(pointer.getPointerCount()==2 && rect!=null
                && rect.bottom<=SourceCaptureController.statusBarBottom(anchor)){done.countDown();return;}
        if(region && (pointer.getPointerCount()!=1 || rect==null || !rect.contains((int)downX,(int)downY))){
            done.countDown();return;
        }
        if(pointer.getPointerCount()==1 && !request.getBoolean("lift")){
            if(!liftEligible){done.countDown();return;}
            int priority=request.getInt("source_priority");
            if(liftCandidate!=null && !PortalGesturePolicy.replaces(liftCandidate.getInt("source_priority"),priority,
                    java.util.Objects.equals(liftCandidate.getString("text"),text))){
                done.countDown();return;
            }
            stageLift(request,uri,priority);
            ShareDiagnostics.event(ShareDiagnostics.Stage.POINTER,ShareDiagnostics.Code.ACCEPTED);
            Bundle reply=new Bundle();reply.putBoolean("accepted",true);result[0]=reply;done.countDown();return;
        }
        if(region){
            // The user still confirms the selected screenshot region in the crop pane.
            dragStarted();
            fallbackCapture.open(rect);
            done.countDown();return;
        }
        if(uri==null && !request.getBoolean("lift")) {
            boolean accepted=deferWords(text);Bundle reply=new Bundle();reply.putBoolean("accepted",accepted);
            result[0]=reply;done.countDown();return;
        }
        if(pointer.getPointerCount()!=1) {
            dragStarted();
            imagePreview.accept(android.net.Uri.parse(uri));
            if(pointer!=null){pointer.recycle();pointer=null;}
            Bundle reply=new Bundle();reply.putBoolean("accepted",true);result[0]=reply;done.countDown();return;
        }
        try{startNativeLift(request,uri,text,result,done,expired);}
        catch(RuntimeException unavailable){
            android.util.Log.w(Tags.PROBE,"native source window unavailable",unavailable);
            finishRejectedLift(!expired.get() && live());done.countDown();
        }
    }

    private android.graphics.Bitmap nativePreview;

    @android.annotation.SuppressLint("RtlHardcoded") // Physical display coordinates, not layout direction.
    private void startNativeLift(Bundle request,String uri,String text,Bundle[] result,CountDownLatch done,AtomicBoolean expired) {
        nativePreview=request.getParcelable("preview",android.graphics.Bitmap.class);
        ClipData clip=uri==null?ClipData.newPlainText("提取文字",text):new ClipData("提取图片",new String[]{"image/jpeg"},
                new ClipData.Item(android.net.Uri.parse(uri)));
        android.os.PersistableBundle metadata=new android.os.PersistableBundle();
        metadata.putString("onesteplite_source_package",request.getString("source"));
        clip.getDescription().setExtras(metadata);
        Context display=anchor.getContext().createDisplayContext(anchor.getDisplay()).createWindowContext(WindowTypes.SYSTEM_UI_DIALOG,null);
        window=new FrameLayout(display);View source;
        if(uri!=null){
            ImageView image=new ImageView(display);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(nativePreview);source=image;
        }
        else {TextView words=new TextView(display);words.setText(text);source=words;}
        source.setContentDescription(uri==null?text:"提取图片");window.addView(source,new FrameLayout.LayoutParams(240,240));
        WindowManager.LayoutParams attrs=new WindowManager.LayoutParams(240,240,WindowTypes.SYSTEM_UI_DIALOG,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,android.graphics.PixelFormat.TRANSLUCENT);
        attrs.setTitle(PortalDragInput.TITLE);attrs.gravity=android.view.Gravity.TOP|android.view.Gravity.LEFT;
        PortalInputCompat.bindSource(attrs,request.getString("source"),pointer);
        HostWindowPolicy.configure(attrs);window.setAlpha(0f);
        window.setOnDragListener((view,event)->{
            if(event.getAction()==DragEvent.ACTION_DRAG_ENDED)main.post(this::clearWindow);
            return event.getAction()!=DragEvent.ACTION_DROP;
        });
        FrameLayout sourceWindow=window;
        startingDrag=true;
        display.getSystemService(WindowManager.class).addView(sourceWindow,attrs);
        startWhenReady(sourceWindow,source,clip,generation,8,result,done,expired);
    }

    private void startWhenReady(FrameLayout sourceWindow,View source,ClipData clip,
            int token,int frames,Bundle[] result,CountDownLatch done,AtomicBoolean expired) {
        source.postOnAnimation(()->{
            if(window!=sourceWindow){done.countDown();return;}
            boolean current=!expired.get() && token==generation && live();
            boolean readySurface=current && PortalInputCompat.surfaceReady(source);
            if(current && !readySurface && frames>1){
                startWhenReady(sourceWindow,source,clip,token,frames-1,result,done,expired);
                return;
            }
            boolean accepted=false;
            try{if(readySurface)accepted=PortalInputCompat.start(source,clip,pointer);}
            catch(RuntimeException failure){android.util.Log.w(Tags.PROBE,"portal drag start rejected",failure);}
            startingDrag=false;
            Bundle reply=new Bundle();reply.putBoolean("accepted",accepted);result[0]=reply;
            if(!accepted)finishRejectedLift(current && token==generation && live());
            else {
                ShareDiagnostics.event(ShareDiagnostics.Stage.DRAG,ShareDiagnostics.Code.STARTED);
                main.postDelayed(expire,60_000L);
            }
            done.countDown();
        });
    }

    private void stageLift(Bundle request,String uri,int priority) {
        clearLiftCandidate();
        Bundle candidate=new Bundle(request);liftCandidate=candidate;
        boolean image=uri!=null || request.getBoolean("region_only");
        long now=android.os.SystemClock.uptimeMillis();
        liftAt=pointer.getDownTime()+android.view.ViewConfiguration.getLongPressTimeout()+150L;
        if(image && priority<PortalGesturePolicy.SYSTEM_IMAGE || priority==PortalGesturePolicy.VIEW_TEXT)
            liftAt=Math.max(liftAt,now+PortalGesturePolicy.PREFERRED_SOURCE_WAIT_MS);
        if(priority==PortalGesturePolicy.REGION)liftAt=Math.max(liftAt,now+350L);
        if(uri!=null)prepareLiftPreview(candidate,android.net.Uri.parse(uri));
        main.postDelayed(()->{
            if(liftCandidate==candidate && live())moveLiftCandidate(pointer);
        },Math.max(0L,liftAt-now));
    }

    private Rect sourceRect(Bundle request) {
        int[] edges=request.getIntArray("source_rect");
        if(edges==null || edges.length!=4 || !PortalGesturePolicy.sameTouch(request.getFloatArray("source_touch"),
                downX,downY,android.view.ViewConfiguration.get(anchor.getContext()).getScaledTouchSlop()*2))return null;
        Rect rect=new Rect(edges[0],edges[1],edges[2],edges[3]);
        Rect mainBounds=bounds.get();
        return mainBounds!=null && !rect.isEmpty() && mainBounds.contains(rect)?rect:null;
    }

    private void finishRejectedLift(boolean current) {
        ShareDiagnostics.event(ShareDiagnostics.Stage.DRAG,current?ShareDiagnostics.Code.FAILED:ShareDiagnostics.Code.CANCELLED);
        clearWindow();
        if(current && ready.getAsBoolean())android.widget.Toast.makeText(anchor.getContext(),
                "未能提起内容，请松手后重试",android.widget.Toast.LENGTH_SHORT).show();
    }
    private void prepareLiftPreview(Bundle candidate,android.net.Uri uri) {
        android.content.ContentResolver resolver=anchor.getContext().getContentResolver();
        PREVIEW_WORKER.execute(()->{
            if(liftCandidate!=candidate)return;
            android.graphics.Bitmap preview;
            try{preview=resolver.loadThumbnail(uri,new android.util.Size(240,240),null);}
            catch(java.io.IOException | RuntimeException unavailable){return;}
            if(!main.post(()->{
                if(liftCandidate==candidate)candidate.putParcelable("preview",preview);
                else preview.recycle();
            }))preview.recycle();
        });
    }

    private boolean deferWords(String text) {
        if(fallbackJob!=null){main.removeCallbacks(fallbackJob);fallbackJob=null;}fallbackCapture.close();
        if(!live() || selection!=null || pointer.getPointerCount()!=2)return false;
        if(pendingWords!=null)return true;
        long down=pointer.getDownTime();int token=generation;
        wordX=pointer.getRawX();wordY=pointer.getRawY();
        pendingWords=()->{
            pendingWords=null;
            if(token==generation && live() && pointer.getDownTime()==down)openWords(text);
        };
        main.postDelayed(pendingWords,0L);return true;
    }
    private void clearWindow() {
        main.removeCallbacks(expire);
        startingDrag=false;
        android.graphics.Bitmap preview=nativePreview;nativePreview=null;
        if(window==null){if(preview!=null)preview.recycle();return;}
        FrameLayout old=window;window=null;
        generation++;issued=0;validGesture=false;
        if(pointer!=null){pointer.recycle();pointer=null;}
        try{old.getContext().getSystemService(WindowManager.class).removeViewImmediate(old);}
        catch(RuntimeException detached){android.util.Log.w(Tags.PROBE,"portal source detached",detached);}
        finally{if(preview!=null)preview.recycle();}
    }
    @Override public void close() {
        clearLiftCandidate();
        if(fallbackJob!=null){main.removeCallbacks(fallbackJob);fallbackJob=null;}fallbackCapture.close();
        if(pendingWords!=null){main.removeCallbacks(pendingWords);pendingWords=null;}
        generation++;issued=0;validGesture=false;clearWindow();
        closeWords();
        if(pointer!=null){pointer.recycle();pointer=null;}
        if(input!=null){
            try{Bundle request=new Bundle();request.putBinder("unregister",endpoint);
                anchor.getContext().getContentResolver().call(ContentInboxProvider.ROOT,"portal_register",null,request);
            }catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"portal source unregister failed",unavailable);}
            input.close();input=null;
        }
    }
    private boolean openWords(String text) {
        if(selection!=null || !ready.getAsBoolean())return false;
        dragStarted();
        if(pointer!=null){pointer.recycle();pointer=null;}
        Context context=anchor.getContext().createDisplayContext(anchor.getDisplay())
                .createWindowContext(WindowTypes.SYSTEM_UI_DIALOG,null);
        Context themed=new android.view.ContextThemeWrapper(context,HyperOsStyle.dark(context)
                ?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert);
        android.app.Dialog opened=new WorkspaceDialog(themed,this::closeWords);selection=opened;
        opened.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        WordSelectionPane pane=new WordSelectionPane(themed,text,this::beginWords,this::closeWords);
        pane.setOnDragListener((view,event)->{
            if(event.getAction()==DragEvent.ACTION_DRAG_ENDED && selection==opened) {
                selectingDrag=false;ContentDragMotion.close();
                if(event.getResult())main.post(this::closeWords);
                else WorkspacePopupStyle.dragWindow(opened.getWindow(),Math.min(100,HyperOsStyle.dp(anchor.getContext(),28)),true);
            }
            return event.getAction()!=DragEvent.ACTION_DROP;
        });
        opened.setContentView(pane);opened.setOnDismissListener(ignored->{if(selection==opened)closeWords();});
        android.view.Window target=opened.getWindow();if(target==null){closeWords();return false;}
        target.setType(WindowTypes.SYSTEM_UI_DIALOG);target.setBackgroundDrawableResource(android.R.color.transparent);
        WindowManager.LayoutParams attrs=target.getAttributes();attrs.setTitle("OneStepLiteContentPanel");
        attrs.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        HostWindowPolicy.configure(attrs);target.setAttributes(attrs);
        try{opened.show();WorkspacePopupStyle.secondary(target,themed);island=new ModalIslandLease(pane);island.start();return true;}
        catch(RuntimeException unavailable){closeWords();return false;}
    }
    private boolean beginWords(View view,String text) {
        if(selection==null || !ready.getAsBoolean() || selectingDrag || !ContentAccessCompat.currentProcessUser())return false;
        ClipData clip=ClipData.newPlainText("选中文字",text);
        final boolean accepted;
        try{ContentDragShadow shadow=new ContentDragShadow(view,1,text);shadow.mark(clip);
            accepted=view.startDragAndDrop(clip,shadow,new SharePayload(clip),View.DRAG_FLAG_GLOBAL);}
        catch(RuntimeException unavailable){ContentDragMotion.close();android.util.Log.w(Tags.PROBE,"selected text drag unavailable",unavailable);return false;}
        if(!accepted)ContentDragMotion.close();
        if(accepted) {
            selectingDrag=true;WorkspacePopupStyle.dragWindow(selection.getWindow(),Math.min(100,HyperOsStyle.dp(anchor.getContext(),28)),false);
        }
        return accepted;
    }
    private void closeWords() {
        selectingDrag=false;
        if(island!=null){island.close();island=null;}
        android.app.Dialog old=selection;selection=null;
        if(old!=null){old.setOnDismissListener(null);old.dismiss();}
    }
}
