package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;

/** Shared dimensions and material for workspace content and shortcut dialogs. */
final class WorkspacePopupStyle {
    private WorkspacePopupStyle() {}

    static int width(Context context) {
        return Math.max(1,Math.min(dp(context,360),context.getResources().getDisplayMetrics().widthPixels-dp(context,48)));
    }

    static int height(Context context) {
        return Math.max(1,Math.min(dp(context,500),Math.round(context.getResources().getDisplayMetrics().heightPixels*.60f)));
    }

    static void size(Window window,Context context) {
        android.view.WindowManager.LayoutParams attrs=window.getAttributes();
        attrs.gravity=Gravity.CENTER;attrs.x=0;attrs.y=0;
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setAttributes(attrs);window.setLayout(width(context),height(context));
    }

    static void decorate(View view,Drawable wallpaper) {
        Context context=view.getContext();int inset=dp(context,14);
        view.setPadding(inset,inset,inset,inset);
        Drawable sheet=HyperOsStyle.glassSheet(context,28);sheet.setAlpha(140);
        view.setBackground(new LayerDrawable(new Drawable[]{wallpaper==null?HyperOsStyle.topBackground(context):wallpaper,
                HyperOsStyle.topWallpaperWash(context),sheet}));
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View target,Outline outline) {
                outline.setRoundRect(0,0,target.getWidth(),target.getHeight(),dp(context,28));
            }
        });
        view.setClipToOutline(true);
    }

    private static int dp(Context context,int value){return Math.round(HyperOsStyle.dp(context,value));}
}
