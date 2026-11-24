package io.github.zerohomers0.onesteplite;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Built-in actions use the same original blue tile family as the content entry. */
final class ShareActionIcon extends Drawable {
    private final String operation;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path tile=IconContour.create(38);
    private int opacity=255;
    ShareActionIcon(String operation){this.operation=operation;}
    @Override public void draw(Canvas canvas) {
        int save=canvas.save();canvas.translate(getBounds().left,getBounds().top);
        canvas.scale(getBounds().width()/38f,getBounds().height()/38f);
        paint.setStyle(Paint.Style.FILL);paint.setAlpha(opacity);
        paint.setShader(new android.graphics.LinearGradient(0,0,38,38,new int[]{0xff62b9ff,0xff3869ec},null,android.graphics.Shader.TileMode.CLAMP));
        canvas.drawPath(tile,paint);paint.setShader(null);paint.setColor(0xffffffff);paint.setAlpha(opacity);
        paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);
        canvas.translate(7,7);
        if(ShareActions.STAGE.equals(operation)) {
            canvas.drawLine(3,14,3,21,paint);canvas.drawLine(3,21,21,21,paint);canvas.drawLine(21,14,21,21,paint);
            canvas.drawLine(12,3,12,15,paint);canvas.drawLine(8,11,12,15,paint);canvas.drawLine(12,15,16,11,paint);
        } else if(ShareActions.MAP.equals(operation)) {
            canvas.drawLine(3,7,3,21,paint);canvas.drawLine(3,21,9,18,paint);canvas.drawLine(9,18,15,21,paint);
            canvas.drawLine(15,21,21,18,paint);canvas.drawLine(21,18,21,7,paint);canvas.drawLine(9,12,9,18,paint);
            canvas.drawCircle(14,6,4,paint);canvas.drawLine(12,9,14,12,paint);canvas.drawLine(14,12,16,9,paint);
        } else if(ShareActions.TRANSLATE.equals(operation) || "recognize".equals(operation)) {
            paint.setStyle(Paint.Style.FILL);paint.setTextSize(20);paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("recognize".equals(operation)?"文":"译",12,20,paint);
        } else if(ShareActions.EDIT.equals(operation)) {
            frame(canvas,2,3,19,20);canvas.drawCircle(7,8,1.5f,paint);canvas.drawLine(4,17,10,11,paint);
            canvas.drawLine(10,11,14,15,paint);canvas.drawLine(14,20,22,12,paint);canvas.drawLine(17,21,23,15,paint);
        } else {
            if(ShareActions.IMAGE_SEARCH.equals(operation)){frame(canvas,2,2,19,19);canvas.drawCircle(7,7,1.5f,paint);}
            canvas.drawCircle(11,11,6,paint);canvas.drawLine(16,16,22,22,paint);
        }
        canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int alpha){opacity=alpha;invalidateSelf();}
    private void frame(Canvas canvas,float left,float top,float right,float bottom){
        canvas.drawLine(left,top,right,top,paint);canvas.drawLine(right,top,right,bottom,paint);
        canvas.drawLine(right,bottom,left,bottom,paint);canvas.drawLine(left,bottom,left,top,paint);
    }
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    @Override public int getIntrinsicWidth(){return 38;}
    @Override public int getIntrinsicHeight(){return 38;}
    @Override public ConstantState getConstantState(){
        String key=operation;return new ConstantState(){
            @Override public Drawable newDrawable(){return new ShareActionIcon(key);}
            @Override public int getChangingConfigurations(){return 0;}
        };
    }
}
