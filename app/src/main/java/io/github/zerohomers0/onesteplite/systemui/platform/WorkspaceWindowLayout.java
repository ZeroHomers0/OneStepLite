package io.github.zerohomers0.onesteplite;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Method;
import android.view.WindowManager;
import android.widget.FrameLayout;
import de.robv.android.xposed.XposedHelpers;

/** Creates the host window and lays out its workspace layers. */
final class WorkspaceWindowLayout {
    private final WorkspaceHost host;

    WorkspaceWindowLayout(WorkspaceHost host) {
        this.host = host;
    }

    TopTaskGeometry topGeometry() {
        if (host.layout.tabletLandscape) return new TopTaskGeometry(host.layout.interactiveBounds(),
                host.ctx.getResources().getDisplayMetrics().density);
        return new TopTaskGeometry(host.screenW,host.screenH,host.layout.topStrip,!DeviceProfile.isTablet(host.ctx),
                host.ctx.getResources().getDisplayMetrics().density);
    }

    void buildLayout() {
        host.workspaceSettings.refresh();
        host.displays.readDisplayGeometry();
        resolveMainSide();
        int w = host.screenW;
        int h = host.screenH;
        WorkspaceHost.log("device: " + DeviceProfile.describe(host.ctx));
        float layoutDensity = host.ctx.getResources().getDisplayMetrics().density;
        host.layout = WorkspaceLayout.of(w, h, host.mainOnRight, layoutDensity, DeviceProfile.isTablet(host.ctx));
        host.CELL_SLOT_COUNT = 1 + host.layout.cellCount;
        host.mainOnRight = host.layout.mainOnRight;
        WorkspaceHost.log("layout: " + host.layout.describe()
                + " mainScale=" + host.scales.mainScale() + " cellScale=" + host.scales.cellScale());
        createRootView();
        watchSideViewport();
        createHostWindow(w, h);
        createTopBar();
        host.root.post(host.chrome::updateTopBarState);
        host.wallpapers.loadWallpaperBackdrop();
        host.windowPresentation.beginWorkspaceEntry(null);
        // Keep corner activation disabled while active; HyperOS GestureStub owns back gestures.
        HotZoneGesture.raiseAll();
        host.zoomSession.startWorkspaceHeartbeat();
    }

    private void resolveMainSide() {
    android.content.SharedPreferences position=host.ctx.createDeviceProtectedStorageContext().getSharedPreferences("workspace_position",0);
    if(position.contains("main_right"))host.mainOnRight=position.getBoolean("main_right",false);
    int pendingSide = WorkspaceHost.pendingMainSide;
    WorkspaceHost.pendingMainSide=-1;
    if (pendingSide >= 0) {
        WorkspaceHost.pendingMainSide = -1;
        host.mainOnRight = pendingSide == 1;
        WorkspaceHost.log("layout mainOnRight from gesture edge=" + host.mainOnRight);
    }
    }

    private void createRootView() {
    host.root = new FrameLayout(host.ctx) {
        private final android.graphics.Paint divider = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
            // A stationary substrate covers both edges while native main geometry
            // moves. Cell content can leave without taking its wallpaper with it.
            if (host.mainSideAnimator != null) {
                int backdropSave = canvas.save();
                canvas.clipOutRect(host.windowPresentation.workspaceMainHole());
                if (host.mainSideBackdrop != null) {
                    host.mainSideBackdrop.setBounds(0, 0, host.screenW, host.screenH);
                    host.mainSideBackdrop.draw(canvas);
                } else canvas.drawColor(HyperOsStyle.page(host.ctx));
                canvas.restoreToCount(backdropSave);
            }
            super.dispatchDraw(canvas);
            if (host.layout == null || host.workspaceAlpha < 1f || host.workspaceAnimator != null || host.mainSideAnimator != null) return;
            int saved = canvas.save();
            android.graphics.RectF main = host.windowPresentation.workspaceMainHole();
            canvas.clipOutRect(main);
            WorkspaceDividers.draw(canvas, host.layout, HyperOsStyle.dp(host.ctx, 1f), host.workspaceAlpha, divider);
            canvas.restoreToCount(saved);

        }
        @Override protected void onConfigurationChanged(android.content.res.Configuration configuration) {
            super.onConfigurationChanged(configuration);
            post(()->{if(host.root==this)host.wallpapers.applyWallpaperBackdrops();});
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (host.transitionWindow.consume(event.getActionMasked())) return true;
            if (host.mainSideAnimator != null || host.cells.motion() != null || host.sideRows.isCompacting()) return true;
            boolean wasCaptured = host.sideRows.isCaptured();
            if (host.sideRows.trackTouch(event)) {
                if (!wasCaptured && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    try { super.dispatchTouchEvent(cancel); } finally { cancel.recycle(); }
                }
                return true;
            }
            return super.dispatchTouchEvent(event) || host.sideRows.isArmed();
        }
    };
    host.transitionWindow.attach();
    host.root.setClipChildren(false);
    host.root.setBackgroundColor(0x00000000);
    }

    private void watchSideViewport() {
    host.root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { host.sideRows.onRootAttached(view); }
        @Override public void onViewDetachedFromWindow(View view) { }
    });
    host.chrome.installHostTouchRegion();
    }

    private void createHostWindow(int w, int h) {
    host.wm = (WindowManager) host.ctx.getSystemService(Context.WINDOW_SERVICE);
    host.lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
    host.lp.gravity = Gravity.TOP | Gravity.START;
    host.lp.setTitle("WorkspaceHost");
    HostWindowPolicy.configure(host.lp);
    // APPLICATION_OVERLAY 默认被状态栏/cutout 顶下去（真机 122px）。两者都关掉后
    // 窗口坐标系才等于屏幕坐标系（[0,0]-[1080,2460]）。
    try {
        host.lp.setFitInsetsTypes(0);
        WorkspaceHost.log("fitInsetsTypes=0 applied");
    } catch (Throwable t) {
        WorkspaceHost.log("setFitInsetsTypes failed: " + t);
    }
    try {
        host.lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        WorkspaceHost.log("cutoutMode=ALWAYS applied");
    } catch (Throwable t) {
        WorkspaceHost.log("cutout mode failed: " + t);
    }
    host.wm.addView(host.root, host.lp);
    registerDefaultDisplayListener();
    host.carriers.createMirrorLayer();
    WorkspaceHost.log("host 2.9.41 HyperOS glass added " + w + "x" + h
            + " privateFlags=0x" + Integer.toHexString(
                    XposedHelpers.getIntField(host.lp, "privateFlags")));
    // v1.3.3：呼出OneStepLite = 整块屏缩进主区（写缩放触发文件，system_server 轮询执行）。
    host.main.postDelayed(host.chrome::collapseStatusBarPanels, 220L);
    }

    private void createTopBar() {
    // Host-owned toolbar and app strip, matching the OneStepLite 3.0 interaction surface.
    try {
        java.util.List<QuickApp> quickApps;
        try {
            quickApps = Warmup.quickApps();
        } catch (Throwable error) {
            quickApps = java.util.Collections.emptyList();
            WorkspaceHost.log("top app strip unavailable: " + error);
        }
        WorkspaceChrome chrome=new WorkspaceChrome(host.ctx,host.root,host.layout,quickApps,new TopBar.Listener() {
            @Override public void onOpenSettings() { host.mainLaunch.openSettingsInMain(); }
            @Override public void onSetMainSide(boolean right) {
                if (host.scene != WorkspaceHost.ACTIVE || host.workspaceEntering || host.promotion != null || host.transferBusy
                        || host.cells.motion() != null || host.sideRows.motionRunning() || host.liveGeometry.holdsLiveFlipZoom()) return;
                host.mainSide.switchMainSideAnimated(right);
            }
            @Override public void onExit() {
                WorkspaceHost.log("top bar exit tapped");
                host.resumes.toggleVisibility();
            }
            @Override public void onLaunch(QuickApp entry) { host.mainLaunch.launchQuick(entry); }
            @Override public void onContentShare(ComponentName target, Runnable launch) {
                host.mainLaunch.shareContentInMain(target, launch);
            }
            @Override public String contentHoverKey(String pkg){return host.contentDrag.contentHoverKeyFor(pkg);}
            @Override public void onContentHover(String key){host.contentDrag.promoteContentHover(key);}
            @Override public Rect contentBounds() {
                if (host.layout == null) return null;
                int[] bounds = host.layout.mainBounds();
                return new Rect(bounds[0], bounds[1], bounds[2], bounds[3]);
            }
            @Override public boolean canShareContent() {
                return host.scene==WorkspaceHost.ACTIVE && host.screenOn && !host.keyguardLocked && host.promotion==null && !host.transferBusy && !host.taskCellsCovered;
            }
            @Override public boolean isActiveContentSource(String pkg) { return host.chrome.isFocusedContentSource(pkg); }
            @Override public boolean contentRailCovered() {
                return host.topFace || host.videoPane!=null && host.videoPane.embedded();
            }
            @Override public java.util.Set<String> openedAppPackages() { return host.chrome.openedQuickAppPackages(); }
            @Override public void onSendMainToTop() { host.topPresentationHost.sendMainToTop(); }
            @Override public void onTaskCellsCovered(boolean covered) { host.topPresentationHost.setTaskCellsCovered(covered); }
        },()->host.layout!=null && !host.layout.tabletLandscape && host.workspaceAlpha<1f && host.mainSideAnimator!=null
                ?host.windowPresentation.workspaceTopHole():null);
        host.topBar=chrome.bar;host.topStripBacking=chrome.backing;host.topLiveClip=chrome.clip;
        host.topBar.applyOptions(host.workspaceOptions);
        host.root.setOnDragListener((view, event) -> {
            Object source=event.getLocalState();
            if(source instanceof SideReorderDrag drag)return host.sideInteraction.onSideReorderDrag(view,event,drag);
            if(source instanceof QuickApp app)return host.sideInteraction.onQuickAppDrag(view,event,app);
            return host.contentDrag.onWindowContentDrag(event);
        });
        host.chrome.refreshQuickApps();
        host.topPresentationHost.createTopPresentation();
        host.videoPane=host.topPresentation.video();
        host.topLiveClip.addView(host.videoPane,new FrameLayout.LayoutParams(-1,host.layout.topStrip));
        host.topBar.setStatusBarInset(DeviceProfile.statusBarHeightPx(host.ctx));
        host.main.post(host.wallpapers::applyWallpaperBackdrops);
        WorkspaceChrome.addSideBackings(host.ctx,host.root,host.layout,host.sideBackingViews,
                host.chrome::sideSegmentTop,host.chrome::sideSegmentHeight);
        WorkspaceHost.log("OneStepLite top bar ready shortcuts=" + quickApps.size());
    } catch (Throwable t) {
        WorkspaceHost.log("top bar add failed: " + t);
    }
    }

    void createSubViews() {
        host.slotLifecycle.createNext(1);
    }

    private void registerDefaultDisplayListener() {
        if (host.displayListener != null) return;
        try {
            host.displayManager = (DisplayManager) host.ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (host.displayManager == null) throw new IllegalStateException("DisplayManager unavailable");
            host.displayListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY) return;
                    host.displays.scheduleDisplayGeometryRefresh();
                    Display display = host.displayManager == null ? null
                            : host.displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                    boolean displayOn = display != null && display.getState() == Display.STATE_ON;
                    // USER_PRESENT can precede STATE_ON while screenOn was already set by
                    // the broadcast. Re-run the idempotent transition so retained privacy
                    // cannot remain stuck after the physical display becomes ready.
                    if (display != null && !Boolean.valueOf(displayOn).equals(host.defaultDisplayOn)) {
                        host.defaultDisplayOn=displayOn;
                        WorkspaceSystemEvents.onScreenState(displayOn);
                    }
                }
            };
            host.displayManager.registerDisplayListener(host.displayListener, host.main);
            WorkspaceHost.log("default display geometry listener registered");
        } catch (Throwable error) {
            host.displayListener = null;
            WorkspaceHost.log("default display geometry listener unavailable: " + error);
        }
    }

    WorkspaceLayout makeAdaptiveLayout(boolean right) {
        android.util.DisplayMetrics metrics = host.ctx.getResources().getDisplayMetrics();
        return host.layoutCache.of(host.screenW, host.screenH, right, metrics.density, DeviceProfile.isTablet(host.ctx));
    }

    void hideIme() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "statusbar");
            Class<?> stub = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub");
            Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            Method m = null;
            for (Class<?> c = svc.getClass(); c != null && m == null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod("hideCurrentInputMethodForBubbles", int.class);
                } catch (NoSuchMethodException ignored) {
                    // 继续往父类找
                }
            }
            if (m == null) {
                WorkspaceHost.log("hideIme: method not found");
                return;
            }
            m.setAccessible(true);
            m.invoke(svc, 0);
            WorkspaceHost.log("hideIme invoked");
        } catch (Throwable t) {
            WorkspaceHost.log("hideIme failed: " + t);
        }
    }

    static Rect activeMainBounds() {
        WorkspaceHost h = WorkspaceHost.sInstance;
        if (h == null || h.scene != WorkspaceHost.ACTIVE || !h.screenOn || h.layout == null) return null;
        WorkspaceLayout active = h.layout;
        return new Rect(active.mainX, active.mainY,
                active.mainX + active.mainWidth, active.mainY + active.mainHeight);
    }

    static float[] activeMainTransform() {
        WorkspaceHost h = WorkspaceHost.sInstance;
        if (h == null || !hostSceneActive() || h.layout == null
                || h.promotion != null || h.transferBusy || h.keyguardLocked) return null;
        WorkspaceLayout current = h.layout;
        return new float[]{current.mainScale, current.mainOffsetY, current.mainOffsetX};
    }

    static float[] activeStatusBarTransform() {
        WorkspaceHost h = WorkspaceHost.sInstance;
        if (h == null || !h.zoomed || !h.screenOn || h.keyguardLocked || h.layout == null
                || h.root == null || h.root.getVisibility() != View.VISIBLE) return null;
        WorkspaceLayout current = h.layout;
        return new float[]{current.mainScale, current.mainOffsetY, current.mainOffsetX};
    }

    static float[] activeIslandMonitorTransform() {
        WorkspaceHost h = WorkspaceHost.sInstance;
        if (h == null || SystemClock.uptimeMillis() < h.zoomAnimUntil) return null;
        return activeMainTransform();
    }

    static boolean hostSceneActive() {
        WorkspaceHost h = WorkspaceHost.sInstance;
        return h != null && h.zoomed && h.scene == WorkspaceHost.ACTIVE && h.screenOn
                && h.root != null && h.root.getVisibility() == View.VISIBLE;
    }
}
