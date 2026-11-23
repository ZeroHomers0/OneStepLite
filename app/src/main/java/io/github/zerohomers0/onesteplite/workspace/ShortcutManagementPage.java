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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full settings page: cancel stale loads, serialize each selection's persistence without leaving. */
final class ShortcutManagementPage extends FrameLayout {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private final boolean applications;
    private final Bundle restored;
    private final Runnable back;
    private LinkedHashSet<String> chosen;
    private int generation;

    ShortcutManagementPage(Context context,boolean applications,Bundle restored,Runnable back) {
        super(context);this.applications=applications;this.restored=restored;this.back=back;
        setPadding(dp(12),dp(4),dp(12),dp(16));status("正在读取…");
    }

    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();load();}
    @Override protected void onDetachedFromWindow(){generation++;super.onDetachedFromWindow();}

    void saveDraft(Bundle state) {
        if(chosen!=null)state.putStringArrayList("shortcut_draft",new ArrayList<>(chosen));
    }

    private void load() {
        final int token=++generation;
        WORKER.execute(()->{
            try {
                String pins=ShortcutSettingsClient.read(getContext(),kind());
                if(pins==null)pins=Settings.Secure.getString(getContext().getContentResolver(),applications
                        ?ShortcutSettingsClient.LEGACY_APPS:ShortcutSettingsClient.LEGACY_SHARE);
                List<String> keys=QuickAppOrder.decode(pins);List<ShareTarget> targets=new ArrayList<>();
                if(applications) {
                    List<QuickApp> apps=QuickApp.load(getContext());
                    apps.sort((a,b)->a.label.compareToIgnoreCase(b.label));List<String> installed=new ArrayList<>();
                    for(QuickApp app:apps){targets.add(new ShareTarget(app.component,app.label,app.icon,true));installed.add(app.component.flattenToString());}
                    keys=QuickAppReconcilePolicy.prune(keys,installed);
                } else targets=ShareTargetCatalog.load(getContext(),new ClipDescription("内容",new String[]{"*/*"}),0,true,keys);
                ArrayList<String> draft=restored==null?null:restored.getStringArrayList("shortcut_draft");
                LinkedHashSet<String> initial=new LinkedHashSet<>(draft==null?keys:draft);
                List<ShareTarget> available=targets;
                post(()->{if(token==generation && isAttachedToWindow())showEditor(available,initial);});
            } catch(RuntimeException unavailable) {
                post(()->{if(token==generation && isAttachedToWindow())status("无法读取配置，请返回后重试");});
            }
        });
    }

    private void showEditor(List<ShareTarget> targets,LinkedHashSet<String> initial) {
        chosen=initial;removeAllViews();
        ShortcutEditor editor=new ShortcutEditor(getContext(),targets,chosen,back,this::persist,applications);
        editor.saveImmediately(this::persist);
        int width=getResources().getDisplayMetrics().widthPixels;
        addView(editor,new LayoutParams(Math.min(width-dp(24),dp(680)),-1,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
    }

    private void persist() {
        if(chosen==null)return;
        String pins=QuickAppOrder.encode(chosen);int token=generation;
        WORKER.execute(()->{
            Boolean saved=ShortcutSettingsClient.write(getContext(),kind(),pins);
            post(()->{
                if(token!=generation || !isAttachedToWindow())return;
                if(!Boolean.TRUE.equals(saved))Toast.makeText(getContext(),"保存失败，请重新切换后重试",Toast.LENGTH_SHORT).show();
            });
        });
    }

    private String kind(){return applications?ShortcutSettingsClient.APPS:ShortcutSettingsClient.SHARE;}
    private void status(String value){removeAllViews();TextView text=new TextView(getContext());text.setText(value);
        text.setTextSize(14);text.setTextColor(HyperOsStyle.textSecondary(getContext()));text.setGravity(Gravity.CENTER);
        addView(text,new LayoutParams(-1,-1));}
    private int dp(int value){return Math.round(HyperOsStyle.dp(getContext(),value));}
}
