package io.github.zerohomers0.onesteplite;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** Borrows only the host view's foreground and restores it if it is still ours. */
final class DropTargetHighlight implements AutoCloseable {
    private View view;
    private Drawable previous,owned;
    void show(View target) {
        if(view==target)return;close();if(target==null)return;
        GradientDrawable outline=new GradientDrawable();outline.setCornerRadius(HyperOsStyle.dp(target.getContext(),12));
        outline.setColor(0x123482ff);outline.setStroke(HyperOsStyle.dp(target.getContext(),2),HyperOsStyle.CONTROL_ACCENT);
        view=target;previous=target.getForeground();owned=outline;target.setForeground(outline);
    }
    @Override public void close(){if(view!=null && view.getForeground()==owned)view.setForeground(previous);view=null;previous=null;owned=null;}
}
