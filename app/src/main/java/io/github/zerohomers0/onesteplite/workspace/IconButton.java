package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/** Toolbar icons keep their touch target without painting a button plate. */
final class IconButton extends View {
    static final int SETTINGS = 0, EXIT = 1, PREVIOUS = 2, NEXT = 3, CLIPBOARD = 4,
            TORCH = 5, SCREENSHOT = 6, RECORD = 7, CLEAR = 8, DELETE = 9, CLOSE = 10,
            TOOLS = 11, PORTRAIT_TASK = 12, SIDE_LEFT = 13, SIDE_RIGHT = 14, EXPAND = 15, PLAY = 16, PAUSE = 17, VIDEO_RETURN = 18,
            TOP_APP = 19;
    private int icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** One scratch path: a button paints exactly one glyph per frame. */
    private final Path glyph = new Path();
    private boolean exitLeft;
    private int foreground;

    void setIcon(int value) {icon=value;invalidate();}

    void setIconColor(int color) { foreground = color; invalidate(); }

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
        if (icon == PLAY) {
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
        } else if (icon == EXPAND) {
            canvas.drawLine(4, 10, 4, 4, paint); canvas.drawLine(4, 4, 10, 4, paint);
            canvas.drawLine(14, 4, 20, 4, paint); canvas.drawLine(20, 4, 20, 10, paint);
            canvas.drawLine(20, 14, 20, 20, paint); canvas.drawLine(20, 20, 14, 20, paint);
            canvas.drawLine(10, 20, 4, 20, paint); canvas.drawLine(4, 20, 4, 14, paint);
            canvas.drawLine(4, 4, 9, 9, paint); canvas.drawLine(20, 20, 15, 15, paint);
        } else if (icon == TOOLS) {
            for (int row = 0; row < 2; row++) for (int column = 0; column < 2; column++) {
                float x = 4f + column * 9f, y = 4f + row * 9f;
                canvas.drawRoundRect(x, y, x + 6f, y + 6f, 1f, 1f, paint);
            }
        } else if (icon == PORTRAIT_TASK) {
            canvas.drawRoundRect(6, 2.5f, 18, 21.5f, 1.5f, 1.5f, paint);
            canvas.drawLine(10, 18.5f, 14, 18.5f, paint);
        } else if (icon == CLIPBOARD) {
            // A folded page and a small clamp, all at the same light stroke weight.
            Path page = glyph; page.reset();
            page.moveTo(8, 4); page.lineTo(5.5f, 4); page.quadTo(4, 4, 4, 5.5f);
            page.lineTo(4, 20); page.quadTo(4, 21.5f, 5.5f, 21.5f);
            page.lineTo(18.5f, 21.5f); page.quadTo(20, 21.5f, 20, 20);
            page.lineTo(20, 5.5f); page.quadTo(20, 4, 18.5f, 4); page.lineTo(16, 4);
            canvas.drawPath(page, paint);
            canvas.drawRoundRect(8, 2.5f, 16, 6.5f, 1.4f, 1.4f, paint);
            canvas.drawLine(8, 11, 16, 11, paint);
            canvas.drawLine(8, 15.5f, 13.5f, 15.5f, paint);
        } else if (icon == CLEAR || icon == DELETE) {
            canvas.drawLine(5, 7, 19, 7, paint);
            canvas.drawLine(9, 4, 15, 4, paint);
            canvas.drawRoundRect(7, 8, 17, 20.5f, 1.5f, 1.5f, paint);
            canvas.drawLine(10, 11, 10, 17.5f, paint);
            canvas.drawLine(14, 11, 14, 17.5f, paint);
        } else if (icon == TOP_APP) {
            // A strip at the top and an arrow rising into it: send the main app up.
            canvas.drawRoundRect(4, 3, 20, 7.5f, 1.2f, 1.2f, paint);
            canvas.drawLine(12, 20.5f, 12, 10.5f, paint);
            canvas.drawLine(8.5f, 14, 12, 10.5f, paint);
            canvas.drawLine(15.5f, 14, 12, 10.5f, paint);
        } else if (icon == CLOSE) {
            canvas.drawLine(6, 6, 18, 18, paint);
            canvas.drawLine(18, 6, 6, 18, paint);
        } else if (icon == TORCH) {
            // Rounded flashlight head and grip; the short rays stay inside the icon box.
            Path body = glyph; body.reset();
            body.moveTo(6.5f, 7); body.lineTo(17.5f, 7);
            body.quadTo(18, 7, 17.5f, 8); body.lineTo(15, 11.5f);
            body.lineTo(15, 20); body.quadTo(15, 21.5f, 13.5f, 21.5f);
            body.lineTo(10.5f, 21.5f); body.quadTo(9, 21.5f, 9, 20);
            body.lineTo(9, 11.5f); body.lineTo(6.5f, 8);
            body.quadTo(6, 7, 6.5f, 7); body.close();
            canvas.drawPath(body, paint);
            canvas.drawLine(9, 11.5f, 15, 11.5f, paint);
            canvas.drawLine(12, 14.5f, 12, 17, paint);
            canvas.drawLine(7.5f, 3.5f, 6.5f, 2.5f, paint);
            canvas.drawLine(12, 3, 12, 1.5f, paint);
            canvas.drawLine(16.5f, 3.5f, 17.5f, 2.5f, paint);
        } else if (icon == SCREENSHOT) {
            // A clear capture frame, without a second tiny camera outline inside it.
            Path frame = glyph; frame.reset();
            frame.moveTo(8, 3.5f); frame.lineTo(5, 3.5f); frame.quadTo(3.5f, 3.5f, 3.5f, 5); frame.lineTo(3.5f, 8);
            frame.moveTo(16, 3.5f); frame.lineTo(19, 3.5f); frame.quadTo(20.5f, 3.5f, 20.5f, 5); frame.lineTo(20.5f, 8);
            frame.moveTo(3.5f, 16); frame.lineTo(3.5f, 19); frame.quadTo(3.5f, 20.5f, 5, 20.5f); frame.lineTo(8, 20.5f);
            frame.moveTo(20.5f, 16); frame.lineTo(20.5f, 19); frame.quadTo(20.5f, 20.5f, 19, 20.5f); frame.lineTo(16, 20.5f);
            canvas.drawPath(frame, paint);
            canvas.drawCircle(12, 12, 3.5f, paint);
        } else if (icon == RECORD) {
            // Same theme paint as its neighbours; every edge is an open stroke.
            canvas.drawLine(4, 6, 14, 6, paint);
            canvas.drawLine(4, 18, 14, 18, paint);
            canvas.drawLine(2.5f, 7.5f, 2.5f, 16.5f, paint);
            canvas.drawLine(15.5f, 7.5f, 15.5f, 16.5f, paint);
            Path corners = glyph; corners.reset();
            corners.moveTo(2.5f, 7.5f); corners.quadTo(2.5f, 6, 4, 6);
            corners.moveTo(14, 6); corners.quadTo(15.5f, 6, 15.5f, 7.5f);
            corners.moveTo(15.5f, 16.5f); corners.quadTo(15.5f, 18, 14, 18);
            corners.moveTo(4, 18); corners.quadTo(2.5f, 18, 2.5f, 16.5f);
            canvas.drawPath(corners, paint);
            canvas.drawLine(15.5f, 10, 21.5f, 6.5f, paint);
            canvas.drawLine(21.5f, 6.5f, 21.5f, 17.5f, paint);
            canvas.drawLine(21.5f, 17.5f, 15.5f, 14, paint);
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
        } else if (icon == EXIT) {
            if (exitLeft) canvas.scale(-1, 1, 12, 12);
            canvas.drawLine(5, 19, 19, 5, paint);
            canvas.drawLine(9, 5, 19, 5, paint);
            canvas.drawLine(19, 5, 19, 15, paint);
        } else {
            if (icon == NEXT) canvas.scale(-1, 1, 12, 12);
            Path chevron = glyph; chevron.reset();
            chevron.moveTo(15, 5);
            chevron.lineTo(8, 12);
            chevron.lineTo(15, 19);
            canvas.drawPath(chevron, paint);
        }
        canvas.restoreToCount(save);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return super.onTouchEvent(event);
        boolean handled = super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            animate().cancel();
            animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            animate().cancel();
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start();
        }
        return handled;
    }
}
