package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Before/after workspace diagrams keep each app's colour across the two states. */
final class SwapModeDiagram extends View {
    private static final int APP_B=0xffc96521;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow=new Path();
    private final RectF frame=new RectF(),header=new RectF(),pane=new RectF();
    private final int mode,outline,empty,accent;
    private final boolean inserting;

    SwapModeDiagram(Context context,int mode,int outline,int empty,int accent) {
        this(context,mode,outline,empty,accent,false);
    }

    static SwapModeDiagram insertIntoEmptySlot(Context context,int outline,int empty,int accent) {
        return new SwapModeDiagram(context,WorkspaceOptions.FIXED_MAIN,outline,empty,accent,true);
    }

    private SwapModeDiagram(Context context,int mode,int outline,int empty,int accent,boolean inserting) {
        super(context);this.mode=mode;this.outline=outline;this.empty=empty;this.accent=accent;
        this.inserting=inserting;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale=Math.min(getWidth()/280f,getHeight()/206f);
        int save=canvas.save();
        canvas.translate((getWidth()-280*scale)/2,(getHeight()-206*scale)/2);
        canvas.scale(scale,scale);
        label(canvas,inserting?"拖入前":"交换前",61,16,12,outline,false);
        label(canvas,inserting?"拖入后":"交换后",219,16,12,outline,false);
        phone(canvas,20,false);
        phone(canvas,178,true);
        label(canvas,inserting?"拖入 B":"点击 B",140,94,12,outline,true);
        paint.setColor(outline);paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        arrow.reset();arrow.moveTo(115,108);arrow.lineTo(165,108);
        arrow.moveTo(159,102);arrow.lineTo(165,108);arrow.lineTo(159,114);
        canvas.drawPath(arrow,paint);
        label(canvas,inserting?"长按顶栏 B":"A 是主窗",61,201,11,outline,false);
        String result=inserting?"B 在小窗中打开":mode==WorkspaceOptions.GROW_IN_PLACE?"主窗换到右侧":"主窗仍在左侧";
        label(canvas,result,219,201,11,outline,true);
        canvas.restoreToCount(save);
    }

    private void phone(Canvas canvas,float x,boolean after) {
        frame.set(x,27,x+82,183);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.2f);paint.setColor(outline);
        canvas.drawRoundRect(frame,7,7,paint);
        header.set(x+4,31,x+78,61);
        block(canvas,header,empty);
        paint.setColor(outline);paint.setStrokeWidth(1.5f);paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(x+10,39,x+27,39,paint);
        canvas.drawLine(x+10,45,x+40,45,paint);
        for(int i=0;i<5;i++) {
            paint.setColor(inserting && i==2?APP_B:outline);
            canvas.drawCircle(x+11+i*12,54,inserting && i==2?4:2,paint);
        }
        boolean changedSides=after && mode==WorkspaceOptions.GROW_IN_PLACE;
        float mainLeft=changedSides?x+24:x+4,mainRight=changedSides?x+78:x+58;
        float sideLeft=changedSides?x+4:x+60,sideRight=changedSides?x+22:x+78;
        pane.set(mainLeft,64,mainRight,179);
        boolean swapped=after && !inserting;
        block(canvas,pane,swapped?APP_B:accent);
        label(canvas,swapped?"B":"A",pane.centerX(),116,19,0xffffffff,true);
        label(canvas,"主窗",pane.centerX(),133,10,0xffffffff,false);
        for(int i=0;i<3;i++) {
            float top=64+i*39;
            pane.set(sideLeft,top,sideRight,top+37);
            boolean filled=i==0 && (!inserting || after);
            block(canvas,pane,filled?(swapped?accent:APP_B):empty);
            if(filled)label(canvas,swapped?"A":"B",pane.centerX(),top+23,12,0xffffffff,true);
        }
        if(!after) {
            pane.set(sideLeft-2,62,sideRight+2,103);
            paint.setStyle(Paint.Style.STROKE);paint.setColor(APP_B);paint.setStrokeWidth(1.6f);
            canvas.drawRoundRect(pane,4,4,paint);
        }
    }

    private void label(Canvas canvas,String text,float x,float baseline,float size,int color,boolean bold) {
        paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
        canvas.drawText(text,x,baseline,paint);
    }

    private void block(Canvas canvas,RectF rect,int color) {
        paint.setStyle(Paint.Style.FILL);paint.setColor(color);
        canvas.drawRoundRect(rect,2,2,paint);
    }
}
