package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.function.Consumer;

/** A bounded settings row keeps the toggle's hit region separate from the sheet's empty space. */
final class ClipboardOptionsPane extends LinearLayout {
    ClipboardOptionsPane(Context context,boolean enabled,Consumer<Boolean> changed,Runnable close) {
        super(context);setOrientation(VERTICAL);WorkspacePopupStyle.decorate(this,null);
        TextView title=label("剪贴板历史",16);title.setTypeface(null,android.graphics.Typeface.BOLD);
        addView(title,new LayoutParams(-1,HyperOsStyle.dp(context,40)));
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=label("记录复制的文字",13);row.addView(name,new LayoutParams(0,-2,1));
        Switch toggle=WorkspaceSwitch.create(context,"记录复制的文字",enabled);
        toggle.setOnCheckedChangeListener((button,value)->changed.accept(value));
        row.addView(toggle,new LayoutParams(-2,HyperOsStyle.dp(context,44)));
        addView(row,new LayoutParams(-1,HyperOsStyle.dp(context,56)));
        TextView detail=label("仅在工作区打开时记录；保留7天，最多64条。",12);
        detail.setTextColor(HyperOsStyle.textSecondary(context));addView(detail,new LayoutParams(-1,-2));
        android.view.View spacer=new android.view.View(context);addView(spacer,new LayoutParams(-1,0,1));
        TextView done=WorkspacePopupStyle.action(context,"完成",false,close);
        addView(done,new LayoutParams(-1,HyperOsStyle.dp(context,44)));
    }
    private TextView label(String text,int size) {
        TextView view=new TextView(getContext());view.setText(text);view.setTextSize(size);
        view.setTextColor(HyperOsStyle.textPrimary(getContext()));return view;
    }
}
