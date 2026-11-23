package io.github.zerohomers0.onesteplite;

/** Explicit picture extraction has bounded pixels, transport and crop coordinates. */
final class ContentCapturePolicy {
    static final int MAX_SIDE=1600,MAX_ENCODED=512*1024,MAX_CAPTURE_PIXELS=12_000_000;
    private ContentCapturePolicy(){}
    static boolean cleanupAfterShare(boolean enabled,boolean captured,boolean document,String mime) {
        return enabled && captured && !document && mime!=null && mime.startsWith("image/");
    }
    static boolean sharedImageExpired(boolean captured,boolean document,long deadline,long now) {
        return captured && !document && deadline>0 && now>=deadline;
    }
    static boolean validImage(int bytes,int width,int height) {
        return bytes>0 && bytes<=MAX_ENCODED && width>0 && height>0
                && width<=MAX_SIDE && height<=MAX_SIDE;
    }
    static boolean validCrop(int left,int top,int right,int bottom,int width,int height) {
        return left>=0 && top>=0 && right<=width && bottom<=height && right-left>=8 && bottom-top>=8;
    }
    static int[] fit(int width,int height) {
        if(width<=0 || height<=0)throw new IllegalArgumentException("Empty capture");
        double scale=Math.min(1,MAX_SIDE/(double)Math.max(width,height));
        return new int[]{Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale))};
    }
}
