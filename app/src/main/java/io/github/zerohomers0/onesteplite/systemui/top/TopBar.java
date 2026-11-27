package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.List;

/** A clock, five fixed workspace actions, and an unmasked application rail. */
final class TopBar extends FrameLayout {
    interface Listener {
        void onOpenSettings();
        void onSetMainSide(boolean right);
        void onExit();
        void onLaunch(QuickApp app);
        /** Send the current main-window app up into the landscape task face. */
        default void onSendMainToTop() {}
        default void onTaskCellsCovered(boolean covered) {}
        default java.util.Set<String> openedAppPackages() { return java.util.Collections.emptySet(); }
        default void onContentShare(android.content.ComponentName target, Runnable launch) { launch.run(); }
        default Rect contentBounds() { return null; }
        default boolean canShareContent(){return false;}
        default boolean isActiveContentSource(String packageName){return false;}
        default boolean contentRailCovered(){return false;}
        default String contentHoverKey(String packageName){return null;}
        default void onContentHover(String key){}
    }
    private Listener listener;
    private final Header header;
    private Header tabletHeader;
    private final WorkspaceClock clock;
    private final QuickAppRail quickRail;
    private ShareWorkspace shareWorkspace;
    private List<QuickApp> availableApps;
    private Bitmap glassWallpaper;
    private int glassWidth, glassHeight, statusBarInset, tabletHeaderHeight;
    private boolean mainOnRight, tabletToolsExpanded;
    private android.animation.ValueAnimator tabletToolsAnimator;
    private final Rect tabletClip=new Rect();
    private boolean followTopBarSide=true;
    private RectF workspaceMain;
    private TopBarGeometry geometry;
    private boolean workspaceActive = true;
    private boolean dragShareEnabled=true;
    private boolean openedAppsRefreshQueued;
    private long openedAppsRefreshedAt;
    private final Runnable refreshOpenedApps = new Runnable() {
        @Override public void run() {
            openedAppsRefreshQueued=false;
            if (!workspaceActive || !isAttachedToWindow() || !isShown()
                    || getWindowVisibility() != VISIBLE) return;
            WorkspaceRuntimeCounters.TOP_APP_REFRESHES.incrementAndGet();
            java.util.Set<String> packages = listener.openedAppPackages();
            if (packages != null) quickRail.setOpenedPackages(packages);
            openedAppsRefreshedAt = SystemClock.uptimeMillis();
        }
    };

    void requestOpenedAppsRefresh() {
        if (!workspaceActive || !isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE) {
            if(openedAppsRefreshQueued)removeCallbacks(refreshOpenedApps);
            openedAppsRefreshQueued=false;
        } else if(!openedAppsRefreshQueued) {
            openedAppsRefreshQueued=true;
            postDelayed(refreshOpenedApps,
                    OpenedAppsRefreshPolicy.delay(SystemClock.uptimeMillis(), openedAppsRefreshedAt));
        }
    }
    void setWorkspaceActive(boolean active) {
        workspaceActive = active;
        if(shareWorkspace!=null)shareWorkspace.setActive(active && dragShareEnabled);
        requestOpenedAppsRefresh();
    }

    void bindListener(Listener owner) { listener = owner; }

    TopBar(Context context, WorkspaceLayout layout, List<QuickApp> apps, Listener listener) {
        super(context);
        this.listener = listener;
        mainOnRight = layout.mainOnRight;
        setBackground(null);
        setClipChildren(true);
        clock = new WorkspaceClock(context);
        addView(clock, new LayoutParams(-1, 1));
        header = new Header(context, null);
        header.setSendMainToTopEnabled(false);
        addView(header, new LayoutParams(-1, 1));
        availableApps = new java.util.ArrayList<>(apps);
        quickRail=new QuickAppRail(context,QuickAppPins.arrange(context,availableApps),
                app->this.listener.onLaunch(app));
        addView(quickRail, new LayoutParams(-1, 1));
        shareWorkspace = new ShareWorkspace(context, quickRail,
                () -> workspaceActive && dragShareEnabled && this.listener.canShareContent(),
                (target, launch) -> this.listener.onContentShare(target, launch),
                text -> {header.setHint(text);if(tabletHeader!=null)tabletHeader.setHint(text);},
                () -> this.listener.contentBounds());
        addView(shareWorkspace, new LayoutParams(-1, 1));
        shareWorkspace.setActiveViewSource(pkg->this.listener.isActiveContentSource(pkg));
        shareWorkspace.setMainOnRight(mainOnRight);quickRail.setMainOnRight(mainOnRight);
        shareWorkspace.setCoveredSource(()->!isShown() || this.listener.contentRailCovered());
        shareWorkspace.setWindowHover(pkg->this.listener.contentHoverKey(pkg),key->this.listener.onContentHover(key));
    }

    private int appliedTheme=-1;
    void applyOptions(WorkspaceOptions options) {
        clock.setShowTime(options.showTime);clock.setShowDate(options.showDate);clock.setShowLunar(options.showLunar);
        if(dragShareEnabled!=options.dragShareEnabled){
            dragShareEnabled=options.dragShareEnabled;
            shareWorkspace.setFeatureEnabled(dragShareEnabled);
            shareWorkspace.setActive(workspaceActive && dragShareEnabled);
            header.capture.setVisibility(dragShareEnabled?VISIBLE:INVISIBLE);
            if(tabletHeader!=null)tabletHeader.capture.setVisibility(dragShareEnabled?VISIBLE:INVISIBLE);
        }
        if(followTopBarSide!=options.followTopBarSide){
            followTopBarSide=options.followTopBarSide;
            setMainOnRight(mainOnRight);
        }
        if(appliedTheme==options.themeMode)return;
        appliedTheme=options.themeMode;
        header.updateColors();if(tabletHeader!=null)tabletHeader.updateColors();
        shareWorkspace.refreshAppearance();clock.invalidate();invalidate();
    }
    void setMainOnRight(boolean right) {
        mainOnRight = right;
        boolean mirrored=followTopBarSide && right;
        quickRail.setMainOnRight(mirrored);shareWorkspace.setMainOnRight(mirrored);
        header.updateActions();
        if (tabletHeader != null) tabletHeader.updateActions();
    }
    /** Grey the send-to-top action out while the main app cannot fill the strip. */
    void setSendMainToTopEnabled(boolean enabled) {
        requestOpenedAppsRefresh();
        header.setSendMainToTopEnabled(enabled);
        if (tabletHeader != null) tabletHeader.setSendMainToTopEnabled(enabled);
    }
    void setWorkspaceMainHole(RectF hole) {
        workspaceMain = hole == null ? null : new RectF(hole);
        if(workspaceMain==null)resetRevealAlpha();
        invalidate(); header.invalidate();
    }
    private void resetRevealAlpha(){
        if(clock!=null)clock.setAlpha(1f);
        header.resetRevealAlpha();
        if(tabletHeader!=null)tabletHeader.resetRevealAlpha();
    }
    private void applyAlpha(View child,float reveal){
        float target=Math.max(0f,Math.min(1f,reveal));
        if(Math.abs(child.getAlpha()-target)>.01f)child.setAlpha(target);
    }
    private float reveal(float l, float t, float r, float b) {
        if (workspaceMain == null) return 1f;
        return WorkspaceChromeReveal.alpha(l,t,r,b,workspaceMain.left,workspaceMain.top,
                workspaceMain.right,workspaceMain.bottom,12f*getResources().getDisplayMetrics().density);
    }
    @Override protected boolean drawChild(Canvas canvas, View child, long time) {
        if (child == header || child == quickRail || child == shareWorkspace || workspaceMain == null)
            return super.drawChild(canvas, child, time);
        float alpha = reveal(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
        applyAlpha(child,alpha);
        return alpha>0 && super.drawChild(canvas,child,time);
    }
    void setQuickApps(List<QuickApp> apps) {
        availableApps = new java.util.ArrayList<>(apps);
        quickRail.setApps(QuickAppPins.arrange(getContext(), availableApps));
    }
    void closeContentUi() { if (shareWorkspace != null) shareWorkspace.close(); }
    void trimDormantMemory() {
        closeContentUi();
        availableApps=java.util.Collections.emptyList();
        quickRail.setApps(java.util.Collections.emptyList());
        glassWallpaper=null;glassWidth=0;glassHeight=0;setBackground(null);
        if(clock!=null)clock.setWallpaper(null,0,0);
        invalidate();
    }
    boolean onWindowContentDrag(android.view.DragEvent event, String targetPackage) {
        return dragShareEnabled && shareWorkspace != null && shareWorkspace.onWindowDrag(event, targetPackage,
                !isShown() || listener.contentRailCovered());
    }
    boolean onPromotedContentDrop(android.view.DragEvent event,String targetPackage,boolean defer) {
        return dragShareEnabled && shareWorkspace != null
                && shareWorkspace.acceptPromotedDrop(event,targetPackage,defer);
    }
    void setStatusBarInset(int inset) { statusBarInset=Math.max(0,inset);requestLayout(); }
    void setWallpaperBackdrop(Bitmap bitmap,int width,int height) {
        glassWallpaper=bitmap;glassWidth=width;glassHeight=height;setBackground(null);
        clock.setWallpaper(bitmap,width,height);
        header.updateColors();
        if(tabletHeader!=null)tabletHeader.updateColors();
    }
    FrameLayout createTabletHeader(int height,Runnable tools) {
        clearTabletHeader();tabletHeaderHeight=height;header.setVisibility(GONE);
        tabletHeader=new Header(getContext(),tools);requestLayout();return tabletHeader;
    }
    void clearTabletHeader() {
        if(tabletToolsAnimator!=null){tabletToolsAnimator.cancel();tabletToolsAnimator=null;}
        tabletHeader=null;tabletHeaderHeight=0;tabletToolsExpanded=false;
        header.setVisibility(VISIBLE);setClipBounds(null);clock.setVisibility(VISIBLE);requestLayout();
    }
    void setTabletToolsExpanded(boolean expanded,boolean animate) {
        tabletToolsExpanded=expanded;listener.onTaskCellsCovered(expanded);
        if(tabletToolsAnimator!=null){tabletToolsAnimator.cancel();tabletToolsAnimator=null;}
        if(!animate || !android.animation.ValueAnimator.areAnimatorsEnabled()){
            setVisibility(expanded?VISIBLE:INVISIBLE);renderTabletTools();
        }else{
            setVisibility(VISIBLE);
            int top=Math.max(tabletHeaderHeight,getHeight());
            float from=expanded?top:tabletHeaderHeight,to=expanded?tabletHeaderHeight:top;
            tabletToolsAnimator=android.animation.ValueAnimator.ofFloat(from,to);
            tabletToolsAnimator.setDuration(180L);
            tabletToolsAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            tabletToolsAnimator.addUpdateListener(value->{
                tabletClip.set(0,Math.round((Float)value.getAnimatedValue()),getWidth(),getHeight());
                setClipBounds(tabletClip);
            });
            tabletToolsAnimator.addListener(new android.animation.AnimatorListenerAdapter(){
                @Override public void onAnimationEnd(android.animation.Animator animation){
                    if(tabletToolsAnimator==animation)tabletToolsAnimator=null;
                    if(!tabletToolsExpanded)setVisibility(INVISIBLE);else renderTabletTools();
                }
            });
            tabletToolsAnimator.start();
        }
        if(workspaceActive && dragShareEnabled && shareWorkspace!=null)shareWorkspace.setActive(true);
    }
    private void renderTabletTools() {
        if(tabletHeaderHeight>0){
            tabletClip.set(0,tabletToolsExpanded?tabletHeaderHeight:getHeight(),getWidth(),getHeight());
            setClipBounds(tabletClip);
        }
    }
    @Override protected void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        header.updateColors();if(tabletHeader!=null)tabletHeader.updateColors();
        requestLayout();invalidate();
        for(int i=0;i<getChildCount();i++)getChildAt(i).invalidate();
    }

    @Override protected void onSizeChanged(int w,int h,int ow,int oh) {
        super.onSizeChanged(w,h,ow,oh);renderTabletTools();
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();requestOpenedAppsRefresh();
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(refreshOpenedApps);openedAppsRefreshQueued=false;
        if(tabletToolsAnimator!=null){tabletToolsAnimator.cancel();tabletToolsAnimator=null;}
        super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View view,int visibility) {
        super.onVisibilityChanged(view,visibility);
        if (refreshOpenedApps != null) requestOpenedAppsRefresh();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (refreshOpenedApps != null) requestOpenedAppsRefresh();
    }
    @Override protected void onMeasure(int ws,int hs) {
        int width=MeasureSpec.getSize(ws),height=MeasureSpec.getSize(hs);
        geometry=new TopBarGeometry(width,height,statusBarInset,getResources().getDisplayMetrics().density,
                DeviceProfile.isTablet(getContext()),tabletHeaderHeight);
        LayoutParams c=(LayoutParams)clock.getLayoutParams();c.topMargin=geometry.blankTop;
        c.height=Math.max(1,geometry.blankBottom-geometry.blankTop);
        LayoutParams h=(LayoutParams)header.getLayoutParams();h.topMargin=geometry.headerTop;h.height=geometry.headerHeight;
        LayoutParams r=(LayoutParams)quickRail.getLayoutParams();r.topMargin=geometry.railTop;r.height=geometry.railHeight;
        r.leftMargin=0;r.rightMargin=0;
        LayoutParams share=(LayoutParams)shareWorkspace.getLayoutParams();
        share.topMargin=geometry.railTop;share.height=geometry.railHeight;
        super.onMeasure(ws,hs);
    }

    private final class Header extends FrameLayout {
        final TextView title;
        final IconButton left,right,topApp,settings,expand,capture;
        private boolean topAppAvailable=true;
        /** Scratch arrays: measure and layout run on every frame of a resize. */
        private final View[] buttons;
        private final IconButton[] icons;
        Header(Context context,Runnable tools) {
            super(context);setClipChildren(false);
            title=new TextView(context);title.setText("One Step");title.setGravity(android.view.Gravity.CENTER);
            title.setSingleLine(true);title.setIncludeFontPadding(false);
            title.setTextColor(HyperOsStyle.textPrimary(context));
            title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));title.setTextSize(19);
            title.setContentDescription("One Step");
            if(tools!=null)title.setOnClickListener(v->tools.run());
            addView(title);
            left=button(IconButton.SIDE_LEFT,"小窗放在左侧",()->listener.onSetMainSide(true));
            right=button(IconButton.SIDE_RIGHT,"小窗放在右侧",()->listener.onSetMainSide(false));
            topApp=button(IconButton.TOP_APP,"把主窗应用放到顶栏",()->{
                if(topAppAvailable)listener.onSendMainToTop();
                else android.widget.Toast.makeText(getContext(),"主窗应用为竖屏，暂时无法置顶",
                        android.widget.Toast.LENGTH_SHORT).show();
            });
            settings=button(IconButton.SETTINGS,"系统设置",()->listener.onOpenSettings());
            expand=button(IconButton.EXPAND_LEFT,"放大为全屏并退出工作区",()->listener.onExit());
            capture=button(IconButton.CAPTURE,"截取主窗图片",()->{if(shareWorkspace!=null)shareWorkspace.captureMain();});
            capture.setVisibility(dragShareEnabled?VISIBLE:INVISIBLE);
            buttons=new View[]{left,right,topApp,settings,expand,capture};
            icons=new IconButton[]{left,right,topApp,settings,expand,capture};
            updateActions();
        }
        /**
         * The header mirrors with the workspace. The expand action always sits on
         * the side the small windows occupy and points that way; the switch that
         * would move them to the side they already occupy is greyed out.
         */
        void setHint(String value) {
            title.setText(value==null?"One Step":value);
            title.setMarqueeRepeatLimit(-1);fitTitle(title.getWidth());
        }
        private void fitTitle(int available) {
            if(available<=0)return;
            float scaled=getResources().getDisplayMetrics().scaledDensity;
            float old=title.getPaint().getTextSize();title.getPaint().setTextSize(19*scaled);
            float measured=title.getPaint().measureText(title.getText().toString());title.getPaint().setTextSize(old);
            float size=Math.max(11*scaled,Math.min(19*scaled,19*scaled*available/Math.max(1,measured)));
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,size);
            boolean overflow=measured*size/(19*scaled)>available+1;
            title.setHorizontallyScrolling(overflow);
            title.setEllipsize(overflow?android.text.TextUtils.TruncateAt.MARQUEE:android.text.TextUtils.TruncateAt.END);
            title.setSelected(overflow);
        }
        void updateActions() {
            expand.setIcon(mainOnRight ? IconButton.EXPAND_LEFT : IconButton.EXPAND_RIGHT);
            left.setEnabled(!mainOnRight);
            left.setAlpha(mainOnRight ? .32f : 1f);
            right.setEnabled(mainOnRight);
            right.setAlpha(mainOnRight ? 1f : .32f);
            requestLayout();
        }
        void setSendMainToTopEnabled(boolean enabled) {
            if (topAppAvailable == enabled) return;
            topAppAvailable=enabled;
            topApp.setEnabled(enabled);
            topApp.setAlpha(enabled ? 1f : .32f);
            topApp.setFocusable(enabled);
            String description=enabled?"把主窗应用放到顶栏":"主窗应用为竖屏，暂时无法置顶";
            topApp.setContentDescription(description);topApp.setTooltipText(description);
        }
        void updateColors() {
            int fallback=HyperOsStyle.textPrimary(getContext());
            for(IconButton icon:icons) {
                icon.setCompactGlyph();
                icon.setIconColor(HyperOsStyle.dark(getContext())?0xffffffff:HyperOsStyle.wallpaperTextColor(
                        glassWallpaper,glassWidth,glassHeight,getLeft()+icon.getLeft(),getTop()+icon.getTop(),
                        icon.getWidth(),icon.getHeight(),fallback));
            }
            title.setTextColor(HyperOsStyle.dark(getContext())?0xffffffff:HyperOsStyle.wallpaperTextColor(glassWallpaper,glassWidth,glassHeight,
                    getLeft()+title.getLeft(),getTop()+title.getTop(),title.getWidth(),title.getHeight(),fallback));
        }
        private IconButton button(int icon,String text,Runnable action) {
            IconButton b=new IconButton(getContext(),icon,text);b.setTooltipText(text);
            b.setOnClickListener(v->action.run());addView(b);return b;
        }
        @Override protected boolean drawChild(Canvas canvas,View child,long time) {
            if(this==tabletHeader||workspaceMain==null)return super.drawChild(canvas,child,time);
            float a=reveal(getLeft()+child.getLeft(),getTop()+child.getTop(),
                    getLeft()+child.getRight(),getTop()+child.getBottom());
            applyAlpha(child,a);
            return a>0 && super.drawChild(canvas,child,time);
        }
        private float baseAlpha(View child){return child.isEnabled()?1f:.32f;}
        private void applyAlpha(View child,float reveal){
            float target=baseAlpha(child)*Math.max(0f,Math.min(1f,reveal));
            if(Math.abs(child.getAlpha()-target)>.01f)child.setAlpha(target);
        }
        private void resetRevealAlpha(){
            for(View child:buttons)child.setAlpha(baseAlpha(child));
            title.setAlpha(baseAlpha(title));
        }
        @Override protected void onMeasure(int ws,int hs) {
            int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);
            int edge=Math.round(10*getResources().getDisplayMetrics().density);
            float density=getResources().getDisplayMetrics().density;
            int preferred=w>=Math.round(380*density)?48:38;
            int bw=Math.min(Math.round(preferred*density),Math.min(h,Math.max(1,(w-2*edge)/8)));
            fitTitle(Math.max(1,w-2*edge-6*bw));
            title.measure(MeasureSpec.makeMeasureSpec(Math.max(1,w-2*edge-6*bw),MeasureSpec.EXACTLY),hs);
            for(View v:buttons)v.measure(MeasureSpec.makeMeasureSpec(bw,MeasureSpec.EXACTLY),hs);
            setMeasuredDimension(w,h);
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
            int w=r-l,h=b-t,edge=Math.round(10*getResources().getDisplayMetrics().density),bw=left.getMeasuredWidth();
            if(followTopBarSide && mainOnRight) {
                // Small windows on the left: expand and settings lead, the top-face
                // action and the two switches close the row on the right.
                expand.layout(edge,0,edge+bw,h);settings.layout(edge+bw,0,edge+2*bw,h);
                capture.layout(edge+2*bw,0,edge+3*bw,h);
                topApp.layout(w-edge-3*bw,0,w-edge-2*bw,h);
                left.layout(w-edge-2*bw,0,w-edge-bw,h);right.layout(w-edge-bw,0,w-edge,h);
                title.layout(edge+3*bw,0,w-edge-3*bw,h);
            } else {
                left.layout(edge,0,edge+bw,h);right.layout(edge+bw,0,edge+2*bw,h);
                topApp.layout(edge+2*bw,0,edge+3*bw,h);
                capture.layout(w-edge-3*bw,0,w-edge-2*bw,h);
                settings.layout(w-edge-2*bw,0,w-edge-bw,h);expand.layout(w-edge-bw,0,w-edge,h);
                title.layout(edge+3*bw,0,w-edge-3*bw,h);
            }
            updateColors();
            float size=Math.min(HyperOsStyle.dp(getContext(),26f),Math.min(bw,h)*.84f);
            // Match the visible 2.1-unit stroke edge, rather than the icon touch target.
            clock.setStartInset(edge+(bw-size)*.5f+size*1.95f/24f);
        }
    }
}
