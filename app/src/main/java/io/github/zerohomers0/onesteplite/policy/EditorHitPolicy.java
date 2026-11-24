package io.github.zerohomers0.onesteplite;

/** Editor feedback requires a live, finite physical rectangle and an actual pointer hit. */
final class EditorHitPolicy {
    private EditorHitPolicy(){}
    static boolean hit(boolean live,boolean down,float x,float y,float left,float top,float right,float bottom,int width,int height){
        return live && down && Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(left)&&Float.isFinite(top)
                && Float.isFinite(right)&&Float.isFinite(bottom) && left>=0 && top>=0 && right<=width && bottom<=height
                && right-left>=2 && bottom-top>=2 && x>=left && x<right && y>=top && y<bottom;
    }
}
