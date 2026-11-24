package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Page actions occupy one slot beside the content tabs. */
final class ContentPanelActions extends FrameLayout {
    private final IconButton button;
    private final TextView count;
    private int page;
    private boolean selecting;
    private java.util.function.Consumer<View> menuHost=ignored->{};

    ContentPanelActions(Context context,Runnable select,Runnable clear,Runnable record,Runnable browse) {
        super(context);
        button=new IconButton(context,IconButton.MORE_VERTICAL,"内容操作");
        addView(button,new LayoutParams(-1,-1));
        count=new TextView(context);count.setTextColor(HyperOsStyle.CONTROL_ACCENT);
        count.setTextSize(9);count.setGravity(Gravity.CENTER);count.setIncludeFontPadding(false);
        LayoutParams badge=new LayoutParams(-1,-2,Gravity.BOTTOM);
        badge.bottomMargin=HyperOsStyle.dp(context,1);
        addView(count,badge);
        button.setTooltipText("内容操作");
        button.setOnClickListener(v->{
            if(selecting){select.run();return;}
            LinearLayout menu=new LinearLayout(context);
            menu.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
            background.setColor(HyperOsStyle.page(context));background.setCornerRadius(HyperOsStyle.dp(context,16));
            menu.setBackground(background);menu.setClipToOutline(true);
            menu.setElevation(HyperOsStyle.dp(context,8));
            addAction(menu,"多选",select);
            if(page==1)addAction(menu,"更多文件",browse);
            if(page==2)addAction(menu,"历史设置",record);
            addAction(menu,page==3?"移除暂存":"清除记录",clear);
            menuHost.accept(menu);
        });
    }

    void bindMenu(java.util.function.Consumer<View> host){menuHost=host;}

    private void addAction(LinearLayout menu,String label,Runnable action) {
        TextView row=new TextView(getContext());row.setText(label);row.setTextSize(13);
        row.setTextColor(HyperOsStyle.textPrimary(getContext()));
        row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        int inset=HyperOsStyle.dp(getContext(),16);
        row.setPaddingRelative(inset,0,inset,0);
        row.setOnClickListener(v->{menuHost.accept(null);action.run();});
        menu.addView(row,new LinearLayout.LayoutParams(-1,HyperOsStyle.dp(getContext(),44)));
    }

    void page(int page){this.page=page;}

    @android.annotation.SuppressLint("SetTextI18n")
    void selection(boolean active,int selected) {
        selecting=active;
        button.setIcon(active?IconButton.CHECK:IconButton.MORE_VERTICAL);
        String description=active?"完成多选，已选 "+selected+" 项":"内容操作";
        button.setContentDescription(description);button.setTooltipText(description);
        count.setText(active?String.valueOf(selected):"");
    }
}
