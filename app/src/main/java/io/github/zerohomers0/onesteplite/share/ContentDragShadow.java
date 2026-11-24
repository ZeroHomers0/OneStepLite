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
    private static final String TIP_OFFSET="onesteplite_tip_offset";
    private final int count;
    private final float unit,width,height,top;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline=new Path();
    private final Bitmap thumbnail;
    private final String caption;
    private final boolean textContent;
    private boolean dark;
    void setDark(boolean value){dark=value;}
    private boolean down;
    boolean pointsDown(){return down;}
    boolean pointDown(boolean value){if(down==value)return false;down=value;return true;}
    ContentDragShadow(View view,int count) {
        this(view,count,null);
    }
    ContentDragShadow(String text,float density) {
        this(text,density,1);
    }
    ContentDragShadow(String text,float density,int items) {
        this(text,density,items,true);
    }
    ContentDragShadow(String text,float density,int items,boolean words) {
        super();count=items;unit=density;width=(words?100:168)*unit;height=(words?110:58)*unit;top=12*unit;
        textContent=words;caption=text.replace('\n',' ');thumbnail=null;paint.setFilterBitmap(true);
    }
    ContentDragShadow(Bitmap image,float density) {
        this(image,density,1);
    }
    ContentDragShadow(Bitmap image,float density,int items) {
        super();count=items;unit=density;width=100*unit;height=110*unit;top=12*unit;
        textContent=false;caption="图片";thumbnail=image;paint.setFilterBitmap(true);
    }
    void recycleThumbnail(){if(thumbnail!=null && !thumbnail.isRecycled())thumbnail.recycle();}
    ContentDragShadow(View view,int count,CharSequence selected) {
        super(view);this.count=count;unit=view.getResources().getDisplayMetrics().density;
        dark=HyperOsStyle.dark(view.getContext());
        boolean image=view instanceof ImageView;
        textContent=selected!=null || view instanceof TextView;
        width=(image||textContent?100:168)*unit;height=(image||textContent?110:58)*unit;top=12*unit;
        CharSequence text=selected!=null?selected:view instanceof TextView?((TextView)view).getText():view.getContentDescription();
        caption=text==null?"文件":text.toString().replace("，长按拖曳分享","").replace('\n',' ');
        paint.setFilterBitmap(true);
        thumbnail=image && ((ImageView)view).getDrawable()!=null?snapshot(view):null;
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
        dimensions.set(Math.round(width),Math.round(height));touch.set(Math.round(width/2),Math.round(height-3*unit));
    }
    void mark(android.content.ClipData clip) {
        android.os.PersistableBundle extras=clip.getDescription().getExtras();
        extras=extras==null?new android.os.PersistableBundle():new android.os.PersistableBundle(extras);
        extras.putInt(TIP_OFFSET,Math.round(height-6*unit));
        extras.putInt("onesteplite_item_count",clip.getItemCount());
        extras.putInt("onesteplite_shadow_half_width",Math.round(width/2));
        extras.putInt("onesteplite_shadow_touch_y",Math.round(height-3*unit));clip.getDescription().setExtras(extras);
        if(getView()!=null)ContentDragMotion.stage(getView(),this);
    }
    static int offset(android.content.ClipDescription description) {
        android.os.PersistableBundle extras=description==null?null:description.getExtras();
        int value=extras==null?0:extras.getInt(TIP_OFFSET,0);return value>0 && value<=2048?value:0;
    }
    @Override public void onDrawShadow(Canvas canvas){
        paint.setStyle(Paint.Style.FILL);paint.setColor(dark?0xfa252e3a:0xfaf6faff);
        paint.setShadowLayer(4*unit,0,2*unit,0x55000000);
        RectF card=new RectF(4*unit,top,width-4*unit,height-(down?12:6)*unit);
        if(count>1){
            paint.clearShadowLayer();paint.setColor(dark?0xbb34465a:0xbbe0effa);
            canvas.drawRoundRect(card.left+3*unit,card.top+5*unit,card.right,card.bottom+3*unit,12*unit,12*unit,paint);
            paint.setColor(dark?0xfa252e3a:0xfaf6faff);paint.setShadowLayer(4*unit,0,2*unit,0x55000000);
        }
        float corner=12*unit;
        outline.reset();outline.moveTo(card.left+corner,card.top);
        if(!down){outline.lineTo(width/2-9*unit,top);outline.lineTo(width/2,3*unit);outline.lineTo(width/2+9*unit,top);}
        outline.lineTo(card.right-corner,card.top);outline.quadTo(card.right,card.top,card.right,card.top+corner);
        outline.lineTo(card.right,card.bottom-corner);outline.quadTo(card.right,card.bottom,card.right-corner,card.bottom);
        if(down){outline.lineTo(width/2+9*unit,card.bottom);outline.lineTo(width/2,height-3*unit);outline.lineTo(width/2-9*unit,card.bottom);}
        outline.lineTo(card.left+corner,card.bottom);outline.quadTo(card.left,card.bottom,card.left,card.bottom-corner);
        outline.lineTo(card.left,card.top+corner);outline.quadTo(card.left,card.top,card.left+corner,card.top);outline.close();
        canvas.drawPath(outline,paint);paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);paint.setColor(0xffa7d8f5);paint.setStrokeWidth(1.1f*unit);
        canvas.drawPath(outline,paint);paint.setStyle(Paint.Style.FILL);
        if(thumbnail!=null){
            int save=canvas.save();Path mask=new Path();mask.addRoundRect(
                    new RectF(8*unit,top+4*unit,width-8*unit,height-(down?16:10)*unit),9*unit,9*unit,Path.Direction.CW);
            int side=Math.min(thumbnail.getWidth(),thumbnail.getHeight());
            int left=(thumbnail.getWidth()-side)/2,y=(thumbnail.getHeight()-side)/2;
            canvas.clipPath(mask);canvas.drawBitmap(thumbnail,new android.graphics.Rect(left,y,left+side,y+side),
                    new RectF(8*unit,top+4*unit,width-8*unit,height-(down?16:10)*unit),paint);canvas.restoreToCount(save);
        }
        else drawSummary(canvas);
        if(count>1){
            paint.setColor(0xff347ff4);canvas.drawCircle(width-14*unit,top+10*unit,10*unit,paint);
            paint.setColor(0xffffffff);paint.setTextSize(11*unit);paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(Integer.toString(count),width-14*unit,top+14*unit,paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSummary(Canvas canvas) {
        paint.setColor(dark?0xb82e3b4c:0xb8edf3ff);canvas.drawRoundRect(8*unit,top+4*unit,width-8*unit,height-(down?16:10)*unit,9*unit,9*unit,paint);
        if(!textContent){
            paint.setColor(dark?0xffeef4fc:0xff283345);paint.setTextSize(12*unit);paint.setTextAlign(Paint.Align.LEFT);
            String name=android.text.TextUtils.ellipsize(caption,new android.text.TextPaint(paint),width-(count>1?52:28)*unit,
                    android.text.TextUtils.TruncateAt.END).toString();canvas.drawText(name,14*unit,top+24*unit,paint);return;
        }
        paint.setColor(dark?0xff9ac6ff:0xff347ff4);paint.setTextSize(11*unit);paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(textContent?"文字":"文件",14*unit,top+18*unit,paint);
        paint.setColor(dark?0xffeef4fc:0xff283345);paint.setTextSize(12*unit);
        int offset=0;
        for(int line=0;line<3 && offset<caption.length();line++) {
            int length=paint.breakText(caption,offset,caption.length(),true,width-28*unit,null);
            if(length==0)break;
            String text=caption.substring(offset,offset+length);offset+=length;
            if(line==2 && offset<caption.length())text=android.text.TextUtils.ellipsize(
                    caption.substring(offset-length),new android.text.TextPaint(paint),width-28*unit,android.text.TextUtils.TruncateAt.END).toString();
            canvas.drawText(text,14*unit,top+(36+line*16)*unit,paint);
        }
    }
}
