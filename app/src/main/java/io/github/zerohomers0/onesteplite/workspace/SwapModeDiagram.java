package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Native, theme-aware equivalents of the two swap-mode diagrams, drawn like the video ones. */
final class SwapModeDiagram extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow=new Path();
    /** Scratch rectangles; the diagram paints once per frame. */
    private final RectF frame=new RectF(),header=new RectF(),main=new RectF(),cell=new RectF();
    private final int mode,outline,empty,accent;
    SwapModeDiagram(Context context,int mode,int outline,int empty,int accent) {
        super(context);this.mode=mode;this.outline=outline;this.empty=empty;this.accent=accent;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale=Math.min(getWidth()/56f,getHeight()/80f);
        int save=canvas.save();canvas.translate((getWidth()-56*scale)/2,(getHeight()-80*scale)/2);
        canvas.scale(scale,scale);
        frame.set(11,3,45,77);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);paint.setColor(outline);
        canvas.drawRoundRect(frame,4,4,paint);paint.setStyle(Paint.Style.FILL);
        frame.inset(3,3);
        header.set(frame.left,frame.top,frame.right,frame.top+frame.height()*.16f);
        block(canvas,header,empty);
        // The main window keeps its side unless the app grows in place, in which case it follows the cells.
        boolean moved=mode==WorkspaceOptions.GROW_IN_PLACE;
        float side=frame.right-frame.width()*.24f, gap=1.5f;
        float mainLeft=moved?side+gap:frame.left, mainRight=moved?frame.right:side-gap;
        main.set(mainLeft,header.bottom+gap,mainRight,frame.bottom);
        block(canvas,main,accent);
        float cellLeft=moved?frame.left:side, cellRight=moved?side-gap:frame.right;
        float height=(main.height()-2*gap)/3f;
        for(int i=0;i<3;i++) {
            float top=main.top+i*(height+gap);
            cell.set(cellLeft,top,cellRight,top+height);block(canvas,cell,empty);
        }
        if(!moved)swapMark(canvas,main);
        canvas.restoreToCount(save);
    }
    /** The main window keeps its place, so mark the content exchange inside it with a two-way arrow. */
    private void swapMark(Canvas canvas,RectF rect) {
        float half=Math.min(rect.width(),rect.height())*.30f;
        float x=rect.centerX(),y=rect.centerY(),gap=half*.5f;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.4f);paint.setColor(0xffffffff);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawLine(x-half,y-gap,x+half,y-gap,paint);
        canvas.drawLine(x+half,y+gap,x-half,y+gap,paint);
        head(canvas,x+half,y-gap,1);
        head(canvas,x-half,y+gap,-1);
        paint.setStyle(Paint.Style.FILL);
    }
    private void head(Canvas canvas,float x,float y,int dx) {
        float size=2.4f;
        arrow.reset();
        arrow.moveTo(x-dx*size,y-size);arrow.lineTo(x,y);arrow.lineTo(x-dx*size,y+size);
        canvas.drawPath(arrow,paint);
    }
    private void block(Canvas canvas,RectF rect,int color) {
        paint.setColor(color);canvas.drawRoundRect(rect,1.5f,1.5f,paint);
    }
}
