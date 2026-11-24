package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Full-page shortcut preferences share the outer Settings spacing, cards and typography. */
final class ShortcutSettingsContent extends LinearLayout {
    private final LinkedHashSet<String> chosen;
    private final List<ShareTarget> rows;
    private final TextView count;
    private final PinnedAppEditor pinned;
    private final Runnable persist;
    private final int primary,secondary,surface;
    private final BaseAdapter adapter;

    ShortcutSettingsContent(Context context,List<ShareTarget> targets,LinkedHashSet<String> chosen,boolean applications,Runnable persist) {
        super(context);this.chosen=chosen;this.persist=persist;rows=new ArrayList<>(targets);
        boolean dark=HyperOsStyle.dark(context);primary=dark?0xfff4f5f8:0xff000000;
        secondary=dark?0xff999ca7:0xff999999;surface=dark?0xff1b1d23:0xffffffff;
        setOrientation(VERTICAL);
        TextView help=text(applications?"开启的应用显示在顶栏，关闭后隐藏。长按上方图标调整顺序。":
                "选择后自动保存，长按已启用的图标调整顺序",12,secondary);
        help.setPadding(dp(16),dp(8),dp(16),dp(16));addView(help);
        count=text("",13,secondary);count.setPadding(dp(16),0,dp(16),dp(8));addView(count);
        List<QuickApp> apps=new ArrayList<>();for(ShareTarget target:targets)apps.add(QuickApp.shortcut(target.label,target.component,target.icon));
        pinned=new PinnedAppEditor(context,apps,chosen,this::changed);pinned.setPadding(dp(8),dp(4),dp(8),dp(4));pinned.setBackground(card());
        addView(pinned,new LayoutParams(-1,dp(64)));updateCount();
        EditText search=new EditText(context);search.setTextSize(16);search.setSingleLine();search.setTextColor(primary);
        search.setHintTextColor(secondary);search.setHint(applications?"搜索应用":"搜索分享入口");search.setPadding(dp(20),0,dp(20),0);
        search.setBackground(card());LayoutParams searchBounds=new LayoutParams(-1,dp(52));searchBounds.topMargin=dp(16);addView(search,searchBounds);
        TextView section=text(applications?"全部应用":"全部分享入口",13,secondary);section.setPadding(dp(16),dp(20),dp(16),dp(8));addView(section);
        ListView list=new ListView(context);list.setDivider(null);list.setBackground(card());list.setClipToOutline(true);
        adapter=createAdapter();adapter.notifyDataSetChanged();list.setAdapter(adapter);addView(list,new LayoutParams(-1,0,1));
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int before,int after){}
            public void afterTextChanged(Editable s){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                String query=s.toString().toLowerCase(java.util.Locale.ROOT);rows.clear();
                for(ShareTarget target:targets)if(target.label.toLowerCase(java.util.Locale.ROOT).contains(query))rows.add(target);
                adapter.notifyDataSetChanged();
            }
        });
    }
    private BaseAdapter createAdapter(){return new BaseAdapter(){
        public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return p;}
        public View getView(int p,View old,ViewGroup parent){return row(rows.get(p));}
        @Override public void notifyDataSetChanged(){
            java.util.Map<String,Integer> order=new java.util.HashMap<>();int i=0;for(String key:chosen)order.put(key,i++);
            rows.sort(java.util.Comparator.comparingInt(item->order.getOrDefault(item.key(),Integer.MAX_VALUE)));super.notifyDataSetChanged();
        }
    };}
    private View row(ShareTarget target){
        LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(20),dp(12),dp(20),dp(12));
        row.setMinimumHeight(dp(72));ImageView icon=new ImageView(getContext());icon.setImageDrawable(target.icon);
        row.addView(icon,new LayoutParams(dp(36),dp(36)));LinearLayout labels=new LinearLayout(getContext());labels.setOrientation(VERTICAL);
        TextView name=text(target.label,16,primary);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(name);
        if(!target.unavailableReason.isEmpty()){TextView detail=text(target.unavailableReason,12,secondary);detail.setMaxLines(2);
            detail.setPadding(0,dp(3),0,0);labels.addView(detail);}
        LayoutParams bounds=new LayoutParams(0,-2,1);bounds.setMarginStart(dp(12));bounds.setMarginEnd(dp(16));row.addView(labels,bounds);
        Switch toggle=WorkspaceSwitch.create(getContext(),target.label,chosen.contains(target.key()));row.addView(toggle,new LayoutParams(-2,dp(46)));
        toggle.setEnabled(target.enabled || chosen.contains(target.key()));if(!target.enabled)icon.setAlpha(.4f);
        toggle.setOnCheckedChangeListener((button,enabled)->{if(enabled)chosen.add(target.key());else chosen.remove(target.key());
            pinned.refresh();adapter.notifyDataSetChanged();changed();});
        return row;
    }
    private void changed(){updateCount();adapter.notifyDataSetChanged();persist.run();}
    @android.annotation.SuppressLint("SetTextI18n") // Workspace settings use the project's Chinese-only copy.
    private void updateCount(){count.setText("已启用 "+chosen.size()+" 项");}
    private TextView text(String value,int size,int color){TextView view=new TextView(getContext());view.setText(value);view.setTextSize(size);
        view.setTextColor(color);view.setIncludeFontPadding(false);return view;}
    private GradientDrawable card(){GradientDrawable shape=new GradientDrawable();shape.setColor(surface);shape.setCornerRadius(dp(16));return shape;}
    private int dp(int value){return HyperOsStyle.dp(getContext(),value);}
}
