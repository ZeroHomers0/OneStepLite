package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Screen-edge arrows show the starting region and direction of each entry gesture. */
final class EntryGestureDiagram extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow=new Path();
    private final RectF rect=new RectF();
    private final int mode,outline,empty,accent;

    EntryGestureDiagram(Context context,int mode,int outline,int empty,int accent) {
        super(context);this.mode=mode;this.outline=outline;this.empty=empty;this.accent=accent;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale=Math.min(getWidth()/280f,getHeight()/170f);
        int save=canvas.save();
        canvas.translate((getWidth()-280*scale)/2,(getHeight()-170*scale)/2);
        canvas.scale(scale,scale);
        paint.setStyle(Paint.Style.STROKE);paint.setColor(outline);paint.setStrokeWidth(1.2f);
        rect.set(94,8,186,148);canvas.drawRoundRect(rect,7,7,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(empty);
        rect.set(99,13,181,143);canvas.drawRoundRect(rect,4,4,paint);
        paint.setColor(outline);paint.setStrokeWidth(1.5f);paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(104,22,120,22,paint);canvas.drawLine(165,22,175,22,paint);
        paint.setColor(accent);paint.setAlpha(35);
        boolean bottom=mode==WorkspaceOptions.BOTTOM_CENTER_OUTWARD;
        rect.set(99,bottom?126:13,181,bottom?143:31);
        canvas.drawRoundRect(rect,4,4,paint);paint.setAlpha(255);
        if(bottom) {
            swipe(canvas,136,135,106);swipe(canvas,144,135,174);
        } else {
            swipe(canvas,102,22,130);swipe(canvas,178,22,150);
        }
        paint.setStyle(Paint.Style.FILL);paint.setColor(outline);paint.setTextSize(11);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(bottom?"底部向左或向右滑":"状态栏两侧向中间滑",140,165,paint);
        canvas.restoreToCount(save);
    }

    private void swipe(Canvas canvas,float from,float y,float to) {
        paint.setColor(accent);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.5f);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawCircle(from,y,3.5f,paint);
        float sign=Math.signum(to-from);
        arrow.reset();arrow.moveTo(from+sign*5,y);arrow.lineTo(to,y);
        arrow.moveTo(to-sign*5,y-5);arrow.lineTo(to,y);arrow.lineTo(to-sign*5,y+5);
        canvas.drawPath(arrow,paint);
    }
}
