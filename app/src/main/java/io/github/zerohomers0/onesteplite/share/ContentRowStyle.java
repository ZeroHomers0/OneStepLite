package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.view.View;

/** Equal-height content rows retain a visible gap and a quiet surface in either theme. */
final class ContentRowStyle {
    private ContentRowStyle(){}
    static int height(Context context) {
        return Math.max(HyperOsStyle.dp(context,68),Math.round(44*context.getResources().getDisplayMetrics().scaledDensity)+HyperOsStyle.dp(context,24));
    }
    static void apply(View view) {
        Context context=view.getContext();view.setMinimumHeight(height(context));
        android.graphics.drawable.GradientDrawable fill=new android.graphics.drawable.GradientDrawable();
        fill.setColor(HyperOsStyle.controlFill(context,false));fill.setAlpha(110);
        fill.setCornerRadius(HyperOsStyle.dp(context,10));
        view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x183482ff),fill,null));
    }
}
