package io.github.zerohomers0.onesteplite;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Standard selected-text entry keeps source apps' own selection and permission behavior. */
public final class TextDragActivity extends Activity {
    private boolean dragging;
    private float pointerX,pointerY;
    private boolean pointerActive;

    @Override protected void onStop(){super.onStop();if(!dragging)finish();}

    @Override protected void onCreate(Bundle state) {
        WorkspaceOptions options=WorkspaceSettingsStore.read(this);
        HyperOsStyle.theme(options.themeMode);
        setTheme(HyperOsStyle.dark(this)?android.R.style.Theme_Material_Dialog_NoActionBar:android.R.style.Theme_Material_Light_Dialog_NoActionBar);
        super.onCreate(state);
        if(!options.dragShareEnabled){
            android.widget.Toast.makeText(this,"拖曳分享已关闭",android.widget.Toast.LENGTH_SHORT).show();
            finish();return;
        }
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        CharSequence selected=Intent.ACTION_PROCESS_TEXT.equals(getIntent().getAction())
                ?getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT):null;
        if(selected==null || selected.length()==0 || !ContentSharePolicy.validSize(1,selected.length())){finish();return;}
        String value=selected.toString();setTitle("拖曳到一步");
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        WorkspacePopupStyle.decorate(body,null);WorkspacePopupStyle.heading(body,"拖曳到一步",this::finish);
        int inset=HyperOsStyle.dp(this,8);
        TextView text=new TextView(this);text.setText(value);text.setTextSize(WorkspacePopupStyle.BODY_SP);
        text.setPadding(inset,inset,inset,inset);
        text.setTextColor(HyperOsStyle.textPrimary(this));text.setContentDescription("选中文字，长按拖曳分享");
        text.setOnTouchListener((view,event)->{
            int action=event.getActionMasked();
            pointerActive=event.getPointerCount()==1 && action!=android.view.MotionEvent.ACTION_UP && action!=android.view.MotionEvent.ACTION_CANCEL;
            if(pointerActive){pointerX=event.getRawX();pointerY=event.getRawY();}return false;
        });
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(text);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        text.setOnLongClickListener(v->{
            if(!pointerActive || !WorkspaceSettingsStore.read(this).dragShareEnabled)return false;
            try {
                ClipData clip=ClipData.newPlainText("选中文字",value);ContentDragShadow shadow=new ContentDragShadow(v,1);shadow.mark(clip);
                android.os.PersistableBundle metadata=clip.getDescription().getExtras();
                metadata.putDouble("onesteplite_module_pointer_x",pointerX);metadata.putDouble("onesteplite_module_pointer_y",pointerY);
                clip.getDescription().setExtras(metadata);
                dragging=v.startDragAndDrop(clip,shadow,null,View.DRAG_FLAG_GLOBAL);
                if(!dragging)ContentDragMotion.close();
                if(dragging)WorkspacePopupStyle.dragWindow(getWindow(),0,false);
                return dragging;
            } catch(RuntimeException unavailable){ContentDragMotion.close();return false;}
        });
        body.setOnDragListener((view,event)->{
            if(event.getAction()==DragEvent.ACTION_DRAG_ENDED && dragging) {
                ContentDragMotion.close();
                dragging=false;if(event.getResult())finish();else WorkspacePopupStyle.dragWindow(getWindow(),Math.min(100,HyperOsStyle.dp(this,28)),true);
            }
            return event.getAction()!=DragEvent.ACTION_DROP;
        });
        setContentView(body);WorkspacePopupStyle.size(getWindow(),this);
        setResult(RESULT_CANCELED);
    }
}
