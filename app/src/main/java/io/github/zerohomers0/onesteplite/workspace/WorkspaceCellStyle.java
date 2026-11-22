package io.github.zerohomers0.onesteplite;

import android.graphics.Canvas;
import android.graphics.Paint;

/** Shared by the real empty hints and their live-transition affordances. */
final class WorkspaceCellStyle {
    static final int WELL_COLOR=0x520E1822, WELL_ICON_COLOR=0xF2FFFFFF;
    static float wellRadius(int width, int height, float density) {
        return Math.min(Math.min(width, height) / 6f, 20f * density);
    }

    static void drawAdd(Canvas canvas, int width, int height, float density, Paint paint) {
        float x = width * .5f, y = height * .5f;
        float well = wellRadius(width, height, density);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(WELL_COLOR);
        canvas.drawCircle(x, y, well, paint);
        paint.setColor(WELL_ICON_COLOR);
        paint.setStrokeWidth(Math.max(1.5f, density * 1.65f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        float arm = well * .30f;
        canvas.drawLine(x - arm, y, x + arm, y, paint);
        canvas.drawLine(x, y - arm, x, y + arm, paint);
    }
}
