package io.github.zerohomers0.onesteplite;

import android.graphics.Bitmap;
import android.os.Bundle;
import de.robv.android.xposed.XposedHelpers;

/** Normalizes OEM semantic extraction without depending on a Portal view or service. */
final class SystemExtractPayload {
    private SystemExtractPayload(){}
    static boolean append(Object injector,Object mode,boolean image,Bundle out) {
        Object name=injector==null?null:XposedHelpers.callMethod(injector,"getPkgName");
        if(!(name instanceof String) || ((String)name).isEmpty())return false;
        out.putString("source",(String)name);
        if(image) {
            if(!(mode instanceof Bundle))return false;
            Bitmap bitmap=((Bundle)mode).getParcelable("viewBitmap",Bitmap.class);
            if(bitmap==null || bitmap.isRecycled() || bitmap.getWidth()<=0 || bitmap.getHeight()<=0
                    || (long)bitmap.getWidth()*bitmap.getHeight()>ContentCapturePolicy.MAX_CAPTURE_PIXELS)return false;
            int[] size=ContentCapturePolicy.fit(bitmap.getWidth(),bitmap.getHeight());
            Bitmap bounded=Bitmap.createScaledBitmap(bitmap,size[0],size[1],true);
            try{out.putByteArray("image",CaptureImageEncoder.encode(bounded));}
            finally{if(bounded!=bitmap)bounded.recycle();}
            return true;
        }
        CharSequence text=mode instanceof CharSequence?(CharSequence)mode:
                mode instanceof Bundle?((Bundle)mode).getCharSequence("viewContent"):null;
        if(text==null || text.length()==0 || text.length()>ContentSharePolicy.MAX_TEXT)return false;
        out.putString("text",text.toString());return true;
    }
}
