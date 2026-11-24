package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Shared native panel shell; preview harness renders these same production widgets. */
final class ContentPanelLayout extends LinearLayout {
    final List<TextView> tabs=new ArrayList<>();
    final ScrollView scroll;
    final GridLayout grid;
    private final android.widget.FrameLayout pages;
    private final TextView empty;
    private final android.widget.FrameLayout track;
    private final android.view.View thumb;
    private int current=-1;
    private int thumbWidth,thumbHeight;
    ContentPanelLayout(Context context,IntConsumer select) {
        super(context);setOrientation(VERTICAL);WorkspacePopupStyle.decorate(this,null);
        track=new android.widget.FrameLayout(context);
        LinearLayout header=new LinearLayout(context);
        header.setPadding(dp(3),dp(3),dp(3),dp(3));
        GradientDrawable track=new GradientDrawable();track.setColor(HyperOsStyle.controlFill(context,false));
        track.setCornerRadius(dp(10));this.track.setBackground(track);
        thumb=new android.view.View(context);GradientDrawable face=new GradientDrawable();
        face.setColor(HyperOsStyle.page(context));face.setCornerRadius(dp(8));thumb.setBackground(face);
        this.track.addView(thumb,new android.widget.FrameLayout.LayoutParams(1,-1));
        String[] names={"近期图片","最近文件","剪贴板","暂存"};
        for(int i=0;i<names.length;i++) {
            final int index=i;TextView tab=new TextView(context);tab.setText(names[i]);tab.setTextSize(12);
            tab.setMinHeight(dp(36));tab.setMaxLines(1);tab.setIncludeFontPadding(false);
            tab.setGravity(Gravity.CENTER);tab.setTextColor(HyperOsStyle.textPrimary(context));
            tab.setOnClickListener(v->select.accept(index));tabs.add(tab);
            LayoutParams cell=new LayoutParams(0,-2,1);header.addView(tab,cell);
        }
        this.track.addView(header,new android.widget.FrameLayout.LayoutParams(-1,-2));addView(this.track);
        this.track.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->positionThumb(false));
        scroll=new ScrollView(context);scroll.setFillViewport(true);
        scroll.setPadding(0,dp(8),0,0);scroll.setClipToPadding(false);
        grid=new GridLayout(context);grid.setColumnCount(3);scroll.addView(grid);
        pages=new android.widget.FrameLayout(context);pages.addView(scroll,new android.widget.FrameLayout.LayoutParams(-1,-1));
        empty=new TextView(context);empty.setTextSize(12);empty.setTextColor(HyperOsStyle.textSecondary(context));
        empty.setGravity(Gravity.CENTER);empty.setPadding(dp(16),dp(12),dp(16),dp(12));empty.setVisibility(GONE);
        pages.addView(empty,new android.widget.FrameLayout.LayoutParams(-1,-1));addView(pages,new LayoutParams(-1,0,1));
    }
    void status(String text){empty.setText(text);empty.setVisibility(VISIBLE);scroll.setVisibility(INVISIBLE);}
    void clearStatus(){empty.setVisibility(GONE);scroll.setVisibility(VISIBLE);}
    private void positionThumb(boolean animate) {
        int width=Math.max(1,(track.getWidth()-dp(6))/tabs.size());
        int height=Math.max(1,track.getHeight()-dp(6));
        if(width==thumbWidth && height==thumbHeight && !animate)return;
        thumbWidth=width;thumbHeight=height;
        if(thumb.getLayoutParams().width!=width || thumb.getLayoutParams().height!=height){
            thumb.getLayoutParams().width=width;thumb.getLayoutParams().height=height;thumb.requestLayout();}
        thumb.setY(dp(3));float x=dp(3)+Math.max(0,current)*width;thumb.animate().cancel();
        if(animate && android.animation.ValueAnimator.areAnimatorsEnabled())thumb.animate().translationX(x).setDuration(360).setInterpolator(new android.view.animation.OvershootInterpolator(.65f)).start();
        else thumb.setTranslationX(x);
    }
    void select(int page) {
        int previous=current;current=page;positionThumb(previous>=0 && previous!=page);
        scroll.animate().cancel();
        scroll.setTranslationX(0);scroll.setAlpha(1);
        if(previous>=0 && previous!=page && android.animation.ValueAnimator.areAnimatorsEnabled()){scroll.setTranslationX(dp(28)*(page>previous?1:-1));scroll.setAlpha(.4f);
            scroll.animate().translationX(0).alpha(1).setDuration(240).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
        for(int i=0;i<tabs.size();i++) {
            tabs.get(i).setSelected(i==page);
            GradientDrawable face=new GradientDrawable();
            face.setColor(android.graphics.Color.TRANSPARENT);
            face.setCornerRadius(dp(8));
            tabs.get(i).setBackground(new RippleDrawable(ColorStateList.valueOf(
                    (HyperOsStyle.CONTROL_ACCENT&0x00ffffff)|0x1c000000),face,null));
            tabs.get(i).setTypeface(Typeface.DEFAULT,i==page?Typeface.BOLD:Typeface.NORMAL);
            tabs.get(i).setTextColor(i==page?HyperOsStyle.CONTROL_ACCENT:HyperOsStyle.textSecondary(getContext()));
        }
    }
    @Override protected void onDetachedFromWindow(){thumb.animate().cancel();scroll.animate().cancel();super.onDetachedFromWindow();}
    private int dp(int value){return Math.round(HyperOsStyle.dp(getContext(),value));}
}
