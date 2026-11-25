package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** User-drawn region on one frozen main-window frame, without touching the source application's UI. */
final class CaptureCropView extends View {
    private final Bitmap image;
    private final Rect initial;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF fit=new RectF(),selection=new RectF();
    private float startX,startY;
    CaptureCropView(Context context,Bitmap image,Rect initial){super(context);this.image=image;
        this.initial=initial==null?null:new Rect(initial);
        setContentDescription("拖动框选主窗图片区域");setFocusable(true);}

    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight) {
        Rect previous=fit.width()>0 && fit.height()>0?selectedPixels():initial;
        float scale=Math.min(width/(float)image.getWidth(),height/(float)image.getHeight());
        float w=image.getWidth()*scale,h=image.getHeight()*scale;
        fit.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2);
        if(previous==null)selection.set(fit);
        else selection.set(fit.left+previous.left*scale,fit.top+previous.top*scale,
                fit.left+previous.right*scale,fit.top+previous.bottom*scale);
    }

    @Override protected void onDraw(Canvas canvas) {
        if(image.isRecycled())return;
        paint.setStyle(Paint.Style.FILL);canvas.drawBitmap(image,null,fit,paint);paint.setColor(0x88000000);
        canvas.drawRect(fit.left,fit.top,fit.right,selection.top,paint);
        canvas.drawRect(fit.left,selection.bottom,fit.right,fit.bottom,paint);
        canvas.drawRect(fit.left,selection.top,selection.left,selection.bottom,paint);
        canvas.drawRect(selection.right,selection.top,fit.right,selection.bottom,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(HyperOsStyle.dp(getContext(),2));
        paint.setColor(HyperOsStyle.CONTROL_ACCENT);canvas.drawRect(selection,paint);paint.setStyle(Paint.Style.FILL);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x=Math.max(fit.left,Math.min(fit.right,event.getX()));
        float y=Math.max(fit.top,Math.min(fit.bottom,event.getY()));
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){startX=x;startY=y;selection.set(x,y,x,y);invalidate();return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE || event.getActionMasked()==MotionEvent.ACTION_UP) {
            selection.set(Math.min(startX,x),Math.min(startY,y),Math.max(startX,x),Math.max(startY,y));invalidate();
            if(event.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;
        }
        return true;
    }

    @Override public boolean performClick(){super.performClick();return true;}

    Rect selectedPixels() {
        if(fit.width()<=0 || fit.height()<=0)return new Rect();
        return new Rect(Math.max(0,Math.round((selection.left-fit.left)*image.getWidth()/fit.width())),
                Math.max(0,Math.round((selection.top-fit.top)*image.getHeight()/fit.height())),
                Math.min(image.getWidth(),Math.round((selection.right-fit.left)*image.getWidth()/fit.width())),
                Math.min(image.getHeight(),Math.round((selection.bottom-fit.top)*image.getHeight()/fit.height())));
    }
}
