package io.github.zerohomers0.onesteplite;

import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Explicit main-window crop; closed scenes discard buffers and any unconsumed private capture. */
final class SourceCaptureController implements AutoCloseable {
    private static final ExecutorService WORKER=BackgroundExecutor.single("OneStepLite-source-capture");
    private final View anchor;
    private final Context context;
    private final BooleanSupplier ready;
    private final Supplier<Rect> bounds;
    private final Object pixels=new Object();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private Bitmap snapshot,result;
    private Rect requested,initialCrop;
    private CaptureCropView crop;
    private TextView confirmCrop;
    private String storedId;
    private ModalIslandLease island;
    private volatile int generation;
    private int owner=-1;
    private boolean closed=true,busy,consumed,dragging;
    private java.util.function.Consumer<String> textResult;
    private ImageTextRequest recognition;

    SourceCaptureController(View anchor,BooleanSupplier ready,Supplier<Rect> bounds) {
        this.anchor=anchor;context=anchor.getContext();this.ready=ready;this.bounds=bounds;
    }

    void open() {
        open(null);
    }

    void open(Rect suggested) {
        open(suggested,null);
    }

    private void open(Rect suggested,java.util.function.Consumer<String> recognize) {
        close();Rect mainBounds=bounds.get();
        if(!ready.getAsBoolean() || mainBounds==null || mainBounds.isEmpty() || !ContentAccessCompat.currentProcessUser())return;
        if(suggested!=null && (!mainBounds.contains(suggested)
                || !ContentCapturePolicy.validCrop(suggested.left-mainBounds.left,suggested.top-mainBounds.top,
                suggested.right-mainBounds.left,suggested.bottom-mainBounds.top,mainBounds.width(),mainBounds.height())))return;
        if((long)mainBounds.width()*mainBounds.height()>ContentCapturePolicy.MAX_CAPTURE_PIXELS){message("主窗画面过大，暂时无法截取");return;}
        owner=CurrentUser.id();closed=false;consumed=false;requested=new Rect(mainBounds);int token=++generation;
        initialCrop=suggested==null?null:new Rect(suggested);
        if(initialCrop!=null)initialCrop.offset(-requested.left,-requested.top);
        Rect region=new Rect(requested);
        if(recognize!=null){
            region.top=Math.max(region.top,statusBarBottom(anchor));
            if(region.isEmpty()){close();return;}
            textResult=recognize;
        }
        // OEM WMS can retain a dismissed dialog surface beyond the next two UI frames.
        // This is a one-shot settling delay, not a compositor-commit acknowledgement.
        main.postDelayed(()->{
            if(!valid(token))return;
            anchor.postOnAnimation(()->anchor.postOnAnimation(()->WORKER.execute(()->capture(token,region))));
        },300L);
        main.postDelayed(()->{if(token==generation && dialog==null && recognition==null)close();},10_000L);
    }

    void extractText(java.util.function.Consumer<String> completed){open(null,completed);}

    static int statusBarBottom(View anchor) {
        android.view.WindowInsets insets=anchor.getRootWindowInsets();
        if(insets!=null){
            int top=insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top;
            if(top>0)return top;
        }
        return DeviceProfile.statusBarHeightPx(anchor.getContext());
    }

    private void capture(int token,Rect region) {
        Bitmap frame=null;
        try {
            if(token!=generation || !WallpaperSnapshot.canCapture(context))return;
            frame=ScreenCaptureCompat.captureRegion(context.getClassLoader(),context.getResources().getDisplayMetrics().widthPixels,
                    context.getResources().getDisplayMetrics().heightPixels,region);
            if(!WallpaperSnapshot.canCapture(context))throw new IllegalStateException("Scene locked");
            Bitmap captured=frame;frame=null;
            main.post(()->{
                if(!valid(token)){captured.recycle();if(token==generation)close();return;}
                synchronized(pixels){snapshot=captured;}
                if(textResult!=null){
                    busy=true;
                    Rect full=new Rect(0,0,captured.getWidth(),captured.getHeight());
                    WORKER.execute(()->encode(token,full,true));return;
                }
                try{showCrop();}catch(RuntimeException unavailable){message("无法打开框选页面");close();}
            });
        } catch(Throwable unavailable) {
            android.util.Log.w(Tags.PROBE,"source capture failed",unavailable);
            ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.FAILED);
            String detail=unavailable instanceof NoSuchMethodError || unavailable instanceof ClassNotFoundException
                    || unavailable instanceof NoClassDefFoundError ? "当前系统版本暂不支持截取，请使用应用原有分享"
                    : "此界面无法截取，请使用应用原有分享";
            main.post(()->{if(token==generation){message(detail);close();}});
        } finally {if(frame!=null)frame.recycle();}
    }

    private boolean valid(int token) {
        return token==generation && !closed && owner==CurrentUser.id() && ready.getAsBoolean()
                && requested.equals(bounds.get()) && anchor.isAttachedToWindow();
    }

    private void showCrop() {
        Context windowContext=context.createDisplayContext(anchor.getDisplay()).createWindowContext(WindowTypes.SYSTEM_UI_DIALOG,null);
        Context themed=new android.view.ContextThemeWrapper(windowContext,HyperOsStyle.dark(context)
                ?android.R.style.Theme_Material_Dialog_NoActionBar:android.R.style.Theme_Material_Light_Dialog_NoActionBar);
        dialog=new Dialog(themed);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body=createCropPage(themed);
        dialog.setContentView(body);
        dialog.setOnDismissListener(d->close());Window window=dialog.getWindow();if(window==null){close();return;}
        window.setType(WindowTypes.SYSTEM_UI_DIALOG);window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        WindowManager.LayoutParams attrs=window.getAttributes();attrs.setTitle("OneStepLiteContentPanel");
        attrs.gravity=Gravity.TOP|Gravity.START;attrs.y=requested.top;
        boolean rtl=context.getResources().getConfiguration().getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
        attrs.x=rtl?context.getResources().getDisplayMetrics().widthPixels-requested.right:requested.left;
        HostWindowPolicy.configure(attrs);window.setAttributes(attrs);window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDecorFitsSystemWindows(false);dialog.show();WorkspacePopupStyle.glassWindow(window,themed);window.setLayout(requested.width(),requested.height());
        island=new ModalIslandLease(body);island.start();
    }

    private LinearLayout createCropPage(Context themed) {
        crop=new CaptureCropView(themed,snapshot,initialCrop);
        LinearLayout body=body(themed,"框选主窗",crop);
        LinearLayout actions=new LinearLayout(themed);
        confirmCrop=WorkspacePopupStyle.action(themed,"使用选区",true,this::prepare);
        actions.addView(confirmCrop,WorkspacePopupStyle.actionCell(themed));
        LinearLayout.LayoutParams footer=new LinearLayout.LayoutParams(-1,-2);
        footer.topMargin=dp(8);
        body.addView(actions,footer);
        return body;
    }

    private void prepare() {
        if(busy || crop==null || closed)return;Rect selection=crop.selectedPixels();
        if(!ContentCapturePolicy.validCrop(selection.left,selection.top,selection.right,selection.bottom,snapshot.getWidth(),snapshot.getHeight())) {
            message("选区太小，请重新框选");return;
        }
        setPreparing(true);int token=generation;
        WORKER.execute(()->encode(token,selection,false));
    }

    private void encode(int token,Rect selection,boolean recognize) {
        Bitmap picture=null;String created=null;
        try {
            int[] size=ContentCapturePolicy.fit(selection.width(),selection.height());
            synchronized(pixels) {
                if(token!=generation || snapshot==null)return;
                picture=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
                new Canvas(picture).drawBitmap(snapshot,selection,new Rect(0,0,size[0],size[1]),null);
            }
            if(recognize) {
                Bitmap input=picture;
                if(main.post(()->{
                    if(!valid(token)){input.recycle();if(token==generation)close();return;}
                    recognizeCapture(token,input);
                }))picture=null;
                return;
            }
            byte[] encoded=CaptureImageEncoder.encode(picture);
            if(token!=generation || !WallpaperSnapshot.canCapture(context))return;
            Bundle input=new Bundle();input.putByteArray("image",encoded);
            Bundle reply=context.getContentResolver().call(ContentInboxProvider.ROOT,"capture_image",null,input);
            if(reply==null || !ContentInboxPolicy.validId(reply.getString("id")))throw new IllegalStateException("No capture record");
            created=reply.getString("id");String id=created;Bitmap readyPicture=picture;picture=null;created=null;
            main.post(()->{
                if(!valid(token)){readyPicture.recycle();discard(id);if(token==generation)close();return;}
                storedId=id;result=readyPicture;busy=false;
                try{showResult(Uri.parse(reply.getString("uri")));}
                catch(RuntimeException unavailable){message("无法显示截取图片");close();return;}
                synchronized(pixels){if(snapshot!=null){snapshot.recycle();snapshot=null;}}
            });
        } catch(RuntimeException unavailable) {
            main.post(()->{if(token==generation){setPreparing(false);message("无法准备图片，请重新框选或使用原有分享");}});
        } finally {if(picture!=null)picture.recycle();if(created!=null)discard(created);}
    }

    private void recognizeCapture(int token,Bitmap image){
        ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.STARTED);
        recognition=new ImageTextRequest(context,image,(text,error)->{
            if(!valid(token)){close();return;}
            java.util.function.Consumer<String> completed=textResult;
            close();if(error!=null){ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.FAILED);message(error);return;}
            ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.ACCEPTED);if(completed!=null)completed.accept(text);
        });recognition.start();
        synchronized(pixels){if(snapshot!=null){snapshot.recycle();snapshot=null;}}
    }

    private void showResult(Uri uri) {
        if(dialog==null)return;
        Context themed=dialog.getContext();
        ImageView image=new ImageView(themed);
        image.setImageBitmap(result);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("截取的主窗图片，长按拖曳分享");
        LinearLayout body=body(themed,"长按拖曳截图",image);
        image.setOnLongClickListener(view->{
            try {
                ClipData clip=new ClipData("主窗图片",new String[]{"image/jpeg"},new ClipData.Item(uri));
                ContentDragShadow shadow=new ContentDragShadow(view,1);shadow.mark(clip);
                dragging=view.startDragAndDrop(clip,shadow,null,View.DRAG_FLAG_GLOBAL|View.DRAG_FLAG_GLOBAL_URI_READ);
                if(!dragging)ContentDragMotion.close();
                if(dragging && dialog!=null && dialog.getWindow()!=null)WorkspacePopupStyle.dragWindow(dialog.getWindow(),Math.min(100,dp(28)),false);
                return dragging;
            } catch(RuntimeException unavailable){ContentDragMotion.close();message("图片暂时无法拖曳");return false;}
        });
        image.setOnDragListener((view,event)->{
            if(event.getAction()==DragEvent.ACTION_DRAG_ENDED && dragging) {
                ContentDragMotion.close();dragging=false;consumed=event.getResult();if(consumed)close();
                else if(dialog!=null && dialog.getWindow()!=null)WorkspacePopupStyle.dragWindow(dialog.getWindow(),Math.min(100,dp(28)),true);
            }
            return event.getAction()!=DragEvent.ACTION_DROP;
        });
        dialog.setContentView(body);
        crop=null;confirmCrop=null;
        if(island!=null)island.close();island=new ModalIslandLease(body);island.start();
    }

    private LinearLayout body(Context themed,String title,View preview) {
        LinearLayout body=new LinearLayout(themed);
        body.setOrientation(LinearLayout.VERTICAL);
        WorkspacePopupStyle.decorate(body,null);
        WorkspacePopupStyle.heading(body,title,this::close);
        GradientDrawable surface=new GradientDrawable();
        surface.setColor(HyperOsStyle.controlFill(themed,false));
        surface.setCornerRadius(dp(10));
        preview.setBackground(surface);
        preview.setClipToOutline(true);
        body.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        return body;
    }

    private void setPreparing(boolean preparing) {
        busy=preparing;
        if(confirmCrop!=null){
            confirmCrop.setEnabled(!preparing);
            confirmCrop.setText(preparing?"正在准备…":"使用选区");
        }
    }

    private void discard(String id) {WORKER.execute(()->{
        try{context.getContentResolver().call(ContentInboxProvider.ROOT,"discard_capture",id,null);}
        catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"capture discard unavailable",unavailable);}
    });}

    @Override public void close() {
        if(closed)return;closed=true;generation++;busy=false;dragging=false;
        textResult=null;if(recognition!=null){recognition.close();recognition=null;}
        Dialog old=dialog;dialog=null;if(old!=null)old.dismiss();
        if(island!=null){island.close();island=null;}
        synchronized(pixels){if(snapshot!=null){snapshot.recycle();snapshot=null;}}
        if(result!=null){result.recycle();result=null;}
        if(storedId!=null && !consumed)discard(storedId);storedId=null;crop=null;confirmCrop=null;initialCrop=null;
    }
    private int dp(int value){return HyperOsStyle.dp(context,value);}
    private void message(String text){android.widget.Toast.makeText(context,text,android.widget.Toast.LENGTH_SHORT).show();}
}
