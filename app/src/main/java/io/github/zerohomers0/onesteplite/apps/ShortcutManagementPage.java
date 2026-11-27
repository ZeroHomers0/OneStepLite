package io.github.zerohomers0.onesteplite;

import android.content.ClipDescription;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full settings page: cancel stale loads, serialize each selection's persistence without leaving. */
final class ShortcutManagementPage extends FrameLayout {
    private static final ExecutorService APP_WORKER=BackgroundExecutor.single("OneStepLite-shortcuts-apps");
    private static final ExecutorService SHARE_WORKER=BackgroundExecutor.single("OneStepLite-shortcuts-share");
    private final boolean applications;
    private final Bundle restored;
    private final Warmup warmup;
    private LinkedHashSet<String> chosen;
    private int generation;

    ShortcutManagementPage(Context context,boolean applications,Bundle restored,Warmup warmup) {
        super(context);this.applications=applications;this.restored=restored;this.warmup=warmup;
        setPadding(dp(20),dp(4),dp(20),dp(32));
    }

    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();load();}
    @Override protected void onDetachedFromWindow(){generation++;super.onDetachedFromWindow();}

    void saveDraft(Bundle state) {
        if(chosen!=null)state.putStringArrayList("shortcut_draft",new ArrayList<>(chosen));
    }

    private void load() {
        final int token=++generation;
        CompletableFuture<Snapshot> pending=warmup.take(getContext(),applications);
        if(!pending.isDone())postDelayed(()->{
            if(token==generation && isAttachedToWindow() && !pending.isDone())status("正在读取…");
        },300);
        pending.whenComplete((snapshot,failure)->post(()->{
            if(token!=generation || !isAttachedToWindow())return;
            if(failure!=null){status("无法读取配置，请返回后重试");return;}
            ArrayList<String> draft=restored==null?null:restored.getStringArrayList("shortcut_draft");
            LinkedHashSet<String> initial=new LinkedHashSet<>(draft==null?snapshot.keys:draft);
            if(!applications)initial.removeIf(ShareActions::retired);
            showEditor(snapshot.targets,initial);
        }));
    }

    private record Snapshot(List<ShareTarget> targets,List<String> keys) {}

    static final class Warmup {
        private static final long MAX_AGE_MS=10_000;
        private record Entry(long started,CompletableFuture<Snapshot> result) {}
        private final Entry[] entries=new Entry[2];

        void prepare(Context context) {
            prepare(context,true);
            prepare(context,false);
        }

        private Entry prepare(Context context,boolean applications) {
            int index=applications?0:1;
            long now=android.os.SystemClock.uptimeMillis();
            Entry entry=entries[index];
            if(entry!=null && now-entry.started<MAX_AGE_MS && !entry.result.isCompletedExceptionally())return entry;
            Context app=context.getApplicationContext();
            Entry next=new Entry(now,CompletableFuture.supplyAsync(()->read(app,applications),worker(applications)));
            entries[index]=next;
            return next;
        }

        CompletableFuture<Snapshot> take(Context context,boolean applications) {
            Entry entry=prepare(context,applications);
            entries[applications?0:1]=null;
            return entry.result;
        }
    }

    private static Snapshot read(Context context,boolean applications) {
        String pins=ShortcutSettingsClient.read(context,kind(applications));
        boolean implicitApps=applications && pins==null;
        if(pins==null)pins=Settings.Secure.getString(context.getContentResolver(),applications
                ?ShortcutSettingsClient.LEGACY_APPS:ShortcutSettingsClient.LEGACY_SHARE);
        List<String> keys=QuickAppOrder.decode(pins);
        if(!applications)return new Snapshot(ShareTargetCatalog.load(context,
                new ClipDescription("内容",new String[]{"*/*"}),0,true,keys),keys);
        List<ShareTarget> targets=new ArrayList<>();
        List<String> installed=new ArrayList<>();
        for(QuickApp app:QuickApp.loadAlphabetical(context)) {
            targets.add(new ShareTarget(app.component,app.label,app.icon,true));
            installed.add(app.component.flattenToString());
        }
        keys=QuickAppReconcilePolicy.prune(keys,installed);
        if(implicitApps) {
            LinkedHashSet<String> visible=new LinkedHashSet<>(keys);
            visible.addAll(installed);
            keys=new ArrayList<>(visible);
        }
        return new Snapshot(targets,keys);
    }

    private void showEditor(List<ShareTarget> targets,LinkedHashSet<String> initial) {
        chosen=initial;removeAllViews();
        ShortcutSettingsContent editor=new ShortcutSettingsContent(getContext(),targets,chosen,applications,this::persist);
        int width=getResources().getDisplayMetrics().widthPixels;
        addView(editor,new LayoutParams(Math.min(width-dp(40),dp(640)),-1,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
    }

    private void persist() {
        if(chosen==null)return;
        String pins=QuickAppOrder.encode(chosen);int token=generation;
        worker(applications).execute(()->{
            Boolean saved=ShortcutSettingsClient.write(getContext(),kind(applications),pins);
            post(()->{
                if(token!=generation || !isAttachedToWindow())return;
                if(!Boolean.TRUE.equals(saved))Toast.makeText(getContext(),"保存失败，请重新切换后重试",Toast.LENGTH_SHORT).show();
            });
        });
    }

    private static ExecutorService worker(boolean applications){return applications?APP_WORKER:SHARE_WORKER;}
    private static String kind(boolean applications){return applications?ShortcutSettingsClient.APPS:ShortcutSettingsClient.SHARE;}
    private void status(String value){removeAllViews();TextView text=new TextView(getContext());text.setText(value);
        text.setTextSize(14);text.setTextColor(HyperOsStyle.textSecondary(getContext()));text.setGravity(Gravity.CENTER);
        addView(text,new LayoutParams(-1,-1));}
    private int dp(int value){return Math.round(HyperOsStyle.dp(getContext(),value));}
}
