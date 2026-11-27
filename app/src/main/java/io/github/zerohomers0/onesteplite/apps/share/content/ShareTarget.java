package io.github.zerohomers0.onesteplite;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

/** Worker-loaded share metadata keeps package lookups and icon decoding off pointer callbacks. */
final class ShareTarget {
    final ComponentName component;
    final String label;
    final Drawable icon;
    final boolean enabled;
    final String unavailableReason;

    ShareTarget(ComponentName component,String label,Drawable icon,boolean enabled) {
        this(component,label,icon,enabled,"");
    }
    ShareTarget(ComponentName component,String label,Drawable icon,boolean enabled,String reason) {
        this.component=component;this.label=label;this.icon=icon;this.enabled=enabled;unavailableReason=reason;
    }
    String key(){return component.flattenToString();}
}
