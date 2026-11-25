package io.github.zerohomers0.onesteplite;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings use the installed system Settings resources and layout proportions. */
final class WorkspaceSettingsPage extends FrameLayout {
    private static final int HOME = 0, SWAP = 3, GESTURE = 5, LANDSCAPE = 6, APPS = 7, SHARE = 8, APP_DRAG = 9;
    private static final int BLUE = HyperOsStyle.CONTROL_ACCENT;
    private final SettingsActivity activity;
    private final boolean dark;
    private final int primary, secondary, surface;
    private final int[] scrollPositions = new int[APP_DRAG + 1];
    private ShortcutManagementPage shortcutPage;
    private Bundle shortcutDraft;
    private final android.database.ContentObserver gestureObserver =
            new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) {
            if (screen == HOME || screen == GESTURE) show(screen, false);
        }
    };
    private boolean gestureObserverRegistered;
    private int screen;
    private ScrollView scroll;
    private LinearLayout content;

    WorkspaceSettingsPage(SettingsActivity activity, Bundle state) {
        super(activity);
        this.activity = activity;
        dark = HyperOsStyle.dark(activity);
        primary = activity.settingsColor("miuix_preference_primary_text_color_" + (dark ? "dark" : "light"),
                dark ? 0xfff4f5f8 : 0xff000000);
        secondary = activity.settingsColor("miuix_preference_secondary_text_color_" + (dark ? "dark" : "light"),
                dark ? 0xff999ca7 : 0xff999999);
        surface = dark ? 0xff1b1d23 : 0xffffffff;
        if (state != null) {
            int[] saved = state.getIntArray("settings_scroll");
            if (saved != null)
                System.arraycopy(saved, 0, scrollPositions, 0, Math.min(saved.length, scrollPositions.length));
        }
        int restored = state == null ? HOME : state.getInt("settings_page", HOME);
        shortcutDraft=state==null?null:state.getBundle("shortcuts");
        show(restored == SWAP || restored == GESTURE || restored == LANDSCAPE
                || restored == APPS || restored == SHARE || restored == APP_DRAG ? restored : HOME, false);
    }

    void save(Bundle state) {
        if(scroll!=null)scrollPositions[screen] = scroll.getScrollY();
        if(shortcutPage!=null){Bundle draft=new Bundle();shortcutPage.saveDraft(draft);state.putBundle("shortcuts",draft);}
        state.putInt("settings_page", screen);
        state.putIntArray("settings_scroll", scrollPositions);
    }

    void refreshLayout() {
        show(screen, false);
    }

    boolean goBack() {
        if (screen == HOME) return false;
        show(HOME, true);
        return true;
    }

    private void show(int next, boolean animate) {
        if (scroll != null) scrollPositions[screen] = scroll.getScrollY();
        if(shortcutPage!=null) {
            shortcutDraft=new Bundle();if(next==screen)shortcutPage.saveDraft(shortcutDraft);
            shortcutPage=null;
        }
        screen = next;
        removeAllViews();
        scroll=null;
        if(next==APPS || next==SHARE){showShortcuts(next);return;}
        shortcutDraft=null;
        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        FrameLayout centered = new FrameLayout(activity);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), dp(32));
        int width = getResources().getDisplayMetrics().widthPixels;
        centered.addView(content, new FrameLayout.LayoutParams(width > dp(680) ? dp(680) : -1,
                -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(centered, new ScrollView.LayoutParams(-1, -1));
        addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        activity.setPageTitle(next == SWAP ? "主窗与小窗交换方式"
                : next == GESTURE ? "打开工作区的手势"
                : next == LANDSCAPE ? "横屏应用显示位置"
                : next == APP_DRAG ? "拖入空小窗" : "OneStepLite");
        if (next == SWAP) swap();
        else if (next == GESTURE) gesture();
        else if (next == LANDSCAPE) landscape();
        else if(next==APP_DRAG)appDragDiagram();
        else home();
        int restoreY = scrollPositions[next];
        scroll.post(() -> scroll.scrollTo(0, restoreY));
        if (animate && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            content.setAlpha(0f);
            content.animate().alpha(1f).setDuration(OverlayMotionSpec.PAGE_MS).start();
        }
    }

    private void showShortcuts(int next) {
        activity.setPageTitle(next==APPS?"管理快捷应用":"管理分享入口");
        shortcutPage=new ShortcutManagementPage(activity,next==APPS,shortcutDraft,()->show(HOME,true));
        addView(shortcutPage,new FrameLayout.LayoutParams(-1,-1));
    }

    private void home() {
        WorkspaceOptions options = WorkspaceSettingsStore.read(activity);
        section("外观");
        LinearLayout appearance = card();
        switchOption(appearance, "显示农历", "日期下方显示农历", options.showLunar,
                checked -> persist(WorkspaceSettingsStore.read(activity).withShowLunar(checked)));
        divider(appearance);
        menu(appearance,"主题模式",WorkspaceOptions.themeLabel(options.themeMode),()->chooseTheme(options.themeMode));
        divider(appearance);
        menu(appearance, "工作区背景", WorkspaceBackground.selected(activity) ? "自定义图片" : "系统壁纸",
                this::chooseWorkspaceBackground);
        divider(appearance);
        switchOption(appearance,"顶栏控件随窗口换边","开启时，顶部操作按钮和前台、分享标记一起换边；关闭时按默认位置显示",
                options.followTopBarSide,value->persist(WorkspaceSettingsStore.read(activity).withFollowTopBarSide(value)));
        add(appearance, 0, 12);

        section("顶栏");
        LinearLayout shortcuts=card();
        menu(shortcuts,"管理快捷应用","选择显示的应用并调整顺序",()->show(APPS,true));
        divider(shortcuts);
        menu(shortcuts,"管理分享入口","应用功能、操作与可用会话入口",()->show(SHARE,true));
        divider(shortcuts);
        menu(shortcuts,"拖入空小窗","长按快捷应用再拖入高亮空槽",()->show(APP_DRAG,true));
        add(shortcuts,0,12);

        section("内容分享");
        LinearLayout sharing=card();
        switchOption(sharing,"拖曳分享","将文字、图片和文件拖到分享栏或小窗；关闭后隐藏顶栏内容和截图入口",
                options.dragShareEnabled,checked->persist(WorkspaceSettingsStore.read(activity).withDragShareEnabled(checked)));
        divider(sharing);
        switchOption(sharing,"分享后清理临时图片","截取、提取的图片默认保留24小时；开启后缩短为交付10分钟，不删除相册原图",options.deleteSharedImages,
                checked->persist(WorkspaceSettingsStore.read(activity).withDeleteSharedImages(checked)));
        add(sharing,0,12);

        section("窗口与操作");
        LinearLayout interaction = card();
        menu(interaction, "打开工作区的手势", gestureLabel(options.entryGesture)
                +(EntryGesturePolicy.canSelect(options.entryGesture,EntryGestureSettings.lineHidden(activity))?"":" · 暂用状态栏"), () -> show(GESTURE, true));
        divider(interaction);
        menu(interaction, "主窗与小窗交换", WorkspaceOptions.modeLabel(options.swapMode), () -> show(SWAP, true));
        divider(interaction);
        switchOption(interaction, "滚动浏览小窗", "上下滑动查看其他小窗", options.scrollSideTasks,
                checked -> persist(WorkspaceSettingsStore.read(activity).withScrollSideTasks(checked)));
        divider(interaction);
        switchOption(interaction, "隐藏小窗最近任务", "仅在工作区内生效，不关闭应用", options.hideSideRecents,
                checked -> persist(WorkspaceSettingsStore.read(activity).withHideSideRecents(checked)));
        divider(interaction);
        switchOption(interaction, "切换时收纳主窗", "打开其他应用时收纳原主窗，无空位则直接切换",
                options.parkMainOnLaunch,
                checked -> persist(WorkspaceSettingsStore.read(activity).withParkMainOnLaunch(checked)));
        add(interaction, 0, 12);

        section("横屏");
        LinearLayout landscape = card();
        menu(landscape, "横屏应用显示位置", WorkspaceOptions.landscapeLabel(options.landscapeVideo),
                () -> show(LANDSCAPE, true));
        add(landscape, 0, 12);

        section("锁屏");
        LinearLayout lock = card();
        switchOption(lock, "解锁后恢复工作区", "保留窗口布局，解锁后继续使用", options.keepOnLock,
                checked -> persist(WorkspaceSettingsStore.read(activity).withKeepOnLock(checked)));
        add(lock, 0, 0);
    }

    private void switchOption(LinearLayout group, String label, String detail, boolean checked,
            java.util.function.Predicate<Boolean> save) {
        LinearLayout item=row(), labels=optionLabels(label,detail);
        LinearLayout.LayoutParams textBounds=new LinearLayout.LayoutParams(0,-2,1);
        textBounds.rightMargin=dp(16);item.addView(labels,textBounds);
        Switch toggle=settingsSwitch(label,checked);
        item.addView(toggle,new LinearLayout.LayoutParams(-2,-2));
        final boolean[] reverting={false};
        toggle.setOnCheckedChangeListener((button,value)->{
            if(reverting[0])return;
            if(!save.test(value)) {reverting[0]=true;button.setChecked(!value);reverting[0]=false;}
        });
        item.setOnClickListener(v->toggle.toggle());group.addView(item);
    }

    private LinearLayout optionLabels(String label,String detail) {
        LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);
        TextView title=text(label,WorkspacePopupStyle.TITLE_SP,primary);title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        labels.addView(title);
        if(detail!=null) {
            TextView description=text(detail,WorkspacePopupStyle.SUPPORT_SP,secondary);
            LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(-1,-2);bounds.topMargin=dp(4);
            labels.addView(description,bounds);
        }
        return labels;
    }

    /** All selection pages share the system indicator, spacing, labels and click target. */
    private void choiceOption(LinearLayout group,String label,String detail,boolean checked,
            boolean enabled,Runnable select) {
        LinearLayout item=row();
        LinearLayout labels=optionLabels(label,detail);
        LinearLayout.LayoutParams labelBounds=new LinearLayout.LayoutParams(0,-2,1);
        labelBounds.rightMargin=dp(8);item.addView(labels,labelBounds);
        RadioButton radio=new RadioButton(activity);
        Drawable indicator=activity.settingsDrawable("miuix_appcompat_btn_radio_"+(dark?"dark":"light"));
        if(indicator!=null) {radio.setButtonDrawable(indicator);radio.setButtonTintList(null);}
        else radio.setButtonTintList(ColorStateList.valueOf(checked?BLUE:secondary));
        radio.setChecked(checked);radio.setEnabled(enabled);
        radio.setContentDescription(detail==null?label:label+"，"+detail);
        item.addView(radio,new LinearLayout.LayoutParams(-2,-2));
        item.setEnabled(enabled);item.setAlpha(enabled?1f:.45f);
        if(enabled) {item.setOnClickListener(v->select.run());radio.setOnClickListener(v->select.run());}
        group.addView(item);
    }

    private void landscape() {
        int[] modes={WorkspaceOptions.VIDEO_TURNED,WorkspaceOptions.VIDEO_FULLSCREEN};
        String[] detail={"横屏显示在主窗，小窗保持可用","退出工作区，使用原生全屏"};
        int selected=WorkspaceSettingsStore.read(activity).landscapeVideo;
        for(int i=0;i<modes.length;i++) {
            final int choice=modes[i];
            diagramOption(WorkspaceOptions.landscapeLabel(choice),detail[i],choice==selected,true,
                    new VideoModeDiagram(activity,choice,secondary,dark?0xff353840:0xffe9e9e9,BLUE),()->{
                        if(persist(WorkspaceSettingsStore.read(activity).withLandscapeVideo(choice)))show(LANDSCAPE,false);
                    });
        }
        previewNote("下次进入横屏时生效");
    }

    private void diagramOption(String title,String detail,boolean selected,boolean enabled,View diagram,Runnable select) {
        LinearLayout group=card();
        choiceOption(group,title,detail,selected,enabled,select);
        addDiagram(group,diagram,enabled);
        if(enabled) {group.setOnClickListener(v->select.run());diagram.setOnClickListener(v->select.run());}
        add(group,0,14);
    }

    private void addDiagram(LinearLayout group,View diagram,boolean enabled) {
        LinearLayout.LayoutParams preview=new LinearLayout.LayoutParams(-1,dp(206));
        preview.leftMargin=preview.rightMargin=dp(12);preview.bottomMargin=dp(12);
        diagram.setAlpha(enabled?1f:.45f);group.addView(diagram,preview);
    }

    private void previewNote(String value) {
        TextView note=text(value,12,secondary);
        note.setPadding(dp(16),dp(2),dp(16),0);add(note,0,0);
    }

    private Switch settingsSwitch(String label, boolean checked) {
        return WorkspaceSwitch.create(activity,label,checked);
    }

    private void swap() {
        int selected=WorkspaceSettingsStore.read(activity).swapMode;
        for(int mode=0;mode<2;mode++) {
            final int choice=mode;
            diagramOption(WorkspaceOptions.modeLabel(mode),mode==WorkspaceOptions.FIXED_MAIN
                    ?"B 进入原主窗，A 收入右侧小窗":"B 在原侧放大，A 收入左侧小窗",mode==selected,true,
                    new SwapModeDiagram(activity,mode,secondary,dark?0xff353840:0xffe9e9e9,BLUE),()->{
                        if(persist(WorkspaceSettingsStore.read(activity).withSwapMode(choice)))show(SWAP,false);
                    });
        }
        previewNote("下次交换主窗与小窗时生效");
    }

    private String gestureLabel(int mode) {
        return mode == WorkspaceOptions.BOTTOM_CENTER_OUTWARD ? "底部向外滑" : "状态栏向内滑";
    }

    private void gesture() {
        WorkspaceOptions options = WorkspaceSettingsStore.read(activity);
        boolean lineHidden = EntryGestureSettings.lineHidden(activity);
        int selected = options.entryGesture;
        entryGestureOption(WorkspaceOptions.STATUS_BAR_INWARD,
                "状态栏向内滑", "从状态栏左侧或右侧向屏幕中间滑动", selected, true);
        String detail = lineHidden ? "从屏幕底部任意位置向左或向右滑动"
                : "需先在系统设置中关闭手势提示线";
        entryGestureOption(WorkspaceOptions.BOTTOM_CENTER_OUTWARD,
                "底部向外滑", detail, selected, lineHidden);
        previewNote("选择后自动保存");
    }

    private void entryGestureOption(int mode,String label,String detail,int selectedMode,boolean enabled) {
        diagramOption(label,detail,selectedMode==mode,enabled,
                new EntryGestureDiagram(activity,mode,secondary,dark?0xff353840:0xffe9e9e9,BLUE),()->{
                    if(!EntryGesturePolicy.canSelect(mode,EntryGestureSettings.lineHidden(activity)))
                        Toast.makeText(activity,"请先关闭系统手势提示线",Toast.LENGTH_SHORT).show();
                    else persist(WorkspaceSettingsStore.read(activity).withEntryGesture(mode));
                    show(GESTURE,false);
                });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            activity.getContentResolver().registerContentObserver(
                    android.provider.Settings.Global.getUriFor(EntryGestureSettings.HIDE_GESTURE_LINE),
                    false, gestureObserver);
            gestureObserverRegistered=true;
        } catch (RuntimeException unavailable) { /* Selection and runtime still check live state. */ }
    }

    @Override protected void onDetachedFromWindow() {
        if(gestureObserverRegistered)try{
            activity.getContentResolver().unregisterContentObserver(gestureObserver);
        }catch(RuntimeException unavailable){ /* The resolver may already be gone during teardown. */}
        gestureObserverRegistered=false;
        super.onDetachedFromWindow();
    }

    private boolean persist(WorkspaceOptions options) {
        if (WorkspaceSettingsStore.write(activity, options)) return true;
        Toast.makeText(activity, "未能保存，请重试", Toast.LENGTH_SHORT).show();
        return false;
    }

    private LinearLayout card() {
        LinearLayout group = new LinearLayout(activity);
        group.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable material = new GradientDrawable();
        material.setColor(surface);
        material.setCornerRadius(activity.settingsDimension("miuix_theme_radius_common", 16));
        group.setBackground(material);
        group.setClipToOutline(true);
        return group;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(activity.settingsDimension("miuix_preference_item_min_height", 56));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x18ffffff : 0x0b000000),
                null, shape(0xffffffff, 0)));
        return row;
    }

    private LinearLayout menu(LinearLayout group, String title, String summary, Runnable action) {
        LinearLayout row = row();
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, primary));
        TextView detail = text(summary, 12, secondary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(3); labels.addView(detail, lp);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        View arrow = new View(activity);
        arrow.setBackground(new Chevron());
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.setOnClickListener(v -> action.run());
        group.addView(row);
        return row;
    }

    private void divider(LinearLayout group) {
        View divider = new View(activity);
        divider.setBackgroundColor(dark ? 0x08ffffff : 0x04000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(.5f));
        lp.leftMargin = lp.rightMargin = dp(20);
        group.addView(divider, lp);
    }

    private void section(String title) {
        TextView section = text(title, 13, secondary);
        section.setPadding(dp(16), dp(8), dp(16), 0);
        section.setAccessibilityHeading(true);
        add(section, 0, 8);
    }

    private void chooseTheme(int current) {
        android.app.Dialog dialog=new android.app.Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        WorkspacePopupStyle.decorate(body,null);
        WorkspacePopupStyle.heading(body,"主题模式",dialog::dismiss);
        LinearLayout choices=card();
        for(int mode=WorkspaceOptions.THEME_SYSTEM;mode<=WorkspaceOptions.THEME_DARK;mode++){
            int choice=mode;
            if(mode!=WorkspaceOptions.THEME_SYSTEM)divider(choices);
            choiceOption(choices,WorkspaceOptions.themeLabel(mode),null,current==mode,true,()->{
                dialog.dismiss();
                if(choice!=current && persist(WorkspaceSettingsStore.read(activity).withThemeMode(choice)))
                    activity.recreate();
            });
        }
        body.addView(choices,new LinearLayout.LayoutParams(-1,-2));
        dialog.setContentView(body);dialog.show();
        android.view.Window window=dialog.getWindow();
        if(window!=null){WorkspacePopupStyle.size(window,activity);window.setLayout(WorkspacePopupStyle.width(activity),-2);}
    }

    private void chooseWorkspaceBackground() {
        boolean custom = WorkspaceBackground.selected(activity);
        android.app.Dialog dialog = new android.app.Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        WorkspacePopupStyle.decorate(body, null);
        WorkspacePopupStyle.heading(body, "工作区背景", dialog::dismiss);
        LinearLayout choices = card();
        choiceOption(choices, "系统壁纸", null, !custom, true, () -> {
            dialog.dismiss();
            if (custom) activity.updateWorkspaceBackground(null);
        });
        divider(choices);
        choiceOption(choices, "自定义图片", custom ? "点击更换图片" : null, custom, true, () -> {
            dialog.dismiss();
            activity.pickWorkspaceBackground();
        });
        body.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(body);
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            WorkspacePopupStyle.size(window, activity);
            window.setLayout(WorkspacePopupStyle.width(activity), -2);
        }
    }

    private void appDragDiagram() {
        LinearLayout group=card();
        LinearLayout item=row();
        LinearLayout labels=optionLabels("拖动顶栏应用","长按快捷应用，拖到高亮的空小窗后松手");
        labels.getChildAt(0).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        item.addView(labels,new LinearLayout.LayoutParams(-1,-2));
        group.addView(item);
        addDiagram(group,SwapModeDiagram.insertIntoEmptySlot(
                activity,secondary,dark?0xff353840:0xffe9e9e9,BLUE),true);
        add(group,0,14);
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setIncludeFontPadding(false);
        text.setLineSpacing(dp(2), 1f);
        return text;
    }

    private void add(View view, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top); lp.bottomMargin = dp(bottom);
        content.addView(view, lp);
    }

    private GradientDrawable shape(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(float value) { return Math.max(value > 0 ? 1 : 0, Math.round(value * getResources().getDisplayMetrics().density)); }

    private final class Chevron extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas) {
            paint.setColor(secondary); paint.setStrokeWidth(dp(1.6f)); paint.setStrokeCap(Paint.Cap.ROUND);
            float x = getBounds().exactCenterX(), y = getBounds().exactCenterY();
            canvas.drawLine(x - dp(2), y - dp(4), x + dp(2), y, paint);
            canvas.drawLine(x + dp(2), y, x - dp(2), y + dp(4), paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
