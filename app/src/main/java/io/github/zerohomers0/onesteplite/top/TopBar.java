package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
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
        boolean canCustomizeApps();
        /** Send the current main-window app up into the landscape task face. */
        default void onSendMainToTop() {}
        default void onTaskCellsCovered(boolean covered) {}
        default java.util.Set<String> openedAppPackages() { return java.util.Collections.emptySet(); }
    }
    private Listener listener;
    private final Header header;
    private Header tabletHeader;
    private final WorkspaceClock clock;
    private final QuickAppRail quickRail;
    private final QuickAppPicker appPicker = new QuickAppPicker();
    private List<QuickApp> availableApps;
    private Bitmap glassWallpaper;
    private int glassWidth, glassHeight, statusBarInset, tabletHeaderHeight;
    private boolean mainOnRight, tabletToolsExpanded;
    private RectF workspaceMain;
    private TopBarGeometry geometry;
    private boolean workspaceActive = true;
    private final Runnable refreshOpenedApps = new Runnable() {
        @Override public void run() {
            if (!workspaceActive || !isAttachedToWindow() || !isShown()
                    || getWindowVisibility() != VISIBLE) return;
            java.util.Set<String> packages = listener.openedAppPackages();
            if (packages != null) quickRail.setOpenedPackages(packages);
            postDelayed(this, 30000L);
        }
    };

    void requestOpenedAppsRefresh() {
        removeCallbacks(refreshOpenedApps);
        if (workspaceActive && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE) postDelayed(refreshOpenedApps, 100L);
    }
    void setWorkspaceActive(boolean active) {
        workspaceActive = active;
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
        quickRail = new QuickAppRail(context, QuickAppPins.arrange(context, availableApps), new QuickAppRail.Listener() {
            public void onLaunch(QuickApp app) { TopBar.this.listener.onLaunch(app); }
            public void onCustomize() {
                if (!TopBar.this.listener.canCustomizeApps()) return;
                try {
                    appPicker.show(getContext(), availableApps, glassWallpaper, glassWidth, glassHeight,
                            () -> quickRail.setApps(QuickAppPins.arrange(getContext(), availableApps)));
                } catch (RuntimeException unavailable) {
                    appPicker.close();
                    android.widget.Toast.makeText(getContext(), "暂时无法打开应用选择框",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        addView(quickRail, new LayoutParams(-1, 1));
    }

    void applyOptions(WorkspaceOptions options) { clock.setShowLunar(options.showLunar); }
    void setMainOnRight(boolean right) {
        mainOnRight = right;
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
        quickRail.setWorkspaceMainHole(hole);
        invalidate(); header.invalidate();
    }
    private float reveal(float l, float t, float r, float b) {
        if (workspaceMain == null) return 1f;
        return WorkspaceChromeReveal.alpha(l,t,r,b,workspaceMain.left,workspaceMain.top,
                workspaceMain.right,workspaceMain.bottom,12f*getResources().getDisplayMetrics().density);
    }
    @Override protected boolean drawChild(Canvas canvas, View child, long time) {
        if (child == header || child == quickRail || workspaceMain == null)
            return super.drawChild(canvas, child, time);
        float alpha = reveal(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
        if (alpha <= 0) return false;
        int save=canvas.saveLayerAlpha(child.getLeft(),child.getTop(),child.getRight(),child.getBottom(),Math.round(255*alpha));
        boolean drawn=super.drawChild(canvas,child,time);canvas.restoreToCount(save);return drawn;
    }
    void setQuickApps(List<QuickApp> apps) {
        availableApps = new java.util.ArrayList<>(apps);
        quickRail.setApps(QuickAppPins.arrange(getContext(), availableApps));
    }
    void closeQuickAppPicker() { appPicker.close(); }
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
        tabletHeader=null;tabletHeaderHeight=0;tabletToolsExpanded=false;
        header.setVisibility(VISIBLE);setClipBounds(null);clock.setVisibility(VISIBLE);requestLayout();
    }
    void setTabletToolsExpanded(boolean expanded,boolean animate) {
        tabletToolsExpanded=expanded;listener.onTaskCellsCovered(expanded);
        setVisibility(expanded?VISIBLE:INVISIBLE);renderTabletTools();
    }
    private void renderTabletTools() {
        if(tabletHeaderHeight>0)setClipBounds(new Rect(0,tabletHeaderHeight,getWidth(),getHeight()));
    }
    @Override protected void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        header.updateColors();if(tabletHeader!=null)tabletHeader.updateColors();
        requestLayout();invalidate();
        for(int i=0;i<getChildCount();i++)getChildAt(i).invalidate();
    }

    @Override protected void onSizeChanged(int w,int h,int ow,int oh) {
        super.onSizeChanged(w,h,ow,oh);renderTabletTools();
        if(ow>0&&(w!=ow||h!=oh))appPicker.close();
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();requestOpenedAppsRefresh();
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(refreshOpenedApps);appPicker.close();super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View view,int visibility) {
        super.onVisibilityChanged(view,visibility);
        if (refreshOpenedApps != null) requestOpenedAppsRefresh();
        if(appPicker!=null&&!isShown())appPicker.close();
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
        r.leftMargin=r.rightMargin=0;
        super.onMeasure(ws,hs);
    }

    private final class Header extends FrameLayout {
        final TextView title;
        final IconButton left,right,topApp,settings,expand;
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
            topApp=button(IconButton.TOP_APP,"把主窗应用放到顶栏",()->listener.onSendMainToTop());
            settings=button(IconButton.SETTINGS,"系统设置",()->listener.onOpenSettings());
            expand=button(IconButton.EXPAND_LEFT,"放大为全屏并退出工作区",()->listener.onExit());
            buttons=new View[]{left,right,topApp,settings,expand};
            icons=new IconButton[]{left,right,topApp,settings,expand};
            updateActions();
        }
        /**
         * The header mirrors with the workspace. The expand action always sits on
         * the side the small windows occupy and points that way; the switch that
         * would move them to the side they already occupy is greyed out.
         */
        void updateActions() {
            expand.setIcon(mainOnRight ? IconButton.EXPAND_LEFT : IconButton.EXPAND_RIGHT);
            left.setEnabled(!mainOnRight);
            left.setAlpha(mainOnRight ? .32f : 1f);
            right.setEnabled(mainOnRight);
            right.setAlpha(mainOnRight ? 1f : .32f);
            requestLayout();
        }
        void setSendMainToTopEnabled(boolean enabled) {
            if (topApp.isEnabled() == enabled) return;
            topApp.setEnabled(enabled);
            topApp.setAlpha(enabled ? 1f : .32f);
        }
        void updateColors() {
            int fallback=HyperOsStyle.textPrimary(getContext());
            for(IconButton icon:icons)icon.setIconColor(HyperOsStyle.dark(getContext())?0xffffffff:HyperOsStyle.wallpaperTextColor(
                    glassWallpaper,glassWidth,glassHeight,getLeft()+icon.getLeft(),getTop()+icon.getTop(),
                    icon.getWidth(),icon.getHeight(),fallback));
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
            if(a<=0)return false;
            int s=canvas.saveLayerAlpha(child.getLeft(),child.getTop(),child.getRight(),child.getBottom(),Math.round(255*a));
            boolean drawn=super.drawChild(canvas,child,time);canvas.restoreToCount(s);return drawn;
        }
        @Override protected void onMeasure(int ws,int hs) {
            int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);
            int edge=Math.round(10*getResources().getDisplayMetrics().density);
            int bw=Math.min(h,Math.max(1,(w-2*edge)/7));
            title.measure(MeasureSpec.makeMeasureSpec(Math.max(1,w-2*edge-5*bw),MeasureSpec.EXACTLY),hs);
            for(View v:buttons)v.measure(MeasureSpec.makeMeasureSpec(bw,MeasureSpec.EXACTLY),hs);
            setMeasuredDimension(w,h);
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
            int w=r-l,h=b-t,edge=Math.round(10*getResources().getDisplayMetrics().density),bw=left.getMeasuredWidth();
            if(mainOnRight) {
                // Small windows on the left: expand and settings lead, the top-face
                // action and the two switches close the row on the right.
                expand.layout(edge,0,edge+bw,h);settings.layout(edge+bw,0,edge+2*bw,h);
                topApp.layout(w-edge-3*bw,0,w-edge-2*bw,h);
                left.layout(w-edge-2*bw,0,w-edge-bw,h);right.layout(w-edge-bw,0,w-edge,h);
                title.layout(edge+2*bw,0,w-edge-3*bw,h);
            } else {
                left.layout(edge,0,edge+bw,h);right.layout(edge+bw,0,edge+2*bw,h);
                topApp.layout(edge+2*bw,0,edge+3*bw,h);
                settings.layout(w-edge-2*bw,0,w-edge-bw,h);expand.layout(w-edge-bw,0,w-edge,h);
                title.layout(edge+3*bw,0,w-edge-2*bw,h);
            }
            updateColors();
            float size=Math.min(HyperOsStyle.dp(getContext(),26f),Math.min(bw,h)*.84f);
            // Match the visible 2.1-unit stroke edge, rather than the icon touch target.
            clock.setStartInset(edge+(bw-size)*.5f+size*1.95f/24f);
        }
    }
}
