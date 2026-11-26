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
    static final int TITLE_SP=16, BODY_SP=14, SUPPORT_SP=12, META_SP=11;
    private static final int ACTION_HEIGHT_DP=44;
    private static final int ACTION_RADIUS_DP=10;
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
        glassWindow(window,context);
    }

    static void glassWindow(Window window,Context context) {
        android.graphics.drawable.GradientDrawable mask=new android.graphics.drawable.GradientDrawable();
        mask.setCornerRadius(dp(context,28));mask.setColor(HyperOsStyle.page(context));window.setBackgroundDrawable(mask);
        android.view.WindowManager manager=context.getSystemService(android.view.WindowManager.class);
        boolean capable=WindowGlassCompat.enable(window);
        java.util.function.Consumer<Boolean> update=enabled->mask.setColor(enabled && capable?(HyperOsStyle.dark(context)?0x3010151c:0x30e7edf5):HyperOsStyle.page(context));
        View decor=window.getDecorView();
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            private boolean registered;
            private void register(){if(!registered)try{manager.addCrossWindowBlurEnabledListener(update);registered=true;}
                catch(RuntimeException unavailable){mask.setColor(HyperOsStyle.page(context));}}
            @Override public void onViewAttachedToWindow(View view){register();}
            @Override public void onViewDetachedFromWindow(View view){
                if(registered)manager.removeCrossWindowBlurEnabledListener(update);
                registered=false;view.removeOnAttachStateChangeListener(this);
            }
            {if(decor.isAttachedToWindow())register();}
        });
        window.setBackgroundBlurRadius(capable?Math.min(100,dp(context,28)):0);
    }

    static void secondary(Window window,Context context) {
        size(window,context);
        window.setLayout(Math.max(1,width(context)-dp(context,24)),Math.max(1,height(context)-dp(context,40)));
    }

    static void dragWindow(Window window,int blur,boolean visible) {
        if(visible)window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        else window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        window.getDecorView().setAlpha(visible?1:0);
        android.view.WindowManager.LayoutParams attrs=window.getAttributes();attrs.alpha=visible?1:0;
        window.setAttributes(attrs);window.setBackgroundBlurRadius(visible?blur:0);
    }

    static android.widget.TextView action(Context context,String text,boolean primary,Runnable action) {
        android.widget.TextView button=new android.widget.TextView(context);
        button.setText(text);styleAction(button,primary);
        button.setOnClickListener(v->action.run());return button;
    }
    static void styleAction(android.widget.TextView button,boolean primary) {
        Context context=button.getContext();button.setTextSize(BODY_SP);button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);button.setIncludeFontPadding(false);button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setTextColor(primary?android.graphics.Color.WHITE:HyperOsStyle.textPrimary(context));
        button.setBackground(HyperOsStyle.controlButton(context,primary,ACTION_RADIUS_DP));
        button.setMinHeight(dp(context,ACTION_HEIGHT_DP));button.setPadding(dp(context,8),0,dp(context,8),0);
    }

    static void styleSearch(android.widget.EditText search) {
        Context context=search.getContext();
        search.setTextSize(BODY_SP);search.setSingleLine();
        search.setTextColor(HyperOsStyle.textPrimary(context));
        search.setHintTextColor(HyperOsStyle.textSecondary(context));
    }

    static android.widget.LinearLayout.LayoutParams actionCell(Context context) {
        android.widget.LinearLayout.LayoutParams cell=new android.widget.LinearLayout.LayoutParams(0,dp(context,ACTION_HEIGHT_DP),1);
        int gap=dp(context,3);cell.setMargins(gap,gap,gap,gap);return cell;
    }

    static IconButton backButton(Context context,int tint,Runnable back) {
        IconButton button=new IconButton(context,IconButton.BACK,"返回");
        button.setIconColor(tint);
        button.setOnClickListener(view->back.run());
        return button;
    }

    static void heading(android.widget.LinearLayout body,String title,Runnable back) {
        heading(body,title,back,null);
    }

    static void heading(android.widget.LinearLayout body,String title,Runnable back,View trailingAction) {
        heading(body,title,back,null,trailingAction);
    }

    static void heading(android.widget.LinearLayout body,String title,Runnable back,
            android.widget.TextView subtitle,View trailingAction) {
        Context context=body.getContext();android.widget.LinearLayout row=new android.widget.LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int height=dp(context,ACTION_HEIGHT_DP);
        int tint=HyperOsStyle.textPrimary(context);
        row.addView(backButton(context,tint,back),new android.widget.LinearLayout.LayoutParams(height,height));
        android.widget.TextView label=new android.widget.TextView(context);label.setText(title);label.setTextSize(TITLE_SP);
        label.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);label.setTextColor(tint);
        label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setAccessibilityHeading(true);
        label.setPaddingRelative(dp(context,4),0,dp(context,8),0);
        if(subtitle==null)row.addView(label,new android.widget.LinearLayout.LayoutParams(0,-2,1));
        else {
            row.addView(label,new android.widget.LinearLayout.LayoutParams(-2,-2));
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(subtitle,new android.widget.LinearLayout.LayoutParams(0,-2,1));
        }
        if(trailingAction!=null){
            int actionWidth=trailingAction instanceof IconButton?height:-2;
            android.widget.LinearLayout.LayoutParams actionBounds=new android.widget.LinearLayout.LayoutParams(actionWidth,height);
            actionBounds.setMarginEnd(dp(context,8));
            row.addView(trailingAction,actionBounds);
        }
        android.widget.LinearLayout.LayoutParams bounds=new android.widget.LinearLayout.LayoutParams(-1,height);
        bounds.bottomMargin=dp(context,8);body.addView(row,0,bounds);
    }

    static void decorate(View view,Drawable wallpaper) {
        Context context=view.getContext();int inset=dp(context,14);
        view.setPadding(inset,inset,inset,inset);
        Drawable sheet=HyperOsStyle.glassSheet(context,28);sheet.setAlpha(225);
        view.setBackground(wallpaper==null?sheet:new LayerDrawable(new Drawable[]{wallpaper,sheet}));
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View target,Outline outline) {
                outline.setRoundRect(0,0,target.getWidth(),target.getHeight(),dp(context,28));
            }
        });
        view.setClipToOutline(true);
    }

    private static int dp(Context context,int value){return Math.round(HyperOsStyle.dp(context,value));}
}
