package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/** Toolbar icons keep their touch target without painting a button plate. */
final class IconButton extends View {
    static final int SETTINGS = 0, CLOSE = 10, SIDE_LEFT = 13, SIDE_RIGHT = 14,
            PLAY = 16, PAUSE = 17, VIDEO_RETURN = 18, TOP_APP = 19, EXPAND_LEFT = 20, EXPAND_RIGHT = 21,
            VIDEO_FIT = 22, VIDEO_FILL = 23;
    private int icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** One scratch path: a button paints exactly one glyph per frame. */
    private final Path glyph = new Path();
    private int foreground;

    void setIcon(int value) {icon=value;invalidate();}

    void setIconColor(int color) { foreground = color; invalidate(); }

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
        int save = canvas.save();
        float size = Math.min(HyperOsStyle.dp(getContext(), 26f), Math.min(getWidth(), getHeight()) * .84f);
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(foreground == 0 ? HyperOsStyle.textPrimary(getContext()) : foreground);
        paint.setStrokeWidth(2.1f);
        if (icon == VIDEO_FIT || icon == VIDEO_FILL) {
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
            canvas.drawLine(3, 4, 21, 4, paint);
            canvas.drawLine(21, 4, 21, 20, paint);
            canvas.drawLine(21, 20, 3, 20, paint);
            canvas.drawLine(3, 20, 3, 4, paint);
            float split = icon == SIDE_LEFT ? 9f : 15f;
            canvas.drawLine(split, 4, split, 20, paint);
            float start = icon == SIDE_LEFT ? 3f : 15f, end = icon == SIDE_LEFT ? 9f : 21f;
            canvas.drawLine(start, 9.3f, end, 9.3f, paint);
            canvas.drawLine(start, 14.7f, end, 14.7f, paint);
        } else if (icon == EXPAND_LEFT || icon == EXPAND_RIGHT) {
            // One arrow pointing at the corner the main window grows into, so the
            // header reads the same way as the workspace it sits on.
            boolean leftward = icon == EXPAND_LEFT;
            float tipX = leftward ? 7f : 17f, tipY = 7f;
            float tailX = leftward ? 19f : 5f, tailY = 19f;
            canvas.drawLine(tailX, tailY, tipX, tipY, paint);
            canvas.drawLine(tipX, tipY, tipX, tipY + 6f, paint);
            canvas.drawLine(tipX, tipY, tipX + (leftward ? 6f : -6f), tipY, paint);
        } else if (icon == TOP_APP) {
            // A strip at the top and an arrow rising into it: send the main app up.
            canvas.drawRoundRect(4, 3, 20, 7.5f, 1.2f, 1.2f, paint);
            canvas.drawLine(12, 20.5f, 12, 10.5f, paint);
            canvas.drawLine(8.5f, 14, 12, 10.5f, paint);
            canvas.drawLine(15.5f, 14, 12, 10.5f, paint);
        } else if (icon == CLOSE) {
            canvas.drawLine(6, 6, 18, 18, paint);
            canvas.drawLine(18, 6, 6, 18, paint);
        } else if (icon == SETTINGS) {
            Path gear = glyph; gear.reset();
            for (int i = 0; i < 32; i++) {
                double angle = Math.PI * 2 * i / 32 - Math.PI / 2;
                float radius = (i % 4 == 0 || i % 4 == 3) ? 10f : 8f;
                float x = 12 + (float) Math.cos(angle) * radius;
                float y = 12 + (float) Math.sin(angle) * radius;
                if (i == 0) gear.moveTo(x, y); else gear.lineTo(x, y);
            }
            gear.close();
            canvas.drawPath(gear, paint);
            canvas.drawCircle(12, 12, 3.5f, paint);
        }
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
