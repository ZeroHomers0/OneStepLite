package io.github.zerohomers0.onesteplite;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Workspace settings opened by the native system Settings entry. */
public final class SettingsActivity extends Activity {
    private static final int PICK_WORKSPACE_BACKGROUND = 41;
    private WorkspaceSettingsPage settings;
    private TextView pageTitle;
    private Resources systemSettingsResources;
    private boolean updatingBackground;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            systemSettingsResources = createPackageContext(Packages.SETTINGS, 0)
                    .createConfigurationContext(getResources().getConfiguration()).getResources();
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        HyperOsStyle.theme(WorkspaceSettingsStore.read(this).themeMode);
        boolean dark=HyperOsStyle.dark(this);
        int pageBackground = dark ? 0xff14151a : 0xfff2f2f2;
        page.setBackgroundColor(pageBackground);
        getWindow().setBackgroundDrawable(new ColorDrawable(pageBackground));

        int barHeight = settingsDimension("miuix_appcompat_action_bar_default_height", 56);
        FrameLayout titleBar = new FrameLayout(this);
        pageTitle = new TextView(this);
        pageTitle.setTextSize(WorkspacePopupStyle.TITLE_SP);
        pageTitle.setTypeface(Typeface.create(pageTitle.getTypeface(), Typeface.BOLD));
        pageTitle.setTextColor(settingsColor("miuix_preference_primary_text_color_" + (dark ? "dark" : "light"),
                dark ? 0xfff4f5f8 : 0xff000000));
        pageTitle.setGravity(Gravity.CENTER);
        pageTitle.setSingleLine(true);
        pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pageTitle.setAccessibilityHeading(true);
        FrameLayout.LayoutParams titleBounds = new FrameLayout.LayoutParams(-1, -1);
        titleBounds.leftMargin = titleBounds.rightMargin = barHeight;
        titleBar.addView(pageTitle, titleBounds);
        titleBar.addView(WorkspacePopupStyle.backButton(this,pageTitle.getCurrentTextColor(),this::navigateBack),
                new FrameLayout.LayoutParams(barHeight,barHeight,Gravity.START|Gravity.CENTER_VERTICAL));
        page.addView(titleBar, new LinearLayout.LayoutParams(-1, barHeight));
        setContentView(page);

        settings = new WorkspaceSettingsPage(this, savedInstanceState);
        page.addView(settings, new LinearLayout.LayoutParams(-1, 0, 1f));
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);

        page.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        page.requestApplyInsets();

        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(dark ? 0 : lightBars, lightBars);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // These size/direction changes are handled by this Activity. Keep the
        // real Window and log controller while rebuilding the responsive page.
        if (settings != null) settings.refreshLayout();
    }

    void setPageTitle(String title) {
        pageTitle.setText(title);
    }

    void pickWorkspaceBackground() {
        if (updatingBackground) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            startActivityForResult(picker, PICK_WORKSPACE_BACKGROUND);
        } catch (android.content.ActivityNotFoundException unavailable) {
            android.widget.Toast.makeText(this, "无法打开图片选择器", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_WORKSPACE_BACKGROUND && result == RESULT_OK
                && data != null && data.getData() != null) updateWorkspaceBackground(data.getData());
    }

    void updateWorkspaceBackground(Uri uri) {
        if (updatingBackground) return;
        updatingBackground = true;
        android.content.Context application = getApplicationContext();
        new Thread(() -> {
            boolean saved;
            try {
                if (uri == null) WorkspaceBackground.reset(application);
                else WorkspaceBackground.save(application, uri);
                saved = true;
            } catch (java.io.IOException | RuntimeException unavailable) {
                saved = false;
            }
            final boolean success = saved;
            runOnUiThread(() -> {
                updatingBackground = false;
                if (isDestroyed() || isFinishing()) return;
                if (success) settings.refreshLayout();
                else android.widget.Toast.makeText(this, uri == null ? "恢复背景失败" : "无法读取这张图片",
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        }, "WorkspaceBackgroundImport").start();
    }

    int settingsColor(String name, int fallback) {
        try {
            if (systemSettingsResources != null) {
                int id = systemSettingsResources.getIdentifier(name, "color", Packages.SETTINGS);
                if (id != 0) return systemSettingsResources.getColor(id, null);
            }
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    int settingsDimension(String name, int fallbackDp) {
        try {
            if (systemSettingsResources != null) {
                int id = systemSettingsResources.getIdentifier(name, "dimen", Packages.SETTINGS);
                if (id != 0) return systemSettingsResources.getDimensionPixelSize(id);
            }
        } catch (RuntimeException ignored) { }
        return Math.round(fallbackDp * getResources().getDisplayMetrics().density);
    }

    Drawable settingsDrawable(String name) {
        try {
            if (systemSettingsResources != null) {
                int id = systemSettingsResources.getIdentifier(name, "drawable", Packages.SETTINGS);
                if (id != 0) return systemSettingsResources.getDrawable(id, null);
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        settings.save(state);
        super.onSaveInstanceState(state);
    }

    private void navigateBack() {
        if (!settings.goBack()) finish();
    }
}
