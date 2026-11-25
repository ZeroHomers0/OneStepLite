package io.github.zerohomers0.onesteplite;

/** Bounds every source handoff to one live pointer and one workspace snapshot. */
final class PortalGesturePolicy {
    static final long OFFER_MS=1200,MAX_HOLD_MS=15_000;
    static final long PREFERRED_SOURCE_WAIT_MS=120;
    static final int REGION=0,PORTAL_TEXT=1,SYSTEM_TEXT=2,VIEW_IMAGE=3,PORTAL_IMAGE=4,SYSTEM_IMAGE=5;
    private PortalGesturePolicy(){}
    static boolean live(boolean active,boolean attached,int pointers,long down,long now) {
        return active && attached && pointers>=1 && pointers<=2 && down>0 && now>=down && now-down<=MAX_HOLD_MS;
    }
    static boolean offer(long expected,long actual,long issued,long now) {
        return expected>0 && expected==actual && issued>0 && now>=issued && now-issued<=OFFER_MS;
    }
    static boolean replaces(int current,int incoming,boolean duplicateText) {
        return incoming>current || incoming==current && incoming>=PORTAL_TEXT && incoming<=SYSTEM_TEXT && !duplicateText;
    }
    static boolean sameTouch(float[] touch,float x,float y,int tolerance) {
        return touch!=null && touch.length==2 && Math.hypot(touch[0]-x,touch[1]-y)<=tolerance;
    }
}
