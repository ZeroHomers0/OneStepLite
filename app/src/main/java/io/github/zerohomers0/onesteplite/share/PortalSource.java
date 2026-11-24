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
    private Runnable fallbackJob;
    private boolean fallbackIssued;
    private float fallbackX,fallbackY;
    private Bundle liftCandidate;
    private float liftX,liftY;
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
    private int wordPointers;
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
        liftCandidate=null;
        if(pendingWords!=null){main.removeCallbacks(pendingWords);pendingWords=null;}
        if(fallbackJob!=null){main.removeCallbacks(fallbackJob);fallbackJob=null;}
        fallbackCapture.close();validGesture=false;
    }
    private boolean moduleCaller() {
        try{return Binder.getCallingUid()==anchor.getContext().getPackageManager().getApplicationInfo(ContentInboxProvider.MODULE,0).uid;}
        catch(Exception unavailable){return false;}
    }
    private void motion(MotionEvent event) {
        if(window!=null)return;
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN || event.getActionMasked()==MotionEvent.ACTION_UP
                || event.getActionMasked()==MotionEvent.ACTION_CANCEL || event.getPointerCount()!=1)liftCandidate=null;
        if(fallbackJob!=null && (event.getPointerCount()!=2 || event.getActionMasked()==MotionEvent.ACTION_UP
                || event.getActionMasked()==MotionEvent.ACTION_POINTER_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL || Math.hypot(event.getRawX()-fallbackX,event.getRawY()-fallbackY)>
                android.view.ViewConfiguration.get(anchor.getContext()).getScaledTouchSlop())){
            main.removeCallbacks(fallbackJob);fallbackJob=null;
        }
        if(pendingWords!=null && (event.getActionMasked()==MotionEvent.ACTION_UP
                || event.getActionMasked()==MotionEvent.ACTION_CANCEL || event.getPointerCount()!=wordPointers
                || event.getActionMasked()==MotionEvent.ACTION_POINTER_UP
                || Math.hypot(event.getRawX()-wordX,event.getRawY()-wordY)>
                        android.view.ViewConfiguration.get(anchor.getContext()).getScaledTouchSlop())) {
            main.removeCallbacks(pendingWords);pendingWords=null;
        }
        if(pointer!=null){pointer.recycle();pointer=null;}
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){validGesture=event.getPointerCount()==1;fallbackIssued=false;}
        if(event.getPointerCount()>2)validGesture=false;
        if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL
                || event.getActionMasked()==MotionEvent.ACTION_POINTER_UP)return;
        if(validGesture && event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN))pointer=MotionEvent.obtainNoHistory(event);
        if(liftCandidate!=null && event.getActionMasked()==MotionEvent.ACTION_MOVE && Math.hypot(event.getRawX()-liftX,event.getRawY()-liftY)>
                android.view.ViewConfiguration.get(anchor.getContext()).getScaledTouchSlop()){
            Bundle request=liftCandidate;liftCandidate=null;
            if(android.os.SystemClock.uptimeMillis()-event.getDownTime()>=android.view.ViewConfiguration.getLongPressTimeout()+150L){
                request.putBoolean("lift",true);offer(request,new Bundle[1],new CountDownLatch(1),new AtomicBoolean());
            }
        }
        if(event.getPointerCount()==2 && !fallbackIssued && pendingWords==null && selection==null && live()){
            fallbackIssued=true;fallbackX=event.getRawX();fallbackY=event.getRawY();
            fallbackJob=()->{fallbackJob=null;if(live() && pointer.getPointerCount()==2 && pendingWords==null && selection==null)
                fallbackCapture.extractText(text->{if(ready.getAsBoolean() && selection==null)openWords(text);});};
            main.postDelayed(fallbackJob,900L);
        }
    }
    private boolean live() {
        Rect mainBounds=bounds.get();
        return pointer!=null && input!=null && ContentAccessCompat.currentProcessUser()
                && PortalGesturePolicy.live(ready.getAsBoolean(),anchor.isAttachedToWindow(),pointer.getPointerCount(),
                        pointer.getDownTime(),android.os.SystemClock.uptimeMillis())
                && mainBounds!=null && mainBounds.contains((int)pointer.getRawX(),(int)pointer.getRawY());
    }
    private Bundle dispatch(int code,Bundle request) {
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean expired=new AtomicBoolean();Bundle[] result={null};
        main.post(()->{
            try {
            if(expired.get()){done.countDown();return;}
            if(code==PortalWire.STATUS)result[0]=status();
            else if(request!=null && live() && window==null && request.getInt("generation")==generation
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
    private Bundle status() {
        if(!live() || window!=null || selection!=null)return null;
        issued=android.os.SystemClock.uptimeMillis();Bundle state=new Bundle();
        state.putLong("down",pointer.getDownTime());state.putInt("generation",generation);return state;
    }
    @android.annotation.SuppressLint("RtlHardcoded") // Physical display coordinates, not layout direction.
    private void offer(Bundle request,Bundle[] result,CountDownLatch done,AtomicBoolean expired) {
        Context context=anchor.getContext();String uri=request.getString("uri"),text=request.getString("text");
        if(uri==null && (text==null || text.isEmpty() || text.length()>ContentSharePolicy.MAX_TEXT)){done.countDown();return;}
        if(pointer.getPointerCount()==1 && !request.getBoolean("lift")){
            liftCandidate=new Bundle(request);liftX=pointer.getRawX();liftY=pointer.getRawY();
            ShareDiagnostics.event(ShareDiagnostics.Stage.POINTER,ShareDiagnostics.Code.ACCEPTED);
            Bundle reply=new Bundle();reply.putBoolean("accepted",true);result[0]=reply;done.countDown();return;
        }
        if(uri==null && !request.getBoolean("lift")) {
            boolean accepted=deferWords(text);Bundle reply=new Bundle();reply.putBoolean("accepted",accepted);
            result[0]=reply;done.countDown();return;
        }
        if(pointer.getPointerCount()!=1) {
            imagePreview.accept(android.net.Uri.parse(uri));
            pointer.recycle();pointer=null;validGesture=false;generation++;issued=0;
            Bundle reply=new Bundle();reply.putBoolean("accepted",true);result[0]=reply;done.countDown();return;
        }
        ClipData clip=uri==null?ClipData.newPlainText("选中文字",text):new ClipData("提取图片",new String[]{"image/jpeg"},
                new ClipData.Item(android.net.Uri.parse(uri)));
        Context display=context.createDisplayContext(anchor.getDisplay()).createWindowContext(WindowTypes.SYSTEM_UI_DIALOG,null);
        window=new FrameLayout(display);View source;
        if(uri!=null){ImageView image=new ImageView(display);image.setImageURI(android.net.Uri.parse(uri));source=image;}
        else {TextView words=new TextView(display);words.setText(text);source=words;}
        source.setContentDescription(uri==null?text:"提取图片");window.addView(source,new FrameLayout.LayoutParams(240,240));
        WindowManager.LayoutParams attrs=new WindowManager.LayoutParams(240,240,WindowTypes.SYSTEM_UI_DIALOG,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,android.graphics.PixelFormat.TRANSLUCENT);
        attrs.setTitle(PortalDragInput.TITLE);attrs.gravity=android.view.Gravity.TOP|android.view.Gravity.LEFT;
        try{PortalInputCompat.bindSource(attrs,request.getString("source"));}
        catch(RuntimeException unavailable){clearWindow();done.countDown();return;}
        HostWindowPolicy.configure(attrs);window.setAlpha(0f);
        window.setOnDragListener((view,event)->{
            if(event.getAction()==DragEvent.ACTION_DRAG_ENDED)main.post(this::clearWindow);
            return event.getAction()!=DragEvent.ACTION_DROP;
        });
        try {
            display.getSystemService(WindowManager.class).addView(window,attrs);
            int token=generation;
            source.postOnAnimation(()->{
                boolean accepted=false;
                try{if(!expired.get() && token==generation && live())accepted=PortalInputCompat.start(source,clip,pointer);}
                catch(RuntimeException failure){android.util.Log.w(Tags.PROBE,"portal drag start rejected",failure);}
                Bundle reply=new Bundle();reply.putBoolean("accepted",accepted);result[0]=reply;
                ShareDiagnostics.event(ShareDiagnostics.Stage.DRAG,accepted?ShareDiagnostics.Code.STARTED:ShareDiagnostics.Code.FAILED);
                if(!accepted)clearWindow();else main.postDelayed(expire,60_000L);
                done.countDown();
            });
        } catch(RuntimeException unavailable){clearWindow();done.countDown();}
    }
    private boolean deferWords(String text) {
        if(fallbackJob!=null){main.removeCallbacks(fallbackJob);fallbackJob=null;}fallbackCapture.close();
        if(!live() || selection!=null)return false;
        if(pendingWords!=null)return true;
        long down=pointer.getDownTime();int token=generation;
        wordX=pointer.getRawX();wordY=pointer.getRawY();wordPointers=pointer.getPointerCount();
        // Two-finger extraction is explicit; ordinary long-press menus retain their original timing.
        long hold=Math.max(1100,android.view.ViewConfiguration.getLongPressTimeout()+350L);
        long wait=pointer.getPointerCount()==1?Math.max(0,hold-(android.os.SystemClock.uptimeMillis()-down)):0;
        pendingWords=()->{
            pendingWords=null;
            if(token==generation && live() && pointer.getDownTime()==down)openWords(text);
        };
        main.postDelayed(pendingWords,wait);return true;
    }
    private void clearWindow() {
        main.removeCallbacks(expire);
        if(window==null)return;FrameLayout old=window;window=null;
        generation++;issued=0;validGesture=false;
        if(pointer!=null){pointer.recycle();pointer=null;}
        try{old.getContext().getSystemService(WindowManager.class).removeViewImmediate(old);}
        catch(RuntimeException detached){android.util.Log.w(Tags.PROBE,"portal source detached",detached);}
    }
    @Override public void close() {
        liftCandidate=null;
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
        Context context=anchor.getContext().createDisplayContext(anchor.getDisplay())
                .createWindowContext(WindowTypes.SYSTEM_UI_DIALOG,null);
        Context themed=new android.view.ContextThemeWrapper(context,HyperOsStyle.dark(context)
                ?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert);
        android.app.Dialog opened=new WorkspaceDialog(themed,this::closeWords);selection=opened;
        opened.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        WordSelectionPane pane=new WordSelectionPane(themed,text,this::beginWords);
        WorkspacePopupStyle.heading(pane,"选词",this::closeWords);
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
