package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.graphics.drawable.StateListDrawable;
import android.widget.Switch;

/** Shared trailing toggle for settings and shortcut management. */
final class WorkspaceSwitch {
    private WorkspaceSwitch(){}
    static Switch create(Context context,String label,boolean checked) {
        Switch toggle=new Switch(context);toggle.setText("");toggle.setShowText(false);
        toggle.setContentDescription(label);toggle.setSplitTrack(false);toggle.setSwitchMinWidth(dp(context,49));
        toggle.setThumbDrawable(new Drawable(){
            private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            {paint.setColor(0xffffffff);}
            @Override public void draw(Canvas canvas){Rect box=getBounds();
                canvas.drawCircle(box.exactCenterX(),box.exactCenterY(),Math.min(box.width(),box.height())/2f,paint);}
            @Override public int getIntrinsicWidth(){return dp(context,20);}
            @Override public int getIntrinsicHeight(){return dp(context,28);}
            @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
            @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
            @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        });
        StateListDrawable tracks=new StateListDrawable();
        tracks.addState(new int[]{android.R.attr.state_checked},track(context,HyperOsStyle.CONTROL_ACCENT));
        tracks.addState(new int[]{},track(context,HyperOsStyle.dark(context)?0xff42454e:0xffd7d9df));
        toggle.setTrackDrawable(tracks);toggle.setThumbTintList(null);toggle.setTrackTintList(null);
        toggle.setChecked(checked);return toggle;
    }
    private static Drawable track(Context context,int color) {
        float radius=dp(context,14);
        ShapeDrawable track=new ShapeDrawable(new RoundRectShape(new float[]{radius,radius,radius,radius,radius,radius,radius,radius},null,null));
        track.getPaint().setColor(color);track.setPadding(dp(context,4),0,dp(context,4),0);
        track.setIntrinsicWidth(dp(context,49));track.setIntrinsicHeight(dp(context,28));return track;
    }
    private static int dp(Context context,int value){return HyperOsStyle.dp(context,value);}
}
