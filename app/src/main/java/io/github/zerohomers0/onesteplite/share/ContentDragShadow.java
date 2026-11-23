package io.github.zerohomers0.onesteplite;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** A bounded content preview leaves the drop target visible and marks multi-item drags. */
final class ContentDragShadow extends View.DragShadowBuilder {
    private final int count;
    private final float unit,width,height,top;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline=new Path();
    private final Bitmap thumbnail;
    private final String caption;
    ContentDragShadow(View view,int count) {
        super(view);this.count=count;unit=view.getResources().getDisplayMetrics().density;
        boolean image=view instanceof ImageView;
        width=(image?84:128)*unit;height=(image?96:92)*unit;top=12*unit;
        CharSequence text=view instanceof TextView?((TextView)view).getText():view.getContentDescription();
        caption=text==null?"文件":text.toString().replace("，长按拖曳分享","").replace('\n',' ');
        paint.setFilterBitmap(true);thumbnail=image?snapshot(view):null;
    }

    private Bitmap snapshot(View source) {
        if(source.getWidth()<=0 || source.getHeight()<=0)return null;
        Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(width-16*unit)),
                Math.max(1,Math.round(height-top-16*unit)),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        float scale=Math.max((float)bitmap.getWidth()/source.getWidth(),(float)bitmap.getHeight()/source.getHeight());
        canvas.translate((bitmap.getWidth()-source.getWidth()*scale)/2,(bitmap.getHeight()-source.getHeight()*scale)/2);
        canvas.scale(scale,scale);
        try {source.draw(canvas);return bitmap;}
        catch(RuntimeException unsupported){bitmap.recycle();return null;}
    }
    @Override public void onProvideShadowMetrics(Point dimensions,Point touch){
        dimensions.set(Math.round(width),Math.round(height));touch.set(Math.round(width/2),Math.round(3*unit));
    }
    @Override public void onDrawShadow(Canvas canvas){
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xfffafbff);
        paint.setShadowLayer(4*unit,0,2*unit,0x55000000);
        RectF card=new RectF(4*unit,top,width-4*unit,height-6*unit);
        outline.reset();outline.addRoundRect(card,3*unit,3*unit,Path.Direction.CW);
        outline.moveTo(width/2-9*unit,top+unit);outline.lineTo(width/2,3*unit);
        outline.lineTo(width/2+9*unit,top+unit);outline.close();
        canvas.drawPath(outline,paint);paint.clearShadowLayer();
        if(thumbnail!=null)canvas.drawBitmap(thumbnail,null,
                new RectF(8*unit,top+4*unit,width-8*unit,height-10*unit),paint);
        else drawSummary(canvas);
        if(count>1){
            paint.setColor(0xff347ff4);canvas.drawCircle(width-14*unit,top+10*unit,10*unit,paint);
            paint.setColor(0xffffffff);paint.setTextSize(11*unit);paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(Integer.toString(count),width-14*unit,top+14*unit,paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSummary(Canvas canvas) {
        paint.setColor(0xffedf3ff);canvas.drawRoundRect(8*unit,top+4*unit,width-8*unit,height-10*unit,2*unit,2*unit,paint);
        paint.setColor(0xff347ff4);paint.setTextSize(11*unit);paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(getView() instanceof TextView?"文字":"文件",14*unit,top+18*unit,paint);
        paint.setColor(0xff283345);paint.setTextSize(12*unit);
        int offset=0;
        for(int line=0;line<2 && offset<caption.length();line++) {
            int length=paint.breakText(caption,offset,caption.length(),true,width-28*unit,null);
            if(length==0)break;
            String text=caption.substring(offset,offset+length);offset+=length;
            if(line==1 && offset<caption.length())text=android.text.TextUtils.ellipsize(
                    caption.substring(offset-length),new android.text.TextPaint(paint),width-28*unit,android.text.TextUtils.TruncateAt.END).toString();
            canvas.drawText(text,14*unit,top+(36+line*16)*unit,paint);
        }
    }
}
