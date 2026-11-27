package io.github.zerohomers0.onesteplite;

import android.app.Dialog;
import android.content.Context;
import android.view.MotionEvent;

/** Workspace sheets dismiss outside their bounds even while retaining the app's input focus. */
final class WorkspaceDialog extends Dialog {
    private final Runnable outside;
    WorkspaceDialog(Context context,Runnable outside) {super(context);this.outside=outside;setCanceledOnTouchOutside(true);}
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(event.getActionMasked()==MotionEvent.ACTION_OUTSIDE){outside.run();return true;}
        return super.onTouchEvent(event);
    }
}
