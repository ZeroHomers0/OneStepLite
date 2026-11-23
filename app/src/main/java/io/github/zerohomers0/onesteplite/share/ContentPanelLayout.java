package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Shared native panel shell; preview harness renders these same production widgets. */
final class ContentPanelLayout extends LinearLayout {
    final List<TextView> tabs=new ArrayList<>();
    final ScrollView scroll;
    final GridLayout grid;
    ContentPanelLayout(Context context,IntConsumer select) {
        super(context);setOrientation(VERTICAL);WorkspacePopupStyle.decorate(this,null);
        LinearLayout header=new LinearLayout(context);
        header.setPadding(dp(3),dp(3),dp(3),dp(3));
        GradientDrawable track=new GradientDrawable();track.setColor(HyperOsStyle.controlFill(context,false));
        track.setCornerRadius(dp(10));header.setBackground(track);
        String[] names={"近期图片","最近文件","剪贴板"};
        for(int i=0;i<names.length;i++) {
            final int index=i;TextView tab=new TextView(context);tab.setText(names[i]);tab.setTextSize(13);
            tab.setMinHeight(dp(42));tab.setMaxLines(1);tab.setIncludeFontPadding(false);
            tab.setGravity(Gravity.CENTER);tab.setTextColor(HyperOsStyle.textPrimary(context));
            tab.setOnClickListener(v->select.accept(index));tabs.add(tab);
            LayoutParams cell=new LayoutParams(0,-2,1);header.addView(tab,cell);
        }
        addView(header);
        scroll=new ScrollView(context);scroll.setFillViewport(true);
        scroll.setPadding(0,dp(12),0,0);scroll.setClipToPadding(false);
        grid=new GridLayout(context);grid.setColumnCount(3);scroll.addView(grid);
        addView(scroll,new LayoutParams(-1,0,1));
    }
    void select(int page) {
        for(int i=0;i<tabs.size();i++) {
            tabs.get(i).setSelected(i==page);
            GradientDrawable face=new GradientDrawable();
            face.setColor(i==page?HyperOsStyle.page(getContext()):android.graphics.Color.TRANSPARENT);
            face.setCornerRadius(dp(8));
            tabs.get(i).setBackground(new RippleDrawable(ColorStateList.valueOf(
                    (HyperOsStyle.CONTROL_ACCENT&0x00ffffff)|0x1c000000),face,null));
            tabs.get(i).setTypeface(Typeface.DEFAULT,i==page?Typeface.BOLD:Typeface.NORMAL);
            tabs.get(i).setTextColor(i==page?HyperOsStyle.CONTROL_ACCENT:HyperOsStyle.textSecondary(getContext()));
        }
    }
    private int dp(int value){return Math.round(HyperOsStyle.dp(getContext(),value));}
}
