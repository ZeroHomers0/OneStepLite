package io.github.zerohomers0.onesteplite;

import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
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
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private final View anchor;
    private final Context context;
    private final BooleanSupplier ready;
    private final Supplier<Rect> bounds;
    private final Object pixels=new Object();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private Bitmap snapshot,result;
    private Rect requested;
    private CaptureCropView crop;
    private String storedId;
    private ModalIslandLease island;
    private volatile int generation;
    private int owner=-1;
    private boolean closed=true,busy,consumed,dragging;

    SourceCaptureController(View anchor,BooleanSupplier ready,Supplier<Rect> bounds) {
        this.anchor=anchor;context=anchor.getContext();this.ready=ready;this.bounds=bounds;
    }

    void open() {
        close();Rect mainBounds=bounds.get();
        if(!ready.getAsBoolean() || mainBounds==null || mainBounds.isEmpty() || !ContentAccessCompat.currentProcessUser())return;
        if((long)mainBounds.width()*mainBounds.height()>ContentCapturePolicy.MAX_CAPTURE_PIXELS){message("主窗画面过大，暂时无法截取");return;}
        owner=CurrentUser.id();closed=false;consumed=false;requested=new Rect(mainBounds);int token=++generation;
        Rect region=new Rect(requested);
        // OEM WMS can retain a dismissed dialog surface beyond the next two UI frames.
        // This is a one-shot settling delay, not a compositor-commit acknowledgement.
        main.postDelayed(()->{
            if(!valid(token))return;
            anchor.postOnAnimation(()->anchor.postOnAnimation(()->WORKER.execute(()->capture(token,region))));
        },300L);
        main.postDelayed(()->{if(token==generation && dialog==null)close();},10_000L);
    }

    private void capture(int token,Rect region) {
        Bitmap frame=null;
        try {
            if(token!=generation || !WallpaperSnapshot.canCapture(context))return;
            frame=SwapPreview.captureRegion(context.getClassLoader(),context.getResources().getDisplayMetrics().widthPixels,
                    context.getResources().getDisplayMetrics().heightPixels,region);
            if(!WallpaperSnapshot.canCapture(context))throw new IllegalStateException("Scene locked");
            Bitmap captured=frame;frame=null;
            main.post(()->{
                if(!valid(token)){captured.recycle();if(token==generation)close();return;}
                synchronized(pixels){snapshot=captured;}
                try{showCrop();}catch(RuntimeException unavailable){message("无法打开框选页面");close();}
            });
        } catch(Throwable unavailable) {
            main.post(()->{if(token==generation){message("此界面无法截取，请使用应用原有分享");close();}});
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
        LinearLayout body=body(themed,"框选主窗画面 · 截取结果为图片");
        crop=new CaptureCropView(themed,snapshot);body.addView(crop,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(themed);button(actions,"取消",this::close);button(actions,"使用选区",this::prepare);
        body.addView(actions,new LinearLayout.LayoutParams(-1,dp(48)));dialog.setContentView(body);
        dialog.setOnDismissListener(d->close());Window window=dialog.getWindow();if(window==null){close();return;}
        window.setType(WindowTypes.SYSTEM_UI_DIALOG);window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        WindowManager.LayoutParams attrs=window.getAttributes();attrs.setTitle("OneStepLiteContentPanel");
        attrs.gravity=Gravity.TOP|Gravity.START;attrs.y=requested.top;
        boolean rtl=context.getResources().getConfiguration().getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
        attrs.x=rtl?context.getResources().getDisplayMetrics().widthPixels-requested.right:requested.left;
        HostWindowPolicy.configure(attrs);window.setAttributes(attrs);window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDecorFitsSystemWindows(false);dialog.show();window.setLayout(requested.width(),requested.height());
        island=new ModalIslandLease(body);island.start();
    }

    private void prepare() {
        if(busy || crop==null || closed)return;Rect selection=crop.selectedPixels();
        if(!ContentCapturePolicy.validCrop(selection.left,selection.top,selection.right,selection.bottom,snapshot.getWidth(),snapshot.getHeight())) {
            message("选区太小，请重新框选");return;
        }
        busy=true;int token=generation;message("正在准备拖曳图片…");
        WORKER.execute(()->encode(token,selection));
    }

    private void encode(int token,Rect selection) {
        Bitmap picture=null;String created=null;
        try {
            int[] size=ContentCapturePolicy.fit(selection.width(),selection.height());
            synchronized(pixels) {
                if(token!=generation || snapshot==null)return;
                picture=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
                new Canvas(picture).drawBitmap(snapshot,selection,new Rect(0,0,size[0],size[1]),null);
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
            main.post(()->{if(token==generation){busy=false;message("无法准备图片，请重新框选或使用原有分享");}});
        } finally {if(picture!=null)picture.recycle();if(created!=null)discard(created);}
    }

    private void showResult(Uri uri) {
        if(dialog==null)return;LinearLayout body=body(dialog.getContext(),"长按图片，拖到分享入口或小窗");
        ImageView image=new ImageView(dialog.getContext());image.setImageBitmap(result);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("截取的主窗图片，长按拖曳分享");body.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(dialog.getContext());button(actions,"关闭",this::close);body.addView(actions,new LinearLayout.LayoutParams(-1,dp(48)));
        image.setOnLongClickListener(view->{
            try {
                ClipData clip=new ClipData("主窗图片",new String[]{"image/jpeg"},new ClipData.Item(uri));
                ContentDragShadow shadow=new ContentDragShadow(view,1);shadow.mark(clip);
                dragging=view.startDragAndDrop(clip,shadow,null,View.DRAG_FLAG_GLOBAL|View.DRAG_FLAG_GLOBAL_URI_READ);
                if(dragging && dialog!=null && dialog.getWindow()!=null){dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                    dialog.getWindow().getDecorView().setAlpha(0);}return dragging;
            } catch(RuntimeException unavailable){message("图片暂时无法拖曳");return false;}
        });
        image.setOnDragListener((view,event)->{
            if(event.getAction()==DragEvent.ACTION_DRAG_ENDED && dragging) {
                dragging=false;consumed=event.getResult();if(consumed)close();
                else if(dialog!=null && dialog.getWindow()!=null){dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                    dialog.getWindow().getDecorView().setAlpha(1);}
            }
            return event.getAction()!=DragEvent.ACTION_DROP;
        });
        dialog.setContentView(body);
        if(island!=null)island.close();island=new ModalIslandLease(body);island.start();
    }

    private LinearLayout body(Context themed,String title) {
        LinearLayout body=new LinearLayout(themed);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(8),dp(8),dp(8),dp(8));
        body.setBackgroundColor(HyperOsStyle.page(themed));TextView text=new TextView(themed);text.setText(title);text.setTextSize(12);
        text.setTextColor(HyperOsStyle.textSecondary(themed));text.setPadding(dp(4),dp(4),dp(4),dp(8));body.addView(text);return body;
    }

    private void button(LinearLayout actions,String label,Runnable action) {
        TextView button=new TextView(actions.getContext());button.setText(label);button.setTextSize(14);button.setGravity(Gravity.CENTER);
        button.setTextColor(HyperOsStyle.textPrimary(actions.getContext()));button.setBackground(HyperOsStyle.controlButton(actions.getContext(),false,10));
        button.setOnClickListener(v->action.run());LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1);
        params.setMargins(dp(3),dp(3),dp(3),dp(3));actions.addView(button,params);
    }

    private void discard(String id) {WORKER.execute(()->{
        try{context.getContentResolver().call(ContentInboxProvider.ROOT,"discard_capture",id,null);}
        catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"capture discard unavailable",unavailable);}
    });}

    @Override public void close() {
        if(closed)return;closed=true;generation++;busy=false;dragging=false;
        Dialog old=dialog;dialog=null;if(old!=null)old.dismiss();
        if(island!=null){island.close();island=null;}
        synchronized(pixels){if(snapshot!=null){snapshot.recycle();snapshot=null;}}
        if(result!=null){result.recycle();result=null;}
        if(storedId!=null && !consumed)discard(storedId);storedId=null;crop=null;
    }
    private int dp(int value){return HyperOsStyle.dp(context,value);}
    private void message(String text){android.widget.Toast.makeText(context,text,android.widget.Toast.LENGTH_SHORT).show();}
}
