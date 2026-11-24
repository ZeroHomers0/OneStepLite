package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.icu.text.BreakIterator;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;

/** Local word chips share the existing content drag path, without any OEM selection UI. */
final class WordSelectionPane extends LinearLayout {
    private final String source;
    private final BiPredicate<View,String> drag;
    private final List<int[]> spans=new ArrayList<>();
    private final List<TextView> chips=new ArrayList<>();
    private final BitSet selected=new BitSet();
    private final TextView result;
    private final FlowPane words;
    private boolean characters;
    private float downX,downY;
    private int slideAnchor;
    private boolean sliding;
    WordSelectionPane(Context context,String source,BiPredicate<View,String> drag) {
        super(context);this.source=source;this.drag=drag;setOrientation(VERTICAL);
        WorkspacePopupStyle.decorate(this,null);
        LinearLayout actions=new LinearLayout(context);
        TextView all=label("全选"),split=label("拆字"),reset=label("清空");
        all.setOnClickListener(v->{selected.set(0,spans.size());refresh();});
        reset.setOnClickListener(v->{selected.clear();refresh();});
        split.setOnClickListener(v->{characters=!characters;split.setText(characters?"分词":"拆字");segment();});
        for(TextView action:new TextView[]{all,split,reset}) {
            WorkspacePopupStyle.styleAction(action,false);
            LayoutParams cell=new LayoutParams(0,dp(44),1);cell.setMargins(dp(3),dp(3),dp(3),dp(3));actions.addView(action,cell);
        }
        addView(actions,new LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(context);scroll.setPadding(0,dp(12),0,0);scroll.setClipToPadding(false);
        words=new FlowPane(context);
        scroll.addView(words);addView(scroll,new LayoutParams(-1,0,1));
        result=label(source);result.setMaxLines(2);result.setTextSize(12);
        result.setEllipsize(android.text.TextUtils.TruncateAt.END);
        result.setOnLongClickListener(v->start(v));addView(result,new LayoutParams(-1,dp(56)));
        segment();
    }
    private void segment() {
        spans.clear();chips.clear();selected.clear();words.removeAllViews();
        BreakIterator iterator=characters?BreakIterator.getCharacterInstance(Locale.CHINA):BreakIterator.getWordInstance(Locale.CHINA);
        iterator.setText(source);int start=iterator.first();
        for(int end=iterator.next();end!=BreakIterator.DONE && spans.size()<1000;start=end,end=iterator.next()) {
            if(source.substring(start,end).trim().isEmpty())continue;
            spans.add(new int[]{start,end});
        }
        if(start<source.length() && spans.size()==1000)spans.add(new int[]{start,source.length()});
        for(int i=0;i<spans.size();i++) {
            int index=i;int[] span=spans.get(i);TextView chip=label(source.substring(span[0],span[1]));
            android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
            states.addState(new int[]{android.R.attr.state_selected},chipFill(true));
            states.addState(new int[]{},chipFill(false));chip.setBackground(states);
            chip.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_selected},new int[]{}},
                    new int[]{HyperOsStyle.CONTROL_ACCENT,HyperOsStyle.textPrimary(getContext())}));
            chip.setTextSize(13);chip.setMaxLines(2);chip.setOnClickListener(v->{selected.flip(index);refresh();});
            chip.setOnLongClickListener(v->{if(selected.isEmpty()){selected.set(index);refresh();}return start(v);});
            chip.setOnTouchListener((view,event)->touch(view,event,index));
            chip.setMinWidth(dp(36));chip.setMinHeight(dp(36));
            words.addView(chip,new android.view.ViewGroup.LayoutParams(-2,-2));chips.add(chip);
        }
        refresh();
    }
    private boolean start(View view) {
        String text=selected.isEmpty()?source:WordSelectionPolicy.selected(source,spans,selected);
        if(text.isEmpty())return false;
        view.setContentDescription(text);return drag.test(view,text);
    }
    private boolean touch(View view,android.view.MotionEvent event,int index) {
        int action=event.getActionMasked();
        if(action==android.view.MotionEvent.ACTION_DOWN) {
            downX=event.getRawX();downY=event.getRawY();slideAnchor=index;sliding=false;
        } else if(action==android.view.MotionEvent.ACTION_MOVE) {
            int slop=android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if(Math.abs(event.getRawX()-downX)>slop && Math.abs(event.getRawX()-downX)>Math.abs(event.getRawY()-downY)) {
                sliding=true;view.cancelLongPress();view.getParent().requestDisallowInterceptTouchEvent(true);
            }
            if(sliding) {
                int[] location=new int[2];
                for(int i=0;i<chips.size();i++) {
                    TextView chip=chips.get(i);chip.getLocationOnScreen(location);
                    if(event.getRawX()>=location[0] && event.getRawX()<location[0]+chip.getWidth()
                            && event.getRawY()>=location[1] && event.getRawY()<location[1]+chip.getHeight()) {
                        selected.clear();selected.set(Math.min(slideAnchor,i),Math.max(slideAnchor,i)+1);refresh();break;
                    }
                }
                return true;
            }
        } else if(action==android.view.MotionEvent.ACTION_UP || action==android.view.MotionEvent.ACTION_CANCEL) {
            view.getParent().requestDisallowInterceptTouchEvent(false);
            if(sliding){sliding=false;view.setPressed(false);return true;}
        }
        return false;
    }
    private void refresh() {
        for(int i=0;i<chips.size();i++)chips.get(i).setSelected(selected.get(i));
        result.setText(selected.isEmpty()?source:WordSelectionPolicy.selected(source,spans,selected));
    }
    private android.graphics.drawable.Drawable chipFill(boolean active) {
        android.graphics.drawable.GradientDrawable fill=new android.graphics.drawable.GradientDrawable();
        fill.setCornerRadius(dp(6));
        fill.setColor(active?(HyperOsStyle.dark(getContext())?0x303482ff:0x183482ff):android.graphics.Color.TRANSPARENT);
        fill.setStroke(dp(1),active?HyperOsStyle.CONTROL_ACCENT:HyperOsStyle.dark(getContext())?0xff586272:0xffc7d1df);
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x183482ff),fill,null);
    }
    private TextView label(String text) {
        TextView view=new TextView(getContext());view.setText(text);view.setTextSize(15);
        view.setTextColor(HyperOsStyle.textPrimary(getContext()));view.setGravity(android.view.Gravity.CENTER);
        view.setPadding(dp(6),dp(4),dp(6),dp(4));return view;
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    /** Word-width chips wrap naturally without stretching punctuation across a grid cell. */
    private static final class FlowPane extends android.view.ViewGroup {
        private final int gap;
        FlowPane(Context context){super(context);gap=Math.round(6*getResources().getDisplayMetrics().density);}
        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            int width=MeasureSpec.getSize(widthSpec),x=0,y=0,row=0;
            for(int i=0;i<getChildCount();i++) {
                View child=getChildAt(i);child.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
                if(x>0 && x+child.getMeasuredWidth()>width){x=0;y+=row+gap;row=0;}
                x+=child.getMeasuredWidth()+gap;row=Math.max(row,child.getMeasuredHeight());
            }
            setMeasuredDimension(width,resolveSize(y+row,heightSpec));
        }
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
            int width=right-left,x=0,y=0,row=0;boolean rtl=getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
            for(int i=0;i<getChildCount();i++) {
                View child=getChildAt(i);int w=child.getMeasuredWidth(),h=child.getMeasuredHeight();
                if(x>0 && x+w>width){x=0;y+=row+gap;row=0;}
                int start=rtl?width-x-w:x;child.layout(start,y,start+w,y+h);x+=w+gap;row=Math.max(row,h);
            }
        }
    }
}
