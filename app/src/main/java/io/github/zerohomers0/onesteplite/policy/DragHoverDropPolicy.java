package io.github.zerohomers0.onesteplite;

/** A hover-promoted side task remains the DROP target on its predicted main area. */
final class DragHoverDropPolicy {
    private DragHoverDropPolicy(){}
    static boolean current(boolean hasTarget,long dragScene,long scene,int dragUser,int user) {
        return hasTarget && dragScene==scene && dragUser==user;
    }
    static boolean usePromotedTarget(boolean current,boolean sideHit,boolean inTarget,boolean sameSource) {
        return current && !sideHit && inTarget && !sameSource;
    }
    static boolean keepPromotionDrag(boolean current,boolean started,boolean location,boolean drop) {
        return current && (started || location || drop);
    }
    static boolean deferUntilReady(boolean promotionActive) {
        return promotionActive;
    }
}
