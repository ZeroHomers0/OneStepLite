package io.github.zerohomers0.onesteplite;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ImageExtractionLeaseTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args) throws InterruptedException {
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
        checkBlockedWorker(lease);
        System.out.println("Image extraction lease checks="+checks+" passed");
    }

    private static void checkBlockedWorker(ImageExtractionLease lease) throws InterruptedException {
        ImageExtractionWorker worker=new ImageExtractionWorker();
        CountDownLatch entered=new CountDownLatch(1);
        CountDownLatch release=new CountDownLatch(1);
        CountDownLatch nextRan=new CountDownLatch(1);
        int stalled=lease.begin();
        try {
            check(worker.submit(()->{
                entered.countDown();
                try{release.await();}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                finally{lease.finish(stalled);}
            }),"stalled work is submitted");
            check(entered.await(2,TimeUnit.SECONDS),"first worker enters the blocking call");
            check(lease.expire(stalled),"expired call relinquishes ownership while still blocked");
            int next=lease.begin();
            check(next!=0 && worker.submit(()->{
                if(lease.finish(next))nextRan.countDown();
            }),"next gesture can submit work");
            check(nextRan.await(2,TimeUnit.SECONDS),"next gesture runs before the old call returns");
        } finally {
            release.countDown();
        }
    }
}
