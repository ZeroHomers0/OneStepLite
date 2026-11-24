package io.github.zerohomers0.onesteplite;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Standard selected-text entry keeps source apps' own selection and permission behavior. */
public final class TextDragActivity extends Activity {
    private boolean dragging;

    @Override protected void onStop(){super.onStop();if(!dragging)finish();}

    @Override protected void onCreate(Bundle state) {
        setTheme(HyperOsStyle.dark(this)?android.R.style.Theme_Material_Dialog_NoActionBar:android.R.style.Theme_Material_Light_Dialog_NoActionBar);
        super.onCreate(state);
        CharSequence selected=Intent.ACTION_PROCESS_TEXT.equals(getIntent().getAction())
                ?getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT):null;
        if(selected==null || selected.length()==0 || !ContentSharePolicy.validSize(1,selected.length())){finish();return;}
        String value=selected.toString();setTitle("拖曳到一步");
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        int inset=HyperOsStyle.dp(this,16);body.setPadding(inset,inset,inset,inset);body.setBackgroundColor(HyperOsStyle.page(this));
        TextView help=new TextView(this);help.setText("长按文字，拖到一步分享栏或小窗");help.setTextSize(12);
        help.setTextColor(HyperOsStyle.textSecondary(this));body.addView(help);
        TextView text=new TextView(this);text.setText(value);text.setTextSize(16);text.setPadding(0,inset,0,inset);
        text.setTextColor(HyperOsStyle.textPrimary(this));text.setContentDescription("选中文字，长按拖曳分享");
        ScrollView scroll=new ScrollView(this);scroll.addView(text);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView close=new TextView(this);close.setText("返回");close.setGravity(Gravity.CENTER);
        close.setTextColor(HyperOsStyle.textPrimary(this));close.setBackground(HyperOsStyle.controlButton(this,false,10));
        close.setOnClickListener(v->finish());body.addView(close,new LinearLayout.LayoutParams(-1,HyperOsStyle.dp(this,44)));
        text.setOnLongClickListener(v->{
            try {
                ClipData clip=ClipData.newPlainText("选中文字",value);ContentDragShadow shadow=new ContentDragShadow(v,1);shadow.mark(clip);
                dragging=v.startDragAndDrop(clip,shadow,null,View.DRAG_FLAG_GLOBAL);
                if(!dragging)ContentDragMotion.close();
                if(dragging)WorkspacePopupStyle.dragWindow(getWindow(),0,false);
                return dragging;
            } catch(RuntimeException unavailable){ContentDragMotion.close();return false;}
        });
        body.setOnDragListener((view,event)->{
            if(event.getAction()==DragEvent.ACTION_DRAG_ENDED && dragging) {
                ContentDragMotion.close();
                dragging=false;if(event.getResult())finish();else WorkspacePopupStyle.dragWindow(getWindow(),0,true);
            }
            return event.getAction()!=DragEvent.ACTION_DROP;
        });
        setContentView(body);getWindow().setLayout(Math.min(HyperOsStyle.dp(this,420),
                getResources().getDisplayMetrics().widthPixels-HyperOsStyle.dp(this,24)),HyperOsStyle.dp(this,340));
        setResult(RESULT_CANCELED);
    }
}
