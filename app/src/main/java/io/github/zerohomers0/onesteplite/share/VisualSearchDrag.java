package io.github.zerohomers0.onesteplite;

import android.app.Activity;
import android.content.ClipData;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewConfiguration;
import android.webkit.WebView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Turns the selected text or the visible image under a deliberate long press into native content. */
final class VisualSearchDrag implements AutoCloseable {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private final Activity activity;
    private final WebView web;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable inspect=this::inspectPage;
    private final int slop;
    private int generation;
    private boolean pressed,held,moved,starting,dragging,closed;
    private float downX,downY,rawX,rawY;
    private String text,captureId;
    private Rect imageBounds;
    private Bitmap activeImage;

    VisualSearchDrag(Activity activity,WebView web) {
        this.activity=activity;
        this.web=web;
        slop=ViewConfiguration.get(web.getContext()).getScaledTouchSlop();
        web.setOnTouchListener((view,event)->touch(event));
        web.setOnDragListener((view,event)->{
            if(event.getAction()==DragEvent.ACTION_DRAG_STARTED)return dragging || starting;
            if(event.getAction()==DragEvent.ACTION_DRAG_ENDED && dragging){
                dragging=false;
                releaseImage(event.getResult());
            }
            return event.getAction()!=DragEvent.ACTION_DROP;
        });
    }

    private boolean touch(MotionEvent event) {
        int action=event.getActionMasked();
        if(dragging)return true;
        if(action==MotionEvent.ACTION_DOWN){
            cancelPress();
            if(closed || !WorkspaceSettingsStore.read(activity).dragShareEnabled)return false;
            pressed=true;
            downX=event.getX();downY=event.getY();
            rawX=event.getRawX();rawY=event.getRawY();
            main.postDelayed(inspect,ViewConfiguration.getLongPressTimeout()+150L);
        }else if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL || event.getPointerCount()!=1){
            cancelPress();
        }else if(pressed && action==MotionEvent.ACTION_MOVE){
            rawX=event.getRawX();rawY=event.getRawY();
            if(Math.hypot(event.getX()-downX,event.getY()-downY)>slop){
                if(!held)cancelPress();
                else {moved=true;beginIfReady();}
            }
        }
        return dragging || (pressed && held && moved);
    }

    private void inspectPage() {
        if(!pressed || closed)return;
        held=true;
        int token=generation;
        web.evaluateJavascript(pageProbe(downX,downY),value->{
            if(!current(token))return;
            try{
                JSONObject answer=new JSONObject(value);
                text=answer.optString("text",null);
                if(text==null && answer.has("l"))imageBounds=imageBounds(answer);
                if(text==null && imageBounds==null)cancelPress();
                else beginIfReady();
            }catch(org.json.JSONException ignored){cancelPress();}
        });
    }

    static String pageProbe(float x,float y) {
        return """
                (()=>{const v=window.visualViewport;
                  const scale=(window.devicePixelRatio||1)*(v?v.scale:1);
                  const ox=v?v.offsetLeft:0,oy=v?v.offsetTop:0;
                  const x=%s/scale+ox,y=%s/scale+oy;
                  const target=document.elementFromPoint(x,y);
                  const img=target&&target.closest('img');
                  if(img&&img.complete&&img.naturalWidth){
                    const r=img.getBoundingClientRect();
                    return {l:(r.left-ox)*scale,t:(r.top-oy)*scale,r:(r.right-ox)*scale,b:(r.bottom-oy)*scale};
                  }
                  const selected=String(window.getSelection()||'').trim();
                  if(selected)return {text:selected.slice(0,100000)};
                  const caret=document.caretRangeFromPoint&&document.caretRangeFromPoint(x,y);
                  const node=caret&&caret.startContainer;
                  if(!node||node.nodeType!==3)return null;
                  const text=node.textContent.trim();
                  return text?{text:text.slice(0,100000)}:null;
                })()
                """.formatted(Float.toString(x),Float.toString(y));
    }

    private Rect imageBounds(JSONObject answer) throws org.json.JSONException {
        Rect rect=new Rect((int)Math.floor(answer.getDouble("l")),(int)Math.floor(answer.getDouble("t")),
                (int)Math.ceil(answer.getDouble("r")),(int)Math.ceil(answer.getDouble("b")));
        if(!rect.intersect(0,0,web.getWidth(),web.getHeight()) || rect.width()<16 || rect.height()<16)return null;
        int[] origin=new int[2];web.getLocationInWindow(origin);rect.offset(origin[0],origin[1]);
        return rect;
    }

    private void beginIfReady() {
        if(!pressed || !moved || starting || dragging)return;
        if(text!=null && !text.isBlank()){
            ContentDragShadow shadow=new ContentDragShadow(text,web.getResources().getDisplayMetrics().density);
            start(ClipData.newPlainText("网页文字",text),shadow,false);
        }else if(imageBounds!=null)copyImage(new Rect(imageBounds),generation);
    }

    private void copyImage(Rect bounds,int token) {
        starting=true;
        double scale=Math.min(1,800d/Math.max(bounds.width(),bounds.height()));
        Bitmap image=Bitmap.createBitmap(Math.max(1,(int)(bounds.width()*scale)),
                Math.max(1,(int)(bounds.height()*scale)),Bitmap.Config.ARGB_8888);
        try{
            PixelCopy.request(activity.getWindow(),bounds,image,result->{
                if(result!=PixelCopy.SUCCESS || !current(token)){
                    image.recycle();
                    if(current(token))fail();
                    return;
                }
                WORKER.execute(()->storeImage(image,token));
            },main);
        }catch(RuntimeException unavailable){image.recycle();fail();}
    }

    private void storeImage(Bitmap image,int token) {
        Bundle record;
        try{record=CaptureInboxStore.store(activity.getApplicationContext(),CaptureImageEncoder.encode(image));}
        catch(RuntimeException unavailable){
            image.recycle();main.post(()->{if(current(token))fail();});return;
        }
        main.post(()->{
            String id=record.getString("id");
            if(!current(token)){image.recycle();discard(id);return;}
            captureId=id;activeImage=image;
            ClipData clip=new ClipData("网页图片",new String[]{"image/jpeg"},
                    new ClipData.Item(Uri.parse(record.getString("uri"))));
            ContentDragShadow shadow=new ContentDragShadow(image,web.getResources().getDisplayMetrics().density);
            start(clip,shadow,true);
        });
    }

    private void start(ClipData clip,ContentDragShadow shadow,boolean image) {
        starting=true;
        shadow.setDark(HyperOsStyle.dark(activity));
        shadow.mark(clip);
        PersistableBundle metadata=clip.getDescription().getExtras();
        metadata.putDouble("onesteplite_module_pointer_x",rawX);
        metadata.putDouble("onesteplite_module_pointer_y",rawY);
        metadata.putString("onesteplite_source_package",activity.getPackageName());
        clip.getDescription().setExtras(metadata);
        try{
            dragging=web.startDragAndDrop(clip,shadow,null,View.DRAG_FLAG_GLOBAL
                    | (image?View.DRAG_FLAG_GLOBAL_URI_READ:0));
        }catch(RuntimeException unavailable){dragging=false;}
        cancelPress();
        if(!dragging){
            releaseImage(false);
            fail();
        }
    }

    private boolean current(int token){return pressed && !closed && token==generation;}
    private void fail(){
        cancelPress();
        android.widget.Toast.makeText(activity,"无法提起网页内容，请重试",android.widget.Toast.LENGTH_SHORT).show();
    }
    void cancelPress(){
        main.removeCallbacks(inspect);
        generation++;
        pressed=false;held=false;moved=false;starting=false;
        text=null;imageBounds=null;
    }
    private void discard(String id){
        if(id==null)return;
        WORKER.execute(()->{
            try{CaptureInboxStore.discard(activity.getApplicationContext(),id);}
            catch(RuntimeException ignored){/* Existing temporary inbox expiry applies. */}
        });
    }
    private void releaseImage(boolean consumed){
        if(activeImage!=null){activeImage.recycle();activeImage=null;}
        if(!consumed)discard(captureId);
        captureId=null;
    }
    @Override public void close(){
        closed=true;cancelPress();
        // An in-flight drop can still read the owned URI after the Activity goes away.
        releaseImage(dragging);
    }
}
