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
        Bundle parsed=new Bundle();parsed.putString("source",(String)name);
        if(image) {
            Object value=mode instanceof Bitmap?mode:value(mode,"viewBitmap",true,0);
            Bitmap bitmap=value instanceof Bitmap?(Bitmap)value:null;
            if(bitmap==null || bitmap.isRecycled() || bitmap.getWidth()<=0 || bitmap.getHeight()<=0
                    || (long)bitmap.getWidth()*bitmap.getHeight()>ContentCapturePolicy.MAX_CAPTURE_PIXELS)return false;
            int[] size=ContentCapturePolicy.fit(bitmap.getWidth(),bitmap.getHeight());
            Bitmap bounded=Bitmap.createScaledBitmap(bitmap,size[0],size[1],true);
            try{parsed.putByteArray("image",CaptureImageEncoder.encode(bounded));}
            finally{if(bounded!=bitmap)bounded.recycle();}
            out.putAll(parsed);return true;
        }
        Object raw=mode instanceof CharSequence || mode instanceof CharSequence[] || mode instanceof java.util.List?mode:value(mode,"viewContent",false,0);
        String text=text(raw);if(text==null || text.isBlank() || text.length()>ContentSharePolicy.MAX_TEXT)return false;
        parsed.putString("text",text);out.putAll(parsed);return true;
    }
    @SuppressWarnings("deprecation") // OEM bundles contain typed alternatives, not a fixed Parcelable class.
    static Object value(Object mode,String key,boolean image,int depth){
        if(depth>2)return null;
        Object direct=null,nested=null;
        if(mode instanceof Bundle){direct=((Bundle)mode).get(key);nested=((Bundle)mode).get(image?"image_pick_mode":"pick_mode");}
        else if(mode instanceof java.util.Map){direct=((java.util.Map<?,?>)mode).get(key);nested=((java.util.Map<?,?>)mode).get(image?"image_pick_mode":"pick_mode");}
        return direct!=null?direct:nested==null?null:value(nested,key,image,depth+1);
    }
    private static String text(Object value){
        if(value instanceof CharSequence)return value.toString();
        java.util.List<?> parts=value instanceof CharSequence[]?java.util.Arrays.asList((CharSequence[])value)
                :value instanceof java.util.List?(java.util.List<?>)value:null;
        if(parts==null || parts.size()>ContentSharePolicy.MAX_ITEMS)return null;
        StringBuilder result=new StringBuilder();
        for(Object part:parts){
            if(!(part instanceof CharSequence))return null;
            if(result.length()>0)result.append('\n');
            if(((CharSequence)part).length()>ContentSharePolicy.MAX_TEXT-result.length())return null;
            result.append((CharSequence)part);
        }
        return result.toString();
    }
}
