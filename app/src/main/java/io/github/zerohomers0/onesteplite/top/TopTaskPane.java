package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/** Tracks top task occupancy without drawing a cell, placeholder or admission target. */
final class TopTaskPane extends FrameLayout {
    private final View emptyHint;
    private boolean choosing=true;
    TopTaskPane(Context context,View hint) {
        super(context);setClipChildren(true);emptyHint=hint;
        hint.setVisibility(GONE);setVisibility(INVISIBLE);
    }
    void message(String text) {emptyHint.setContentDescription(text);}
    void occupied(boolean occupied) {
        choosing=!occupied;emptyHint.setVisibility(GONE);
    }
    boolean choosing() {return choosing;}
}
