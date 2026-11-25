package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Before/after landscape placement uses the same visual grammar as window swapping. */
final class VideoModeDiagram extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow=new Path();
    private final RectF rect=new RectF();
    private final int mode,outline,empty,accent;
    VideoModeDiagram(Context context,int mode,int outline,int empty,int accent) {
        super(context);this.mode=mode;this.outline=outline;this.empty=empty;this.accent=accent;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);float scale=Math.min(getWidth()/280f,getHeight()/206f);
        int saved=canvas.save();canvas.translate((getWidth()-280*scale)/2,(getHeight()-206*scale)/2);canvas.scale(scale,scale);
        label(canvas,"横屏前",61,16,12,outline,false);label(canvas,"横屏后",219,16,12,outline,false);
        phone(canvas,20);
        if(mode==WorkspaceOptions.VIDEO_FULLSCREEN) {
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.2f);paint.setColor(outline);
            rect.set(168,73,270,137);canvas.drawRoundRect(rect,7,7,paint);
            block(canvas,172,77,266,133,accent);label(canvas,"A",219,108,19,0xffffffff,true);
        } else landscapeWorkspace(canvas);
        label(canvas,"进入横屏",140,94,11,outline,true);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(outline);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        arrow.reset();arrow.moveTo(115,108);arrow.lineTo(165,108);
        arrow.moveTo(159,102);arrow.lineTo(165,108);arrow.lineTo(159,114);canvas.drawPath(arrow,paint);
        label(canvas,"A 是主窗",61,201,11,outline,false);
        label(canvas,mode==WorkspaceOptions.VIDEO_FULLSCREEN?"恢复原生全屏":"小窗保持可用",219,201,11,outline,true);
        canvas.restoreToCount(saved);
    }
    private void phone(Canvas canvas,float x) {
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.2f);paint.setColor(outline);
        rect.set(x,27,x+82,183);canvas.drawRoundRect(rect,7,7,paint);
        block(canvas,x+4,31,x+78,61,empty);
        paint.setColor(outline);paint.setStrokeWidth(1.5f);paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(x+10,39,x+27,39,paint);canvas.drawLine(x+10,45,x+40,45,paint);
        for(int i=0;i<5;i++)canvas.drawCircle(x+11+i*12,54,2,paint);
        block(canvas,x+4,64,x+58,179,accent);
        label(canvas,"A",x+31,122,19,0xffffffff,true);
        for(int i=0;i<3;i++)block(canvas,x+60,64+i*39,x+78,101+i*39,empty);
    }
    private void landscapeWorkspace(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.2f);paint.setColor(outline);
        rect.set(168,73,270,137);canvas.drawRoundRect(rect,7,7,paint);
        block(canvas,172,77,266,87,empty);
        block(canvas,172,90,238,133,accent);
        label(canvas,"A",205,118,19,0xffffffff,true);
        for(int i=0;i<3;i++)block(canvas,241,90+i*15,266,103+i*15,empty);
    }

    private void block(Canvas canvas,float l,float t,float r,float b,int color) {
        rect.set(l,t,r,b);paint.setStyle(Paint.Style.FILL);paint.setColor(color);canvas.drawRoundRect(rect,2,2,paint);
    }
    private void label(Canvas canvas,String text,float x,float y,float size,int color,boolean bold) {
        paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTextSize(size);paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);canvas.drawText(text,x,y,paint);
    }
}
