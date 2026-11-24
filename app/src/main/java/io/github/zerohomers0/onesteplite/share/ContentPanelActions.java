package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Page-specific actions keep unrelated controls out of the narrow content footer. */
final class ContentPanelActions extends LinearLayout {
    final TextView selection;
    private final TextView history,more,clear;
    ContentPanelActions(Context context,Runnable select,Runnable clear,Runnable record,Runnable browse) {
        super(context);setGravity(Gravity.CENTER_VERTICAL);
        selection=button("多选",select);this.clear=button("清除记录",clear);
        history=button("历史设置",record);more=button("更多文件",browse);
    }
    void page(int page){history.setVisibility(page==2?View.VISIBLE:View.GONE);more.setVisibility(page==1?View.VISIBLE:View.GONE);
        clear.setText(page==3?"移除暂存":"清除记录");}
    private TextView button(String text,Runnable action) {
        TextView view=WorkspacePopupStyle.action(getContext(),text,false,action);
        addView(view,WorkspacePopupStyle.actionCell(getContext()));return view;
    }
}
