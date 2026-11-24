package io.github.zerohomers0.onesteplite;

/** A small exit margin retains the current icon without overlapping its neighbour. */
final class ShareTargetHitPolicy {
    private ShareTargetHitPolicy(){}
    static boolean hit(float x,float y,float left,float right,float height,float inset,float slack) {
        return Float.isFinite(x) && Float.isFinite(y) && right>left && height>0 && inset>=0 && slack>=0 && slack<=inset
                && x>=left+inset-slack && x<right-inset+slack && y>=inset-slack && y<height-inset+slack;
    }
}
