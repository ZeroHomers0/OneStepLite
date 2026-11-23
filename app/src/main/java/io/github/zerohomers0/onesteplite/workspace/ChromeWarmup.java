package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.content.MutableContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import java.util.List;

/** Main-thread, detached header layout and one software draw; no window or subscription. */
final class ChromeWarmup {
    private static volatile TopBar bar;
    private static MutableContextWrapper ownerContext;
    private static int user=-1,width,height;
    private static String configuration;
    private static volatile boolean claimed;
    private static final TopBar.Listener IDLE=new TopBar.Listener() {
        public void onOpenSettings() {}
        public void onSetMainSide(boolean right) {}
        public void onExit() {}
        public void onLaunch(QuickApp app) {}
        public boolean canCustomizeApps() { return false; }
    };
    private ChromeWarmup() {}

    static boolean needsPreparation() { return !claimed&&bar==null; }

    static void prepare(Context base,int expectedUser,List<QuickApp> apps,WorkspaceOptions options) {
        if (claimed || bar!=null || CurrentUser.id()!=expectedUser || !EntryGuard.canOpen(base)) return;
        Bitmap scratch=null;
        try {
            Display display=((DisplayManager)base.getSystemService(Context.DISPLAY_SERVICE))
                    .getDisplay(Display.DEFAULT_DISPLAY);
            Context context=base.createDisplayContext(display).createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
            Point size=new Point();display.getRealSize(size);
            if(size.x<=0||size.y<=0)return;
            boolean right=context.createDeviceProtectedStorageContext()
                    .getSharedPreferences("workspace_position",Context.MODE_PRIVATE).getBoolean("main_right",false);
            WorkspaceLayout layout=WorkspaceLayout.of(size.x,size.y,right,
                    context.getResources().getDisplayMetrics().density,DeviceProfile.isTablet(context));
            MutableContextWrapper owner=new MutableContextWrapper(context);
            TopBar prepared=new TopBar(owner,layout,apps,IDLE);
            prepared.setWorkspaceActive(false);prepared.applyOptions(options);
            prepared.setStatusBarInset(DeviceProfile.statusBarHeightPx(context));
            prepared.measure(View.MeasureSpec.makeMeasureSpec(size.x,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(layout.topStrip,View.MeasureSpec.EXACTLY));
            prepared.layout(0,0,size.x,layout.topStrip);
            // Warm text/icon decoding without allocating a physical screen-sized buffer.
            int rasterWidth=Math.max(1,Math.min(512,size.x));
            int rasterHeight=Math.max(1,Math.min(512,layout.topStrip));
            scratch=Bitmap.createBitmap(rasterWidth,rasterHeight,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(scratch);
            canvas.scale(rasterWidth/(float)size.x,rasterHeight/(float)layout.topStrip);
            try { prepared.draw(canvas); }
            catch (RuntimeException softwareUnsupported) {
                // Some application icons are hardware-backed. Keep the measured
                // hierarchy; the attached hardware renderer can draw them normally.
            }
            ownerContext=owner;bar=prepared;user=expectedUser;width=size.x;height=size.y;configuration=key(context);
        } catch (Throwable unavailable) { bar=null; }
        finally { if(scratch!=null)scratch.recycle(); }
    }

    static TopBar take(Context context,WorkspaceLayout layout,TopBar.Listener listener) {
        claimed=true;TopBar ready=bar;MutableContextWrapper owner=ownerContext;bar=null;ownerContext=null;
        if(ready==null || user!=CurrentUser.id() || width!=layout.width || height!=layout.height
                || !key(context).equals(configuration))return null;
        owner.setBaseContext(context);
        ready.bindListener(listener);ready.setMainOnRight(layout.mainOnRight);
        ready.setWorkspaceActive(true);return ready;
    }
    static void invalidate(boolean userChanged) {
        bar=null;ownerContext=null;if(userChanged)claimed=false;
    }
    private static String key(Context context) {
        android.content.res.Configuration c=context.getResources().getConfiguration();
        return c.orientation+":"+c.densityDpi+":"+c.fontScale+":"+c.uiMode+":"+c.getLocales().toLanguageTags();
    }
}
