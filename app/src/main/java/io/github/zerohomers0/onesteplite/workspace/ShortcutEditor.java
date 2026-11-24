package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Shared app and share shortcut draft editor; callers own persistence and navigation. */
final class ShortcutEditor extends LinearLayout {
    private Runnable changed=()->{};
    void saveImmediately(Runnable save){changed=save;getChildAt(getChildCount()-1).setVisibility(GONE);
        ((TextView)description).setText("选择后自动保存 · 长按图标调整顺序");}
    private final TextView count;
    private final LinkedHashSet<String> chosen;
    private final View heading,description,selectedIcons,searchBox;

    ShortcutEditor(Context context,List<ShareTarget> targets,LinkedHashSet<String> chosen,
            Runnable cancel,Runnable save) {
        this(context,targets,chosen,cancel,save,false);
    }

    ShortcutEditor(Context context,List<ShareTarget> targets,LinkedHashSet<String> chosen,
            Runnable cancel,Runnable save,boolean applications) {
        super(context);this.chosen=chosen;setOrientation(VERTICAL);setPadding(dp(16),dp(16),dp(16),dp(12));
        GradientDrawable background=new GradientDrawable();background.setColor(HyperOsStyle.page(context));
        background.setCornerRadius(dp(16));setBackground(background);setClipToOutline(true);
        TextView title=label(applications?"快捷应用":"拖曳分享入口",18);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);addView(title);
        heading=title;
        TextView help=label(applications?"选择常用应用，点击顶栏图标打开":"选择常用分享功能，拖曳时显示在顶栏",12);
        description=help;
        help.setTextColor(HyperOsStyle.textSecondary(context));help.setPadding(0,dp(6),0,dp(14));addView(help);
        count=label("",11);count.setTextColor(HyperOsStyle.textSecondary(context));addView(count);
        this.applications=applications;refreshCount();
        List<QuickApp> apps=new ArrayList<>();
        for(ShareTarget target:targets)apps.add(QuickApp.shortcut(target.label,target.component,target.icon));
        PinnedAppEditor editor=new PinnedAppEditor(context,apps,chosen,()->{refreshCount();changed.run();});
        selectedIcons=editor;
        addView(editor,new LayoutParams(-1,dp(56)));
        EditText search=new EditText(context);search.setSingleLine();search.setTextSize(14);search.setHint(applications?"搜索应用":"搜索分享功能");
        searchBox=search;
        search.setTextColor(HyperOsStyle.textPrimary(context));search.setHintTextColor(HyperOsStyle.textSecondary(context));
        search.setPadding(dp(12),0,dp(12),0);search.setBackground(HyperOsStyle.controlButton(context,false,10));
        LayoutParams searchBounds=new LayoutParams(-1,dp(44));searchBounds.bottomMargin=dp(8);addView(search,searchBounds);
        List<ShareTarget> rows=new ArrayList<>(targets);ListView list=new ListView(context);list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        BaseAdapter adapter=adapter(rows,editor);adapter.notifyDataSetChanged();list.setAdapter(adapter);addView(list,new LayoutParams(-1,0,1));
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                String query=s.toString().toLowerCase(java.util.Locale.ROOT);rows.clear();
                for(ShareTarget target:targets)if(target.label.toLowerCase(java.util.Locale.ROOT).contains(query))rows.add(target);
                adapter.notifyDataSetChanged();
            }
            public void afterTextChanged(Editable text){}
        });
        LinearLayout actions=new LinearLayout(context);
        addAction(actions,"取消",false,cancel);addAction(actions,"保存",true,save);
        addView(actions,new LayoutParams(-1,dp(50)));
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        boolean compact=MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(heightSpec)<dp(420);
        heading.setVisibility(compact?GONE:VISIBLE);description.setVisibility(compact?GONE:VISIBLE);
        count.setVisibility(compact?GONE:VISIBLE);
        setPadding(dp(16),dp(compact?8:16),dp(16),dp(compact?8:12));
        selectedIcons.getLayoutParams().height=dp(compact?44:56);
        ((LayoutParams)searchBox.getLayoutParams()).bottomMargin=dp(compact?4:8);
        super.onMeasure(widthSpec,heightSpec);
    }

    private BaseAdapter adapter(List<ShareTarget> rows,PinnedAppEditor editor) {
        return new BaseAdapter(){
            public int getCount(){return rows.size();}public Object getItem(int index){return rows.get(index);}
            public long getItemId(int index){return index;}
            public View getView(int index,View old,ViewGroup parent){
                ShareTarget target=rows.get(index);LinearLayout row=new LinearLayout(getContext());
                row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(2),dp(6),dp(2),dp(6));row.setMinimumHeight(dp(58));
                ImageView icon=new ImageView(getContext());icon.setImageDrawable(target.icon);
                row.addView(icon,new LayoutParams(dp(32),dp(32)));
                TextView name=label(target.label,14);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout labels=new LinearLayout(getContext());labels.setOrientation(VERTICAL);labels.addView(name);
                if(!target.unavailableReason.isEmpty()){TextView reason=label(target.unavailableReason,11);
                    reason.setTextColor(HyperOsStyle.textSecondary(getContext()));reason.setMaxLines(2);labels.addView(reason);}
                LayoutParams text=new LayoutParams(0,-2,1);text.setMarginStart(dp(10));text.setMarginEnd(dp(16));row.addView(labels,text);
                Switch check=WorkspaceSwitch.create(getContext(),target.label,chosen.contains(target.key()));row.addView(check,new LayoutParams(-2,dp(46)));
                check.setEnabled(target.enabled || chosen.contains(target.key()));row.setAlpha(target.enabled?1f:.5f);
                check.setOnCheckedChangeListener((button,enabled)->{
                    if(enabled)chosen.add(target.key());else chosen.remove(target.key());editor.refresh();refreshCount();notifyDataSetChanged();changed.run();
                });row.setOnClickListener(v->{if(check.isEnabled())check.toggle();});return row;
            }
            @Override public void notifyDataSetChanged(){
                java.util.Map<String,Integer> order=new java.util.HashMap<>();int index=0;
                for(String key:chosen)order.put(key,index++);
                rows.sort(java.util.Comparator.comparingInt(target->order.getOrDefault(target.key(),Integer.MAX_VALUE)));
                super.notifyDataSetChanged();
            }
        };
    }

    private final boolean applications;
    private void refreshCount(){count.setText(chosen.isEmpty()?(applications?"未固定 · 按使用情况显示应用":"未选择分享入口")
            :"已固定 "+chosen.size()+" 项 · 长按图标调整顺序");}
    private void addAction(LinearLayout actions,String text,boolean primary,Runnable action) {
        TextView button=label(text,14);button.setGravity(Gravity.CENTER);
        button.setTextColor(primary?android.graphics.Color.WHITE:HyperOsStyle.textPrimary(getContext()));
        button.setBackground(HyperOsStyle.controlButton(getContext(),primary,10));button.setOnClickListener(v->action.run());
        LayoutParams params=new LayoutParams(0,-1,1);params.setMargins(dp(3),dp(6),dp(3),0);actions.addView(button,params);
    }
    private TextView label(String value,int size){TextView text=new TextView(getContext());text.setText(value);
        text.setTextSize(size);text.setTextColor(HyperOsStyle.textPrimary(getContext()));return text;}
    private int dp(int value){return Math.round(HyperOsStyle.dp(getContext(),value));}
}
