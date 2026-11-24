package io.github.zerohomers0.onesteplite;

public final class ShareTargetHitPolicyTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("hit "+checks);}
    public static void main(String[] args){
        check(ShareTargetHitPolicy.hit(25,25,0,50,50,6,0));
        check(!ShareTargetHitPolicy.hit(4,25,0,50,50,6,0));
        check(ShareTargetHitPolicy.hit(4,25,0,50,50,6,3));
        check(!ShareTargetHitPolicy.hit(1,25,0,50,50,6,3));
        check(!ShareTargetHitPolicy.hit(49,25,0,50,50,6,3));
        check(!ShareTargetHitPolicy.hit(49,25,50,100,50,6,0));
        check(!ShareTargetHitPolicy.hit(Float.NaN,25,0,50,50,6,0));
        check(!ShareTargetHitPolicy.hit(25,Float.POSITIVE_INFINITY,0,50,50,6,0));
        check(!ShareTargetHitPolicy.hit(25,-1,0,50,50,6,0));
        check(!ShareTargetHitPolicy.hit(25,51,0,50,50,6,0));
        System.out.println("Share target hit policy checks="+checks+" passed");
    }
}
