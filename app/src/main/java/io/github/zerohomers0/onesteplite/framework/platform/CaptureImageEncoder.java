package io.github.zerohomers0.onesteplite;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;

/** Produces a bounded JPEG for the module provider; never transports a full screenshot over Binder. */
final class CaptureImageEncoder {
    private CaptureImageEncoder(){}
    static byte[] encode(Bitmap source) {
        Bitmap current=source;
        try {
            for(int pass=0;pass<3;pass++) {
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                if(!current.compress(Bitmap.CompressFormat.JPEG,85,output))throw new IllegalStateException("Capture compression failed");
                if(output.size()<=ContentCapturePolicy.MAX_ENCODED)return output.toByteArray();
                Bitmap smaller=Bitmap.createScaledBitmap(current,Math.max(1,current.getWidth()/2),Math.max(1,current.getHeight()/2),true);
                if(current!=source)current.recycle();current=smaller;
            }
            throw new IllegalArgumentException("Capture too large");
        } finally {if(current!=source)current.recycle();}
    }
}
