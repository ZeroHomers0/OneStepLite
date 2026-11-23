package io.github.zerohomers0.onesteplite;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import java.util.LinkedHashSet;
import java.util.List;

/** Reuses the existing pinned-icon drag editor; cancel never persists its draft order. */
final class ShareTargetPicker {
    private Dialog dialog;
    private ModalIslandLease island;

    boolean isOpen(){return dialog!=null;}

    void show(View anchor,List<ShareTarget> targets,int owner) {
        close();
        Context base=anchor.getContext();
        Context context=new android.view.ContextThemeWrapper(base.createDisplayContext(anchor.getDisplay())
                .createWindowContext(WindowTypes.SYSTEM_UI_DIALOG,null),HyperOsStyle.dark(base)
                ?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert);
        LinkedHashSet<String> chosen=new LinkedHashSet<>(QuickAppOrder.decode(ContentAccessCompat.pins(base)));
        dialog=new Dialog(context);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ShortcutEditor body=new ShortcutEditor(context,targets,chosen,this::close,()->{
            if(owner!=CurrentUser.id() || !ContentAccessCompat.savePins(base,QuickAppOrder.encode(chosen)))
                android.widget.Toast.makeText(base,"无法保存分享入口",android.widget.Toast.LENGTH_SHORT).show();
            else close();
        });
        dialog.setContentView(body);dialog.setOnDismissListener(d->{release();dialog=null;});
        Window window=dialog.getWindow();if(window==null){close();return;}
        window.setType(WindowTypes.SYSTEM_UI_DIALOG);window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        WindowManager.LayoutParams attrs=window.getAttributes();attrs.setTitle("OneStepLiteContentPanel");
        HostWindowPolicy.configure(attrs);window.setAttributes(attrs);dialog.show();
        window.setLayout(Math.min(dp(base,440),base.getResources().getDisplayMetrics().widthPixels-dp(base,24)),
                Math.min(dp(base,560),base.getResources().getDisplayMetrics().heightPixels-dp(base,64)));
        island=new ModalIslandLease(body);island.start();
    }

    void close(){Dialog old=dialog;dialog=null;if(old!=null)old.dismiss();release();}
    private void release(){if(island!=null){island.close();island=null;}}
    private static int dp(Context context,int value){return Math.round(HyperOsStyle.dp(context,value));}
}
