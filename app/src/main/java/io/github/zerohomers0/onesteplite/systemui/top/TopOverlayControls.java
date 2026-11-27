package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Owns the task exit window; its view also follows the native task's fade. */
final class TopOverlayControls implements AutoCloseable {
    private final Context context;
    private final WindowManager windows;
    private final Runnable exit;
    private View plate;
    private int width, height;

    TopOverlayControls(Context context, WindowManager windows, Runnable exit) {
        this.context = context; this.windows = windows; this.exit = exit;
    }

    View view() { return plate; }

    void show(int nextWidth, int nextHeight) {
        if (plate != null && width == nextWidth && height == nextHeight) return;
        close();
        int size = HyperOsStyle.dp(context, IconButton.TOP_CONTROL_TOUCH_DP);
        int margin = HyperOsStyle.dp(context, IconButton.TOP_CONTROL_MARGIN_DP);
        FrameLayout content = new FrameLayout(context);
        IconButton button = IconButton.topControl(context, IconButton.CLOSE, "退出顶栏，应用回到主窗", exit);
        content.addView(button, new FrameLayout.LayoutParams(size, size));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = IconButton.TOP_CONTROL_GRAVITY;
        params.x = Math.max(0, nextWidth - size - margin);
        params.y = Math.max(0, nextHeight - size - margin);
        params.setTitle("OneStepLiteTopExit");
        HostWindowPolicy.configure(params);
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try {
            windows.addView(content, params);
            plate = content; width = nextWidth; height = nextHeight;
        } catch (RuntimeException failure) {
            android.util.Log.w(Tags.PROBE, "upper exit plate unavailable", failure);
        }
    }

    @Override public void close() {
        View old = plate; plate = null;
        if (old == null) return;
        old.setVisibility(View.GONE);
        try { windows.removeViewImmediate(old); }
        catch (RuntimeException failure) { android.util.Log.w(Tags.PROBE, "upper exit plate release", failure); }
    }
}
