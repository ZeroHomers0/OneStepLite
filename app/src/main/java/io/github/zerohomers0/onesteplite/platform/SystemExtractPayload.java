package io.github.zerohomers0.onesteplite;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
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
                    || (long)bitmap.getWidth()*bitmap.getHeight()>ContentCapturePolicy.MAX_CAPTURE_PIXELS
                    || hasNoVisiblePixels(bitmap))return false;
            int[] size=ContentCapturePolicy.fit(bitmap.getWidth(),bitmap.getHeight());
            Bitmap bounded=Bitmap.createScaledBitmap(bitmap,size[0],size[1],true);
            try{parsed.putByteArray("image",CaptureImageEncoder.encode(bounded));}
            finally{if(bounded!=bitmap)bounded.recycle();}
            copyGeometry(mode,true,parsed);
            out.putAll(parsed);return true;
        }
        Object raw=mode instanceof CharSequence || mode instanceof CharSequence[] || mode instanceof java.util.List?mode:value(mode,"viewContent",false,0);
        if(raw==null)raw=configuredText(mode,(String)name);
        String text=text(raw);if(text==null || text.isBlank() || text.length()>ContentSharePolicy.MAX_TEXT)return false;
        parsed.putString("text",text);copyGeometry(mode,false,parsed);out.putAll(parsed);return true;
    }

    static boolean appendRegion(Object injector,Object mode,Bundle out) {
        Object source=injector==null?null:XposedHelpers.callMethod(injector,"getPkgName");
        if(!(source instanceof String name) || name.isEmpty())return false;
        Bundle parsed=new Bundle();
        if(!copyGeometry(mode,true,parsed))return false;
        parsed.putString("source",name);parsed.putBoolean("region_only",true);
        out.putAll(parsed);return true;
    }

    @SuppressWarnings("deprecation") // OEM geometry is carried in a known parcelable array.
    static boolean copyGeometry(Object mode,boolean image,Bundle out) {
        for(int depth=0;depth<=2;depth++) {
            if(mode instanceof Bundle bundle && bundle.get("view_rect") instanceof Rect rect
                    && bundle.get("touch_points") instanceof Object[] points && points.length>=1 && points.length<=2
                    && points[0] instanceof PointF point && Float.isFinite(point.x) && Float.isFinite(point.y)
                    && !rect.isEmpty()) {
                out.putIntArray("source_rect",new int[]{rect.left,rect.top,rect.right,rect.bottom});
                out.putFloatArray("source_touch",new float[]{point.x,point.y});
                return true;
            }
            String key=image?"image_pick_mode":"pick_mode";
            mode=mode instanceof Bundle b?b.get(key):mode instanceof java.util.Map<?,?> m?m.get(key):null;
        }
        return false;
    }

    static boolean hasNoVisiblePixels(Bitmap bitmap) {
        if(!bitmap.hasAlpha() || bitmap.getConfig()==Bitmap.Config.HARDWARE)return false;
        int stepX=Math.max(1,bitmap.getWidth()/16),stepY=Math.max(1,bitmap.getHeight()/16);
        for(int y=stepY/2;y<bitmap.getHeight();y+=stepY)
            for(int x=stepX/2;x<bitmap.getWidth();x+=stepX)
                if((bitmap.getPixel(x,y)>>>24)!=0)return false;
        if(bitmap.getWidth()>8192)return false;
        int[] row=new int[bitmap.getWidth()];
        for(int y=0;y<bitmap.getHeight();y++){
            bitmap.getPixels(row,0,row.length,0,y,row.length,1);
            for(int pixel:row)if((pixel>>>24)!=0)return false;
        }
        return true;
    }
    @SuppressWarnings("deprecation") // OEM metadata uses typed values under declared keys.
    private static CharSequence configuredText(Object mode,String source) {
        Object nested=mode instanceof Bundle b?b.get("pick_mode")
                :mode instanceof java.util.Map<?,?> map?map.get("pick_mode"):null;
        Object payload=nested==null?mode:nested;
        if(!(payload instanceof Bundle bundle) || !source.equals(bundle.getString("apkName")))return null;
        String[] fields=bundle.getStringArray("apkParamList");
        if(fields==null || fields.length>ContentSharePolicy.MAX_ITEMS)return null;
        CharSequence result=null;
        for(String field:fields) {
            if(field==null || !field.startsWith("miuiCE_") || field.length()<=7 || field.length()>255)continue;
            Object value=bundle.get(field.substring(7));
            if(value instanceof CharSequence chars && chars.length()<=ContentSharePolicy.MAX_TEXT)result=chars;
        }
        return result;
    }
    @SuppressWarnings("deprecation") // OEM bundles contain typed alternatives, not a fixed Parcelable class.
    static Object value(Object mode,String key,boolean image,int depth){
        if(depth>2)return null;
        if(image && mode instanceof Bitmap || !image && (mode instanceof CharSequence
                || mode instanceof CharSequence[] || mode instanceof java.util.List))return mode;
        Object direct=null,nested=null;
        if(mode instanceof Bundle){direct=((Bundle)mode).get(key);nested=((Bundle)mode).get(image?"image_pick_mode":"pick_mode");}
        else if(mode instanceof java.util.Map){direct=((java.util.Map<?,?>)mode).get(key);nested=((java.util.Map<?,?>)mode).get(image?"image_pick_mode":"pick_mode");}
        if(image && nested!=null){
            Object wrapped=value(nested,key,true,depth+1);
            if(wrapped instanceof Bitmap)return wrapped;
            return direct;
        }
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
