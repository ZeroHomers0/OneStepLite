package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.function.BooleanSupplier;

/** Worker-only preparation verifies owned streams and rebuilds MIME from the actual shared records. */
final class ShareContentPreparation {
    private ShareContentPreparation(){}
    static ClipData prepare(Context context,ClipData source,BooleanSupplier live)throws IOException {
        SharePayload original=new SharePayload(source);LinkedHashSet<String> types=new LinkedHashSet<>();
        for(int i=0;i<original.clip.getItemCount();i++){
            if(!live.getAsBoolean())throw new IOException("Delivery cancelled");
            ClipData.Item item=original.clip.getItemAt(i);
            if(item.getUri()!=null && "content".equals(item.getUri().getScheme())){
                if(!ContentInboxProvider.AUTHORITY.equals(item.getUri().getAuthority()))throw new SecurityException("Unowned delivery URI");
                String mime=ContentInboxProvider.normalize(context,item.getUri(),original.mime);types.add(mime);
                if(!live.getAsBoolean())throw new IOException("Delivery cancelled");
                try(InputStream input=context.getContentResolver().openInputStream(item.getUri())){
                    if(input==null || input.read()<0 && mime.startsWith("image/"))throw new IOException("Unreadable delivery image");
                }
            }
            if(item.getText()!=null)types.add("text/plain");
        }
        if(!live.getAsBoolean())throw new IOException("Delivery cancelled");
        ClipData result=new ClipData(new ClipDescription("分享内容",types.toArray(new String[0])),original.clip.getItemAt(0));
        for(int i=1;i<original.clip.getItemCount();i++)result.addItem(original.clip.getItemAt(i));
        ShareDiagnostics.event(ShareDiagnostics.Stage.COPY,ShareDiagnostics.Code.READABLE);return result;
    }
}
