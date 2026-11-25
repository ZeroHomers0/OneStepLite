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
    private final LinearLayout toolbar;
    private final android.view.View thumb;
    private int current=-1;
    private final IntConsumer selectPage;
    private int thumbWidth,thumbHeight;
    private android.view.View detail;
    private android.view.View actionLayer;
    void push(android.view.View page){
        pop(false);
        for(int i=0;i<getChildCount();i++)getChildAt(i).setVisibility(GONE);
        detail=page;page.setBackground(null);page.setPadding(0,0,0,0);
        page.setClipToOutline(false);page.setOutlineProvider(null);addView(page,new LayoutParams(-1,0,1));
        if(android.animation.ValueAnimator.areAnimatorsEnabled()){
            page.setTranslationX(dp(36));page.setAlpha(.5f);page.animate().translationX(0).alpha(1).setDuration(240).start();
        }
    }
    void pop(boolean animate){
        if(detail==null)return;detail.animate().cancel();removeView(detail);detail=null;
        for(int i=0;i<getChildCount();i++)getChildAt(i).setVisibility(VISIBLE);
        if(animate && android.animation.ValueAnimator.areAnimatorsEnabled()){
            pages.animate().cancel();pages.setTranslationX(-dp(28));pages.setAlpha(.5f);
            pages.animate().translationX(0).alpha(1).setDuration(240).start();
        }
    }
    ContentPanelLayout(Context context,IntConsumer select) {
        super(context);selectPage=select;setOrientation(VERTICAL);WorkspacePopupStyle.decorate(this,null);
        toolbar=new LinearLayout(context);toolbar.setGravity(Gravity.CENTER_VERTICAL);
        addView(toolbar,new LayoutParams(-1,-2));
        track=new android.widget.FrameLayout(context);
        toolbar.addView(track,new LayoutParams(0,-2,1));
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
        this.track.addView(header,new android.widget.FrameLayout.LayoutParams(-1,-2));
        this.track.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->positionThumb(false));
        scroll=new ScrollView(context);scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0,dp(8),0,0);scroll.setClipToPadding(false);
        grid=new GridLayout(context);grid.setColumnCount(3);scroll.addView(grid);
        pages=new PageSwipeArea(context);pages.addView(scroll,new android.widget.FrameLayout.LayoutParams(-1,-1));
        empty=new TextView(context);empty.setTextSize(WorkspacePopupStyle.SUPPORT_SP);empty.setTextColor(HyperOsStyle.textSecondary(context));
        empty.setGravity(Gravity.CENTER);empty.setPadding(dp(16),dp(12),dp(16),dp(12));empty.setVisibility(GONE);
        pages.addView(empty,new android.widget.FrameLayout.LayoutParams(-1,-1));addView(pages,new LayoutParams(-1,0,1));
    }
    private final class PageSwipeArea extends android.widget.FrameLayout {
        private float downX,downY;
        private boolean decided,tracking;
        PageSwipeArea(Context context){super(context);}

        @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
            switch(event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX=event.getX();downY=event.getY();decided=tracking=false;
                    animate().cancel();setTranslationX(0);break;
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                    decided=true;break;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx=event.getX()-downX,dy=event.getY()-downY;
                    int slop=android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if(!decided && Math.max(Math.abs(dx),Math.abs(dy))>slop){
                        decided=true;tracking=Math.abs(dx)>Math.abs(dy)*1.2f;
                    }
                    break;
                default:break;
            }
            return tracking;
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            int action=event.getActionMasked();
            if(!tracking) {
                if(action==android.view.MotionEvent.ACTION_DOWN)return true;
                if(action!=android.view.MotionEvent.ACTION_MOVE || !onInterceptTouchEvent(event))return true;
            }
            float delta=event.getRawX()-downRawX;
            int direction=delta<0?1:-1;
            if(getLayoutDirection()==LAYOUT_DIRECTION_RTL)direction=-direction;
            int next=current+direction;
            boolean available=next>=0 && next<tabs.size();
            switch(event.getActionMasked()){
                case android.view.MotionEvent.ACTION_MOVE:
                    setTranslationX(Math.copySign(Math.min(Math.abs(delta)*(available?.45f:.16f),dp(96)),delta));
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    tracking=false;
                    boolean commit=available && Math.abs(delta)>=Math.min(dp(84),getWidth()*.25f);
                    if(commit){setTranslationX(0);selectPage.accept(next);}
                    else returnToPage();
                    return true;
                default:
                    tracking=false;returnToPage();return true;
            }
        }

        private void returnToPage(){
            animate().cancel();
            if(android.animation.ValueAnimator.areAnimatorsEnabled())animate().translationX(0).setDuration(140).start();
            else setTranslationX(0);
        }

        private float downRawX;
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)downRawX=event.getRawX();
            return super.dispatchTouchEvent(event);
        }
    }

    void setActions(ContentPanelActions actions) {
        LayoutParams size=new LayoutParams(dp(48),dp(44));
        size.setMarginStart(dp(2));toolbar.addView(actions,size);actions.bindMenu(this::setActionMenu);
    }

    boolean closeActionMenu(){
        if(detail instanceof WordSelectionPane words && words.closeActionMenu())return true;
        if(actionLayer==null)return false;
        pages.removeView(actionLayer);actionLayer=null;return true;
    }

    private void setActionMenu(android.view.View menu) {
        boolean wasOpen=closeActionMenu();
        if(menu==null || wasOpen)return;
        actionLayer=actionOverlay(menu,()->setActionMenu(null));
        pages.addView(actionLayer,new android.widget.FrameLayout.LayoutParams(-1,-1));
    }

    static android.widget.FrameLayout actionOverlay(android.view.View menu,Runnable dismiss) {
        Context context=menu.getContext();
        android.widget.FrameLayout overlay=new android.widget.FrameLayout(context);
        overlay.setOnClickListener(ignored->dismiss.run());
        android.widget.FrameLayout.LayoutParams position=new android.widget.FrameLayout.LayoutParams(
                HyperOsStyle.dp(context,160),-2,Gravity.TOP|Gravity.END);
        position.topMargin=HyperOsStyle.dp(context,4);
        overlay.addView(menu,position);
        return overlay;
    }

    void heading(String title,int width) {
        TextView label=new TextView(getContext());label.setText(title);
        label.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        label.setPaddingRelative(dp(6),0,0,0);
        label.setTextSize(WorkspacePopupStyle.SUPPORT_SP);label.setTypeface(null,Typeface.BOLD);
        label.setTextColor(HyperOsStyle.textSecondary(getContext()));
        GridLayout.LayoutParams row=new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(0,3));
        row.width=width;row.height=dp(32);grid.addView(label,row);
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
        if(current!=page)setActionMenu(null);
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
    @Override protected void onDetachedFromWindow(){thumb.animate().cancel();scroll.animate().cancel();pages.animate().cancel();if(detail!=null)detail.animate().cancel();super.onDetachedFromWindow();}
    private int dp(int value){return Math.round(HyperOsStyle.dp(getContext(),value));}
}
