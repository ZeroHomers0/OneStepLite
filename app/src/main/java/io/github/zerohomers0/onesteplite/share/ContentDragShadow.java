package io.github.zerohomers0.onesteplite;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.view.View;

/** A bounded content preview leaves the drop target visible and marks multi-item drags. */
final class ContentDragShadow extends View.DragShadowBuilder {
    private final int size,count;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    ContentDragShadow(View view,int count){super(view);this.count=count;size=Math.round(88*view.getResources().getDisplayMetrics().density);}
    @Override public void onProvideShadowMetrics(Point dimensions,Point touch){
        dimensions.set(size,size);touch.set(size/2,size-1);
    }
    @Override public void onDrawShadow(Canvas canvas){
        View source=getView();if(source==null || source.getWidth()==0 || source.getHeight()==0)return;
        paint.setColor(0xfffafafa);canvas.drawRoundRect(0,0,size,size,8,8,paint);
        int save=canvas.save();canvas.clipRect(3,3,size-3,size-3);
        float scale=Math.min((size-6f)/source.getWidth(),(size-6f)/source.getHeight());
        canvas.translate((size-source.getWidth()*scale)/2,(size-source.getHeight()*scale)/2);
        canvas.scale(scale,scale);source.draw(canvas);canvas.restoreToCount(save);
        if(count>1){
            paint.setColor(0xff347ff4);canvas.drawCircle(size*.8f,size*.2f,size*.18f,paint);
            paint.setColor(0xffffffff);paint.setTextSize(size*.22f);paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(Integer.toString(count),size*.8f,size*.28f,paint);
        }
    }
}
