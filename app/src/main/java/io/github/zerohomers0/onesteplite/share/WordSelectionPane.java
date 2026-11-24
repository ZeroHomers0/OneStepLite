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
    private final FlowPane words;
    private final ScrollView scroll;
    private final TextView all,mode,copy,status;
    private final LinearLayout statusBar;
    private final BitSet beforeSlide=new BitSet();
    private final int[] screenPosition=new int[2];
    private final Runnable edgeScroll=this::scrollSelection;
    private boolean characters,sliding,slideAdds,settled;
    private float downX,downY,pointerX,pointerY;
    private int slideAnchor,slideEnd=-1,edgeStep;

    WordSelectionPane(Context context,String source,BiPredicate<View,String> drag) {
        super(context);this.source=source;this.drag=drag;setOrientation(VERTICAL);
        WorkspacePopupStyle.decorate(this,null);
        status=label("");status.setTextSize(12);status.setGravity(android.view.Gravity.START);
        status.setTextColor(HyperOsStyle.textSecondary(context));
        statusBar=new LinearLayout(context);statusBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        statusBar.addView(status,new LayoutParams(0,-2,1));
        addView(statusBar,new LayoutParams(-1,-2));
        scroll=new WordScroll(context);scroll.setPadding(0,dp(6),0,dp(6));scroll.setClipToPadding(false);
        words=new FlowPane(context);
        scroll.addView(words);addView(scroll,new LayoutParams(-1,0,1));

        LinearLayout actions=new LinearLayout(context);
        all=WorkspacePopupStyle.action(context,"全选",false,()->{
            if(selected.cardinality()==spans.size())selected.clear();else selected.set(0,spans.size());
            refresh();
        });
        mode=WorkspacePopupStyle.action(context,"拆字",false,()->{characters=!characters;segment();});
        copy=WorkspacePopupStyle.action(context,"复制",true,this::copySelection);
        for(TextView action:new TextView[]{all,mode,copy})
            actions.addView(action,WorkspacePopupStyle.actionCell(context));
        addView(actions,new LayoutParams(-1,-2));
        segment();
    }

    void addHistoryActions(Runnable pin,Runnable delete) {
        IconButton more=new IconButton(getContext(),IconButton.MORE,"剪贴板记录操作");
        more.setTooltipText("剪贴板记录操作");
        statusBar.addView(more,new LayoutParams(dp(44),dp(44)));
        more.setOnClickListener(v->{
            android.widget.PopupMenu menu=new android.widget.PopupMenu(getContext(),more,android.view.Gravity.END);
            menu.getMenu().add(0,1,0,"固定／取消固定");
            menu.getMenu().add(0,2,1,"删除记录");
            menu.setOnMenuItemClickListener(item->{
                if(item.getItemId()==1)pin.run();else delete.run();
                return true;
            });
            menu.show();
        });
    }

    private void copySelection() {
        String text=WordSelectionPolicy.selected(source,spans,selected);
        if(text.isEmpty())return;
        android.content.ClipboardManager clipboard=getContext().getSystemService(android.content.ClipboardManager.class);
        if(clipboard==null)return;
        try {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("选中文字",text));
            android.widget.Toast.makeText(getContext(),"已复制选区",android.widget.Toast.LENGTH_SHORT).show();
        }catch(RuntimeException unavailable){
            android.widget.Toast.makeText(getContext(),"无法复制选区",android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void segment() {
        BitSet selectedOffsets=new BitSet();
        for(int i=selected.nextSetBit(0);i>=0;i=selected.nextSetBit(i+1)) {
            int[] span=spans.get(i);selectedOffsets.set(span[0],span[1]);
        }
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
            int overlap=selectedOffsets.nextSetBit(span[0]);
            if(overlap>=0 && overlap<span[1])selected.set(i);
            android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
            states.addState(new int[]{android.R.attr.state_selected},chipFill(true));
            states.addState(new int[]{},chipFill(false));chip.setBackground(states);
            chip.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_selected},new int[]{}},
                    new int[]{HyperOsStyle.CONTROL_ACCENT,HyperOsStyle.textPrimary(getContext())}));
            chip.setTextSize(14);chip.setMaxLines(2);chip.setEllipsize(android.text.TextUtils.TruncateAt.END);chip.setOnClickListener(v->{selected.flip(index);refresh();});
            chip.setOnLongClickListener(v->{
                BitSet previous=(BitSet)selected.clone();
                if(!selected.get(index)){selected.clear();selected.set(index);}
                if(start(v)) {settled=true;refresh();return true;}
                selected.clear();selected.or(previous);refresh();return false;
            });
            chip.setOnTouchListener((view,event)->touch(view,event,index));
            chip.setMinWidth(dp(36));chip.setMinHeight(dp(36));
            words.addView(chip,new android.view.ViewGroup.LayoutParams(-2,-2));chips.add(chip);
        }
        refresh();
    }
    private boolean start(View view) {
        String text=selected.isEmpty()?source:WordSelectionPolicy.selected(source,spans,selected);
        if(text.isEmpty())return false;
        return drag.test(view,text);
    }
    private boolean touch(View view,android.view.MotionEvent event,int index) {
        int action=event.getActionMasked();
        if(action==android.view.MotionEvent.ACTION_DOWN) {
            stopSlide();downX=event.getRawX();downY=event.getRawY();slideAnchor=index;settled=false;
        } else if(action==android.view.MotionEvent.ACTION_MOVE && !settled) {
            pointerX=event.getRawX();pointerY=event.getRawY();
            float dx=Math.abs(pointerX-downX),dy=Math.abs(pointerY-downY);
            int slop=android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if(!sliding && Math.max(dx,dy)>slop) {
                view.cancelLongPress();view.setPressed(false);
                if(dx<=dy){settled=true;return false;}
                sliding=true;slideAdds=!selected.get(index);slideEnd=-1;
                beforeSlide.clear();beforeSlide.or(selected);
                view.getParent().requestDisallowInterceptTouchEvent(true);
            }
            if(sliding){extendSelection();updateEdge();return true;}
        } else if(action==android.view.MotionEvent.ACTION_UP || action==android.view.MotionEvent.ACTION_CANCEL
                || action==android.view.MotionEvent.ACTION_POINTER_DOWN) {
            boolean consume=sliding || settled || action==android.view.MotionEvent.ACTION_POINTER_DOWN;
            stopSlide();
            if(consume){view.cancelLongPress();view.setPressed(false);settled=true;}
            return consume;
        }
        return false;
    }

    private void extendSelection() {
        scroll.getLocationOnScreen(screenPosition);
        float visibleY=Math.max(screenPosition[1]+scroll.getPaddingTop(),
                Math.min(pointerY,screenPosition[1]+scroll.getHeight()-scroll.getPaddingBottom()-1));
        words.getLocationOnScreen(screenPosition);
        float x=pointerX-screenPosition[0],y=visibleY-screenPosition[1];
        int end=-1;float nearest=Float.MAX_VALUE;
        for(int i=0;i<chips.size();i++) {
            View chip=chips.get(i);
            if(y<chip.getTop() || y>=chip.getBottom())continue;
            float distance=Math.max(0,Math.max(chip.getLeft()-x,x-chip.getRight()));
            if(distance<nearest){end=i;nearest=distance;}
        }
        if(end<0 || end==slideEnd)return;
        slideEnd=end;selected.clear();selected.or(beforeSlide);
        selected.set(Math.min(slideAnchor,end),Math.max(slideAnchor,end)+1,slideAdds);
        refresh();
    }

    private void updateEdge() {
        scroll.getLocationOnScreen(screenPosition);
        float y=pointerY-screenPosition[1];int band=dp(30),step=dp(9);
        int next=y<band?-step:y>scroll.getHeight()-band?step:0;
        if(edgeStep==next)return;
        removeCallbacks(edgeScroll);edgeStep=next;
        if(next!=0)postOnAnimation(edgeScroll);
    }

    private void scrollSelection() {
        if(!sliding || edgeStep==0)return;
        int previous=scroll.getScrollY();scroll.scrollBy(0,edgeStep);
        if(scroll.getScrollY()==previous){edgeStep=0;return;}
        extendSelection();postOnAnimation(edgeScroll);
    }

    private void stopSlide() {
        removeCallbacks(edgeScroll);edgeStep=0;
        if(sliding)words.requestDisallowInterceptTouchEvent(false);
        sliding=false;slideEnd=-1;
    }

    @Override protected void onDetachedFromWindow() {
        stopSlide();super.onDetachedFromWindow();
    }

    @android.annotation.SuppressLint("SetTextI18n")
    private void refresh() {
        for(int i=0;i<chips.size();i++)chips.get(i).setSelected(selected.get(i));
        int count=selected.cardinality();
        all.setText(count>0 && count==spans.size()?"取消全选":"全选");
        mode.setText(characters?"分词":"拆字");
        copy.setEnabled(count>0);copy.setAlpha(count>0?1f:.42f);
        status.setText(count==0?"点按或横滑选词，长按拖出":"已选 "+count+" 项 · 长按拖出");
    }
    private android.graphics.drawable.Drawable chipFill(boolean active) {
        android.graphics.drawable.GradientDrawable fill=new android.graphics.drawable.GradientDrawable();
        fill.setCornerRadius(dp(9));
        fill.setColor(active?(HyperOsStyle.dark(getContext())?0x303482ff:0x183482ff):HyperOsStyle.controlFill(getContext(),false));
        if(active)fill.setStroke(dp(1),HyperOsStyle.CONTROL_ACCENT);
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x183482ff),fill,null);
    }
    private TextView label(String text) {
        TextView view=new TextView(getContext());view.setText(text);view.setTextSize(15);
        view.setTextColor(HyperOsStyle.textPrimary(getContext()));view.setGravity(android.view.Gravity.CENTER);
        view.setPadding(dp(6),dp(4),dp(6),dp(4));return view;
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static final class WordScroll extends ScrollView {
        private float downX,downY;
        WordScroll(Context context){super(context);}

        @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
            if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN) {
                downX=event.getX();downY=event.getY();
            } else if(event.getActionMasked()==android.view.MotionEvent.ACTION_MOVE) {
                float dx=Math.abs(event.getX()-downX),dy=Math.abs(event.getY()-downY);
                if(dx>dy && dx>android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop())return false;
            }
            return super.onInterceptTouchEvent(event);
        }
    }

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
