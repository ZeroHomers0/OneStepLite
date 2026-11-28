package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.InsetDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;

/** Toolbar icons keep their touch target without painting a button plate. */
final class IconButton extends View {
    static final int TOP_CONTROL_TOUCH_DP = 40;
    static final int TOP_CONTROL_MARGIN_DP = 12;
    static final int TOP_CONTROL_GRAVITY = Gravity.getAbsoluteGravity(Gravity.TOP | Gravity.START, LAYOUT_DIRECTION_LTR);
    private static final int TOP_CONTROL_PLATE_DP = 20;
    static final int SETTINGS = 0, CLOSE = 10, SIDE_LEFT = 13, SIDE_RIGHT = 14,
            PLAY = 16, PAUSE = 17, VIDEO_RETURN = 18, TOP_APP = 19, EXPAND_LEFT = 20, EXPAND_RIGHT = 21,
            VIDEO_FIT = 22, VIDEO_FILL = 23, CONTENT = 24, CAPTURE = 25, MORE = 26, CHECK = 27,
            MORE_VERTICAL = 28, BACK = 29, RETRY = 30;
    private int icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** One scratch path: a button paints exactly one glyph per frame. */
    private final Path glyph = new Path();
    private final Path tile=IconContour.create(38);
    private int foreground;
    private boolean compactGlyph;
    private boolean topControl;
    private final float contentIconSize;
    private final android.graphics.LinearGradient contentGradient = new android.graphics.LinearGradient(
            0,0,30,38,new int[]{0xff80ceff,0xff4b93f6,0xff3758d7},null,
            android.graphics.Shader.TileMode.CLAMP);

    void setCompactGlyph() {
        if(compactGlyph)return;
        compactGlyph=true;
        invalidate();
    }

    void setIcon(int value) {icon=value;invalidate();}

    void setIconColor(int color) { foreground = color; invalidate(); }

    static IconButton topControl(Context context, int icon, String description, Runnable action) {
        IconButton button = new IconButton(context, icon, description);
        button.topControl = true;
        button.setTooltipText(description);
        int inset = HyperOsStyle.dp(context, (TOP_CONTROL_TOUCH_DP - TOP_CONTROL_PLATE_DP) / 2f);
        button.setBackground(new InsetDrawable(
                HyperOsStyle.controlButton(context, false, TOP_CONTROL_PLATE_DP / 2f), inset));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if(!enabled) {animate().cancel();setScaleX(1f);setScaleY(1f);}
    }

    @Override protected void onDetachedFromWindow() {
        animate().cancel();setScaleX(1f);setScaleY(1f);
        super.onDetachedFromWindow();
    }

    IconButton(Context context, int icon, String description) {
        super(context);
        this.icon = icon;
        contentIconSize = IconContour.RAIL_ICON_DP * context.getResources().getDisplayMetrics().density;
        paint.setColor(HyperOsStyle.textPrimary(context));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setBackground(null);
        setClickable(true);
        setFocusable(true);
        setContentDescription(description);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if(icon==CONTENT) {drawContentIcon(canvas);return;}
        int save = canvas.save();
        float glyphDp = topControl ? 13f : compactGlyph ? 24f : 26f;
        float size = Math.min(HyperOsStyle.dp(getContext(), glyphDp), Math.min(getWidth(), getHeight()) * .84f);
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(foreground == 0 ? HyperOsStyle.textPrimary(getContext()) : foreground);
        paint.setStrokeWidth(2.1f);
        if (compactGlyph) paint.setShadowLayer(.7f,0,.6f,0x48000000);
        if (icon == MORE || icon == MORE_VERTICAL) {
            paint.setStyle(Paint.Style.FILL);
            if (icon == MORE_VERTICAL) canvas.rotate(90,12,12);
            canvas.drawCircle(6,12,1.65f,paint);canvas.drawCircle(12,12,1.65f,paint);
            canvas.drawCircle(18,12,1.65f,paint);
        } else if (icon == BACK) {
            if(getLayoutDirection()==LAYOUT_DIRECTION_RTL)canvas.scale(-1,1,12,12);
            canvas.drawLine(14,6,8,12,paint);
            canvas.drawLine(8,12,14,18,paint);
        } else if (icon == RETRY) {
            canvas.drawArc(5,5,19,19,25,300,false,paint);
            canvas.drawLine(18,4,18,8,paint);
            canvas.drawLine(18,8,14,8,paint);
        } else if (icon == CHECK) {
            canvas.drawLine(5,11,10,16,paint);canvas.drawLine(10,16,19,6,paint);
        } else if (icon == CAPTURE) {
            canvas.drawLine(3,9,3,3,paint);canvas.drawLine(3,3,9,3,paint);
            canvas.drawLine(15,3,21,3,paint);canvas.drawLine(21,3,21,9,paint);
            canvas.drawLine(21,15,21,21,paint);canvas.drawLine(21,21,15,21,paint);
            canvas.drawLine(9,21,3,21,paint);canvas.drawLine(3,21,3,15,paint);
        } else if (icon == VIDEO_FIT || icon == VIDEO_FILL) {
            canvas.drawRoundRect(3, 5, 21, 19, 2, 2, paint);
            if (icon == VIDEO_FIT) canvas.drawRect(7, 7, 17, 17, paint);
            else {canvas.drawLine(7, 12, 17, 12, paint);canvas.drawLine(7, 12, 10, 9, paint);canvas.drawLine(17, 12, 14, 15, paint);}
        } else if (icon == PLAY) {
            Path triangle=glyph;triangle.reset();triangle.moveTo(8,5);triangle.lineTo(19,12);triangle.lineTo(8,19);triangle.close();canvas.drawPath(triangle,paint);
        } else if (icon == PAUSE) {
            canvas.drawLine(8,5,8,19,paint);canvas.drawLine(16,5,16,19,paint);
        } else if (icon == VIDEO_RETURN) {
            canvas.drawLine(3,5,21,5,paint);canvas.drawLine(3,5,3,19,paint);canvas.drawLine(3,19,21,19,paint);canvas.drawLine(21,5,21,19,paint);
            canvas.drawLine(12,11,21,11,paint);canvas.drawLine(12,11,12,19,paint);
        } else if (icon == SIDE_LEFT || icon == SIDE_RIGHT) {
            // Open line segments avoid OEM stroked-round-rect paths becoming solid plates.
            canvas.drawLine(3, 3, 21, 3, paint);
            canvas.drawLine(21, 3, 21, 21, paint);
            canvas.drawLine(21, 21, 3, 21, paint);
            canvas.drawLine(3, 21, 3, 3, paint);
            float split = icon == SIDE_LEFT ? 9f : 15f;
            canvas.drawLine(split, 3, split, 21, paint);
            float start = icon == SIDE_LEFT ? 3f : 15f, end = icon == SIDE_LEFT ? 9f : 21f;
            canvas.drawLine(start, 9f, end, 9f, paint);
            canvas.drawLine(start, 15f, end, 15f, paint);
        } else if (icon == EXPAND_LEFT || icon == EXPAND_RIGHT) {
            // One arrow pointing at the corner the main window grows into, so the
            // header reads the same way as the workspace it sits on.
            boolean leftward = icon == EXPAND_LEFT;
            float tipX = leftward ? 3f : 21f, tipY = 3f;
            float tailX = leftward ? 21f : 3f, tailY = 21f;
            canvas.drawLine(tailX, tailY, tipX, tipY, paint);
            canvas.drawLine(tipX, tipY, tipX, tipY + 6f, paint);
            canvas.drawLine(tipX, tipY, tipX + (leftward ? 6f : -6f), tipY, paint);
        } else if (icon == TOP_APP) {
            // A strip at the top and an arrow rising into it: send the main app up.
            canvas.drawRoundRect(3, 3, 21, 7.5f, 1.2f, 1.2f, paint);
            canvas.drawLine(12, 21, 12, 10.5f, paint);
            canvas.drawLine(8.5f, 14, 12, 10.5f, paint);
            canvas.drawLine(15.5f, 14, 12, 10.5f, paint);
        } else if (icon == CLOSE) {
            canvas.drawLine(6, 6, 18, 18, paint);
            canvas.drawLine(18, 6, 6, 18, paint);
        } else if (icon == SETTINGS) {
            Path gear = glyph; gear.reset();
            for (int i = 0; i < 32; i++) {
                double angle = Math.PI * 2 * i / 32 - Math.PI / 2;
                float radius = (i % 4 == 0 || i % 4 == 3) ? 9f : 7.2f;
                float x = 12 + (float) Math.cos(angle) * radius;
                float y = 12 + (float) Math.sin(angle) * radius;
                if (i == 0) gear.moveTo(x, y); else gear.lineTo(x, y);
            }
            gear.close();
            canvas.drawPath(gear, paint);
            canvas.drawCircle(12, 12, 3.5f, paint);
        }
        paint.clearShadowLayer();
        canvas.restoreToCount(save);
    }

    private void drawContentIcon(Canvas canvas) {
        int save=canvas.save();
        float size = Math.min(IconContour.railIconEdge(contentIconSize, getHeight()), getWidth());
        canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);canvas.scale(size/38,size/38);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(contentGradient);
        canvas.drawPath(tile,paint);paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(0x58ffffff);
        int edge=canvas.save();
        canvas.clipPath(tile);
        canvas.drawPath(tile,paint);
        canvas.restoreToCount(edge);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x70ffffff);canvas.drawRoundRect(12,7,30,25,3,3,paint);
        paint.setColor(0xffffffff);canvas.drawRoundRect(7,12,26,31,3,3,paint);
        paint.setColor(0xffb1dcff);canvas.drawRoundRect(9.5f,14.5f,23.5f,28.5f,1.5f,1.5f,paint);
        paint.setColor(0xffffffff);canvas.drawCircle(19.5f,18,1.8f,paint);
        glyph.reset();glyph.moveTo(9.5f,26);glyph.lineTo(14.5f,20.5f);glyph.lineTo(19,25);
        glyph.lineTo(21,23);glyph.lineTo(23.5f,26);glyph.lineTo(23.5f,28.5f);
        glyph.lineTo(9.5f,28.5f);glyph.close();canvas.drawPath(glyph,paint);
        canvas.restoreToCount(save);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return super.onTouchEvent(event);
        boolean handled = super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            animate().cancel();
            animate().scaleX(0.96f).scaleY(0.96f).setDuration(OverlayMotionSpec.PRESS_MS).start();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            animate().cancel();
            animate().scaleX(1f).scaleY(1f).setDuration(OverlayMotionSpec.RELEASE_MS).start();
        }
        return handled;
    }
}
