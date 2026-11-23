package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Page-specific actions keep unrelated controls out of the narrow content footer. */
final class ContentPanelActions extends LinearLayout {
    final TextView selection;
    private final TextView history,more;
    ContentPanelActions(Context context,Runnable select,Runnable clear,Runnable record,Runnable browse) {
        super(context);setGravity(Gravity.CENTER_VERTICAL);
        selection=button("多选",select);button("清除记录",clear);
        history=button("历史设置",record);more=button("更多文件",browse);
    }
    void page(int page){history.setVisibility(page==2?View.VISIBLE:View.GONE);more.setVisibility(page==1?View.VISIBLE:View.GONE);}
    private TextView button(String text,Runnable action) {
        TextView view=new TextView(getContext());view.setText(text);view.setTextSize(12);
        view.setTextColor(HyperOsStyle.textPrimary(getContext()));view.setGravity(Gravity.CENTER);
        view.setMinHeight(Math.round(HyperOsStyle.dp(getContext(),44)));view.setMaxLines(2);
        view.setBackground(HyperOsStyle.controlButton(getContext(),false,8));
        LayoutParams params=new LayoutParams(0,-1,1);
        int gap=Math.round(HyperOsStyle.dp(getContext(),3));params.setMargins(gap,gap,gap,gap);
        view.setOnClickListener(v->action.run());addView(view,params);return view;
    }
}
