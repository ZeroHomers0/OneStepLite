package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A compact clipboard item; the complete payload stays in ContentEntry, never in the label. */
final class ClipboardRow extends LinearLayout {
    ClipboardRow(Context context,ContentEntry entry) {
        super(context);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(10),dp(7),dp(10),dp(7));ContentRowStyle.apply(this);
        if(entry.thumbnail!=null) {
            ImageView image=new ImageView(context);image.setImageBitmap(entry.thumbnail);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(image,new LayoutParams(dp(30),dp(36)));
        } else {
            TextView icon=new TextView(context);icon.setText(entry.text!=null?"文":"文件");icon.setTextSize(11);
            icon.setTextColor(HyperOsStyle.CONTROL_ACCENT);icon.setGravity(Gravity.CENTER);
            icon.setBackground(HyperOsStyle.controlButton(context,false,8));addView(icon,new LayoutParams(dp(30),dp(36)));
        }
        TextView text=new TextView(context);text.setText(entry.label);text.setTextSize(WorkspacePopupStyle.BODY_SP);
        text.setTextColor(HyperOsStyle.textPrimary(context));text.setMaxLines(2);text.setIncludeFontPadding(false);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout body=new LinearLayout(context);body.setOrientation(VERTICAL);body.addView(text);
        if(entry.addedAt>0) {
            TextView time=new TextView(context);
            time.setText(RecentFilePolicy.age(entry.addedAt,System.currentTimeMillis()));
            time.setTextColor(HyperOsStyle.textSecondary(context));time.setTextSize(WorkspacePopupStyle.META_SP);
            time.setIncludeFontPadding(false);body.addView(time);
        }
        LayoutParams label=new LayoutParams(0,-2,1);label.setMarginStart(dp(10));addView(body,label);
        setContentDescription(entry.label+"，点击选词，长按拖曳全文");
    }
    private int dp(int value){return HyperOsStyle.dp(getContext(),value);}
}
