package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/** Same real empty hint as the side cells; occupied tasks have no host overlay. */
final class TopTaskPane extends FrameLayout {
    private final View emptyHint;
    private boolean choosing=true;
    private IconButton exit;
    TopTaskPane(Context context,View hint) {
        super(context);setClipChildren(true);emptyHint=hint;
        addView(hint,new FrameLayout.LayoutParams(-1,-1));setVisibility(INVISIBLE);
    }
    /** A persistent exit plate over the live task, so the face never traps the user. */
    void exitAction(Runnable action) {
        if (exit != null || action == null) return;
        Context context=getContext();
        exit=new IconButton(context,IconButton.CLOSE,"退出顶栏，应用回到主窗");
        exit.setTooltipText("退出顶栏，应用回到主窗");
        exit.setBackground(HyperOsStyle.controlButton(context,false,20f));
        exit.setOnClickListener(v->action.run());
        int size=HyperOsStyle.dp(context,40f),margin=HyperOsStyle.dp(context,12f);
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(size,size);
        params.gravity=Gravity.END|Gravity.BOTTOM;
        params.rightMargin=margin;params.bottomMargin=margin;
        addView(exit,params);
        exit.setVisibility(choosing?INVISIBLE:VISIBLE);
    }
    void message(String text) {emptyHint.setContentDescription(text);}
    void occupied(boolean occupied) {
        choosing=!occupied;emptyHint.setVisibility(occupied?INVISIBLE:VISIBLE);
        if(!occupied)emptyHint.setContentDescription("空白窗口，点按添加主屏应用");
        if(exit!=null)exit.setVisibility(occupied?VISIBLE:INVISIBLE);
    }
    boolean choosing() {return choosing;}
}
