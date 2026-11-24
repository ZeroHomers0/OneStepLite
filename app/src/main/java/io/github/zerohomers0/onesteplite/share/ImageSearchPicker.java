package io.github.zerohomers0.onesteplite;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User chooses the actual image-search share activity; no undocumented app deep links. */
final class ImageSearchPicker {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private ImageSearchPicker(){}
    static void show(Context context) {
        Dialog dialog=new Dialog(context);dialog.setCanceledOnTouchOutside(true);dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);WorkspacePopupStyle.decorate(body,null);
        WorkspacePopupStyle.heading(body,"图片搜索应用",dialog::dismiss);
        TextView hint=new TextView(context);hint.setText("选择用于以图搜索的分享入口");hint.setTextSize(12);
        hint.setTextColor(HyperOsStyle.textSecondary(context));body.addView(hint);
        ScrollView scroll=new ScrollView(context);LinearLayout rows=new LinearLayout(context);rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(body);dialog.show();WorkspacePopupStyle.size(dialog.getWindow(),context);
        WORKER.execute(()->{
            List<ResolveInfo> entries=context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_SEND).setType("image/*"),0);
            java.util.ArrayList<String[]> choices=new java.util.ArrayList<>();
            for(ResolveInfo entry:entries)if(entry.activityInfo!=null && entry.activityInfo.exported
                    && !ContentInboxProvider.MODULE.equals(entry.activityInfo.packageName))choices.add(new String[]{
                        entry.loadLabel(context.getPackageManager()).toString(),new ComponentName(entry.activityInfo.packageName,entry.activityInfo.name).flattenToString()});
            body.post(()->{
                if(!dialog.isShowing())return;
                add(rows,"不设置",()->save(context,"",dialog));
                for(String[] choice:choices)add(rows,choice[0],()->save(context,choice[1],dialog));
            });
        });
    }
    private static void add(LinearLayout rows,String label,Runnable clicked) {
        TextView item=WorkspacePopupStyle.action(rows.getContext(),label,false,clicked);
        LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(-1,HyperOsStyle.dp(rows.getContext(),48));bounds.topMargin=HyperOsStyle.dp(rows.getContext(),6);
        rows.addView(item,bounds);
    }
    private static void save(Context context,String component,Dialog dialog) {
        WORKER.execute(()->{
            boolean saved=context.getSharedPreferences("share_actions",Context.MODE_PRIVATE).edit().putString("image_search",component).commit();
            View decor=dialog.getWindow().getDecorView();decor.post(()->{
                if(!dialog.isShowing())return;
                if(saved)dialog.dismiss();else android.widget.Toast.makeText(context,"保存失败，请重试",android.widget.Toast.LENGTH_SHORT).show();
            });
        });
    }
}
