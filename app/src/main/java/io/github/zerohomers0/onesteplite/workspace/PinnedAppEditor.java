package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** The selected shortcuts, using the rail's icons and the picker's existing glass palette. */
final class PinnedAppEditor extends HorizontalScrollView {
    private final LinearLayout row;
    private final List<QuickApp> apps;
    private final LinkedHashSet<String> chosen;
    private final Runnable changed;
    private int dragScroll;
    private final Runnable scrollDrag=new Runnable() {
        @Override public void run() {
            if(dragScroll==0 || !isAttachedToWindow())return;
            scrollBy(dragScroll,0);postOnAnimation(this);
        }
    };

    PinnedAppEditor(Context context, List<QuickApp> apps, LinkedHashSet<String> chosen, Runnable changed) {
        super(context); this.apps=apps; this.chosen=chosen; this.changed=changed;
        setHorizontalScrollBarEnabled(false);
        row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row,new LayoutParams(-2,-1));refresh();
    }

    void refresh() {
        row.removeAllViews();
        if(chosen.isEmpty()) {
            TextView empty=new TextView(getContext());empty.setText("选择应用后，长按图标拖动排序");
            empty.setTextSize(12);empty.setTextColor(HyperOsStyle.textSecondary(getContext()));
            row.addView(empty,new LinearLayout.LayoutParams(-2,-1));return;
        }
        for(String key:chosen)for(QuickApp app:apps)if(key.equals(app.component.flattenToString())) {
            row.addView(tile(app,key),new LinearLayout.LayoutParams(dp(52),dp(52)));break;
        }
    }

    @Override public boolean dispatchDragEvent(DragEvent event) {
        if(event.getAction()==DragEvent.ACTION_DRAG_LOCATION) {
            int direction=event.getX()<dp(32)?-1:event.getX()>getWidth()-dp(32)?1:0;
            if(direction!=Integer.signum(dragScroll)) {
                removeCallbacks(scrollDrag);dragScroll=direction*dp(6);
                if(dragScroll!=0)postOnAnimation(scrollDrag);
            }
        } else if(event.getAction()==DragEvent.ACTION_DROP || event.getAction()==DragEvent.ACTION_DRAG_ENDED) {
            dragScroll=0;removeCallbacks(scrollDrag);
        }
        return super.dispatchDragEvent(event);
    }

    @Override protected void onDetachedFromWindow() {
        dragScroll=0;removeCallbacks(scrollDrag);super.onDetachedFromWindow();
    }

    private View tile(QuickApp app, String key) {
        ImageView tile=new ImageView(getContext());tile.setPadding(dp(7),dp(7),dp(7),dp(7));
        Drawable.ConstantState state=app.icon==null?null:app.icon.getConstantState();
        tile.setImageDrawable(state==null?app.icon:state.newDrawable().mutate());
        tile.setBackground(HyperOsStyle.glassButton(getContext(),16,false));
        tile.setContentDescription(app.label+"，固定应用第"+(new ArrayList<>(chosen).indexOf(key)+1)+"位，长按拖动排序");
        tile.setTooltipText(app.label);tile.setFocusable(true);
        tile.setOnLongClickListener(view->{
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return view.startDragAndDrop(ClipData.newPlainText("shortcut",key),new View.DragShadowBuilder(view),key,0);
        });
        tile.setOnDragListener((view,event)->{
            Object local=event.getLocalState();
            if(!(local instanceof String) || !chosen.contains(local))return false;
            switch(event.getAction()) {
                case DragEvent.ACTION_DRAG_ENTERED:view.setAlpha(.55f);break;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:view.setAlpha(1f);break;
                case DragEvent.ACTION_DROP:move((String)local,key);break;
                default:break;
            }
            return true;
        });
        tile.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host,info);
                int index=new ArrayList<>(chosen).indexOf(key);
                if(index>0)info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,"向前移动"));
                if(index<chosen.size()-1)info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,"向后移动"));
            }
            @Override public boolean performAccessibilityAction(View host,int action,Bundle args) {
                List<String> order=new ArrayList<>(chosen);int index=order.indexOf(key);
                int target=index+(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD?-1:1);
                if((action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD || action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        && index>=0 && target>=0 && target<order.size()) {move(key,order.get(target));return true;}
                return super.performAccessibilityAction(host,action,args);
            }
        });
        return tile;
    }

    private void move(String key,String target) {
        List<String> order=QuickAppOrder.move(new ArrayList<>(chosen),key,target);
        chosen.clear();chosen.addAll(order);refresh();changed.run();
    }

    private int dp(float value) {return Math.round(HyperOsStyle.dp(getContext(),value));}
}
