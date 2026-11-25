package io.github.zerohomers0.onesteplite;

/** Physical pointer ownership must survive neither privacy boundaries nor stale replies. */
public final class PortalGesturePolicyTest {
    private static int checks;
    private static void check(boolean condition){checks++;if(!condition)throw new AssertionError("check "+checks);}
    public static void main(String[] args) {
        check(PortalGesturePolicy.live(true,true,1,100,500));
        check(!PortalGesturePolicy.live(false,true,1,100,500));
        check(!PortalGesturePolicy.live(true,false,1,100,500));
        check(PortalGesturePolicy.live(true,true,2,100,500));
        check(!PortalGesturePolicy.live(true,true,3,100,500));
        check(!PortalGesturePolicy.live(true,true,1,0,500));
        check(!PortalGesturePolicy.live(true,true,1,500,100));
        check(!PortalGesturePolicy.live(true,true,1,100,15101));
        check(PortalGesturePolicy.offer(100,100,500,1700));
        check(!PortalGesturePolicy.offer(100,101,500,600));
        check(!PortalGesturePolicy.offer(100,100,500,1701));
        check(!PortalGesturePolicy.offer(100,100,500,499));
        check(!PortalGesturePolicy.offer(100,100,0,500));
        check(PortalGesturePolicy.replaces(PortalGesturePolicy.VIEW_IMAGE,PortalGesturePolicy.PORTAL_IMAGE,false));
        check(PortalGesturePolicy.replaces(PortalGesturePolicy.PORTAL_IMAGE,PortalGesturePolicy.SYSTEM_IMAGE,false));
        check(PortalGesturePolicy.replaces(PortalGesturePolicy.SYSTEM_TEXT,PortalGesturePolicy.VIEW_IMAGE,true));
        check(!PortalGesturePolicy.replaces(PortalGesturePolicy.PORTAL_IMAGE,PortalGesturePolicy.VIEW_IMAGE,false));
        check(!PortalGesturePolicy.replaces(PortalGesturePolicy.SYSTEM_IMAGE,PortalGesturePolicy.SYSTEM_TEXT,false));
        check(!PortalGesturePolicy.replaces(PortalGesturePolicy.PORTAL_TEXT,PortalGesturePolicy.PORTAL_TEXT,true));
        check(PortalGesturePolicy.replaces(PortalGesturePolicy.PORTAL_TEXT,PortalGesturePolicy.SYSTEM_TEXT,false));
        check(!PortalGesturePolicy.replaces(PortalGesturePolicy.SYSTEM_TEXT,PortalGesturePolicy.PORTAL_TEXT,false));
        check(PortalGesturePolicy.sameTouch(new float[]{105,102},100,100,8));
        check(!PortalGesturePolicy.sameTouch(new float[]{140,100},100,100,8));
        check(!PortalGesturePolicy.sameTouch(new float[]{Float.NaN,100},100,100,8));
        check(PortalGesturePolicy.replaces(PortalGesturePolicy.REGION,PortalGesturePolicy.VIEW_IMAGE,false));
        System.out.println("Portal gesture policy checks="+checks+" passed");
    }
}
