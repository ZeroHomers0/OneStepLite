package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native file row shared by the workspace and offline component screenshot harness. */
final class RecentFileRow extends LinearLayout {
    RecentFileRow(Context context,ContentEntry entry) {
        super(context);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(10),dp(7),dp(10),dp(7));setMinimumHeight(dp(60));
        ContentRowStyle.apply(this);
        String type=RecentFilePolicy.type(entry.label,entry.mime);
        TextView icon=new TextView(context);icon.setText(type.length()>4?"FILE":type);
        icon.setTextSize(10);icon.setTypeface(Typeface.DEFAULT,Typeface.BOLD);icon.setGravity(Gravity.CENTER);
        icon.setTextColor(HyperOsStyle.CONTROL_ACCENT);
        android.graphics.drawable.GradientDrawable badge=new android.graphics.drawable.GradientDrawable();
        badge.setColor(HyperOsStyle.controlFill(context,false));badge.setCornerRadius(dp(8));icon.setBackground(badge);
        addView(icon,new LayoutParams(dp(30),dp(36)));
        LinearLayout lines=new LinearLayout(context);lines.setOrientation(VERTICAL);
        LayoutParams content=new LayoutParams(0,-2,1);content.setMarginStart(dp(12));addView(lines,content);
        TextView name=new TextView(context);name.setText(entry.label);name.setTextSize(WorkspacePopupStyle.BODY_SP);
        name.setTextColor(HyperOsStyle.textPrimary(context));name.setMaxLines(1);name.setIncludeFontPadding(false);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);lines.addView(name);
        String time=entry.addedAt<=0?"":RecentFilePolicy.age(entry.addedAt,System.currentTimeMillis());
        TextView metadata=new TextView(context);metadata.setText(metadata(type,entry.bytes,time));metadata.setTextSize(WorkspacePopupStyle.META_SP);
        metadata.setTextColor(HyperOsStyle.textSecondary(context));metadata.setPadding(0,dp(5),0,0);
        metadata.setMaxLines(1);metadata.setEllipsize(android.text.TextUtils.TruncateAt.END);lines.addView(metadata);
        setContentDescription(entry.label+"，"+metadata.getText()+"，长按拖曳分享");
    }
    private static String metadata(String type,long bytes,String time) {
        return type+" · "+RecentFilePolicy.size(bytes)+(time.isEmpty()?"":" · "+time);
    }
    private int dp(int value){return Math.round(HyperOsStyle.dp(getContext(),value));}
}
