package io.github.zerohomers0.onesteplite;

public final class ImageExtractionLeaseTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        ImageExtractionLease lease=new ImageExtractionLease();
        check(!lease.busy(),"new lease is idle");
        int first=lease.begin();
        check(first!=0&&lease.busy()&&lease.active(first),"first extraction owns the lease");
        check(lease.begin()==0,"concurrent extraction is rejected");
        check(lease.expire(first)&&!lease.busy(),"watchdog expires a stalled extraction");
        int second=lease.begin();
        check(second!=0&&!lease.finish(first),"stale completion cannot clear the newer lease");
        check(lease.active(second),"newer extraction remains active");
        check(lease.finish(second)&&!lease.busy(),"current completion releases the lease");
        check(!lease.finish(second),"duplicate completion is harmless");
        System.out.println("Image extraction lease checks="+checks+" passed");
    }
}
