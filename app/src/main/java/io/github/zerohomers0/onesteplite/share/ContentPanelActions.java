package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

/** Page actions occupy one slot beside the content tabs. */
final class ContentPanelActions extends FrameLayout {
    private final IconButton button;
    private final TextView count;
    private int page;
    private boolean selecting;

    ContentPanelActions(Context context,Runnable select,Runnable clear,Runnable record,Runnable browse) {
        super(context);
        button=new IconButton(context,IconButton.MORE,"内容操作");
        addView(button,new LayoutParams(-1,-1));
        count=new TextView(context);count.setTextColor(HyperOsStyle.CONTROL_ACCENT);
        count.setTextSize(9);count.setGravity(Gravity.CENTER);count.setIncludeFontPadding(false);
        LayoutParams badge=new LayoutParams(-1,-2,Gravity.BOTTOM);
        badge.bottomMargin=HyperOsStyle.dp(context,1);
        addView(count,badge);
        button.setTooltipText("内容操作");
        button.setOnClickListener(v->{
            if(selecting){select.run();return;}
            PopupMenu menu=new PopupMenu(context,button,Gravity.END);
            menu.getMenu().add(0,1,0,"多选");
            if(page==1)menu.getMenu().add(0,2,1,"更多文件");
            if(page==2)menu.getMenu().add(0,3,1,"历史设置");
            menu.getMenu().add(0,4,2,page==3?"移除暂存":"清除记录");
            menu.setOnMenuItemClickListener(item->{
                switch(item.getItemId()) {
                    case 1: select.run();return true;
                    case 2: browse.run();return true;
                    case 3: record.run();return true;
                    case 4: clear.run();return true;
                    default: return false;
                }
            });
            menu.show();
        });
    }

    void page(int page){this.page=page;}

    @android.annotation.SuppressLint("SetTextI18n")
    void selection(boolean active,int selected) {
        selecting=active;
        button.setIcon(active?IconButton.CHECK:IconButton.MORE);
        String description=active?"完成多选，已选 "+selected+" 项":"内容操作";
        button.setContentDescription(description);button.setTooltipText(description);
        count.setText(active?String.valueOf(selected):"");
    }
}
