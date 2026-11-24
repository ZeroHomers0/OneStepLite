package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.ImageView;

/** Original share icon with a small One Step-style action badge in its upper corner. */
final class ShareTargetButton extends ImageView {
    private final Paint badge=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow=new Path();
    private boolean dragHovered;
    private boolean mainRight;
    void setMainOnRight(boolean right){mainRight=right;invalidate();}
    ShareTargetButton(Context context,ShareTarget target) {
        super(context);setTag(target);setImageDrawable(target.icon);setScaleType(ScaleType.FIT_CENTER);
        setContentDescription(target.label+(target.enabled?"":"，不支持当前内容"));setTooltipText(target.label);
        setAlpha(target.enabled?1f:.3f);int inset=Math.round(HyperOsStyle.dp(context,6));
        setPadding(inset,inset,inset,inset);
    }

    void setDragHovered(boolean hovered) {
        if(dragHovered==hovered)return;
        dragHovered=hovered;animate().cancel();
        setSelected(hovered);
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()){setScaleX(hovered?1.1f:1);setScaleY(hovered?1.1f:1);return;}
        animate().scaleX(hovered?1.14f:1f).scaleY(hovered?1.14f:1f)
                .setDuration(hovered?120:100)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    @Override protected void onDetachedFromWindow() {
        animate().cancel();dragHovered=false;setSelected(false);setScaleX(1);setScaleY(1);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float unit=getResources().getDisplayMetrics().density,radius=6.5f*unit;
        float x=mainRight?getPaddingLeft()+2*unit:getWidth()-getPaddingRight()-2*unit;
        float y=getPaddingTop()+2*unit;
        badge.setStyle(Paint.Style.FILL);badge.setColor(HyperOsStyle.CONTROL_ACCENT);canvas.drawCircle(x,y,radius,badge);
        badge.setColor(android.graphics.Color.WHITE);badge.setStyle(Paint.Style.STROKE);
        badge.setStrokeWidth(1.3f*unit);badge.setStrokeCap(Paint.Cap.ROUND);badge.setStrokeJoin(Paint.Join.ROUND);
        float sign=mainRight?-1:1;
        arrow.reset();arrow.moveTo(x+sign*2.2f*unit,y-2.2f*unit);arrow.lineTo(x-sign*2.2f*unit,y+2.2f*unit);
        arrow.moveTo(x-sign*2.2f*unit,y-1.4f*unit);arrow.lineTo(x-sign*2.2f*unit,y+2.2f*unit);
        arrow.lineTo(x+sign*1.4f*unit,y+2.2f*unit);canvas.drawPath(arrow,badge);
    }
}
