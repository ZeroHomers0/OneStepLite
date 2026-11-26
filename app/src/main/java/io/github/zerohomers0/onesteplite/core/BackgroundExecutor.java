package io.github.zerohomers0.onesteplite;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local daemon pools for finite one-shot work; lifecycle polls stay on their owning Handler. */
final class BackgroundExecutor {
    private static final ExecutorService SHARED=Executors.newFixedThreadPool(2,
            daemonFactory("OneStepLite-background"));
    private static final ScheduledExecutorService TIMED=Executors.newSingleThreadScheduledExecutor(
            daemonFactory("OneStepLite-timer"));
    private static final AtomicInteger QUEUED=new AtomicInteger(),ACTIVE=new AtomicInteger();
    private static final AtomicLong SUBMITTED=new AtomicLong(),COMPLETED=new AtomicLong(),
            FAILED=new AtomicLong(),MAX_MILLIS=new AtomicLong();
    private BackgroundExecutor(){}

    static void execute(Runnable task){
        if(task==null)return;
        SUBMITTED.incrementAndGet();QUEUED.incrementAndGet();
        try{
            SHARED.execute(()->runMeasured(task));
        }catch(RuntimeException rejected){
            QUEUED.decrementAndGet();FAILED.incrementAndGet();throw rejected;
        }
    }

    static void schedule(Runnable task,long delayMillis){
        if(task!=null)TIMED.schedule(task,Math.max(0L,delayMillis),TimeUnit.MILLISECONDS);
    }

    static ExecutorService single(String name){
        ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,30L,TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),daemonFactory(name));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    static ThreadFactory daemonFactory(String name){
        return runnable->{
            Thread thread=new Thread(runnable,name);
            thread.setDaemon(true);
            return thread;
        };
    }

    static String diagnostics(){
        return "background pool submitted="+SUBMITTED.get()+" completed="+COMPLETED.get()
                +" failed="+FAILED.get()+" active="+ACTIVE.get()+" queued="+QUEUED.get()
                +" maxMs="+MAX_MILLIS.get();
    }

    private static void runMeasured(Runnable task){
        QUEUED.decrementAndGet();ACTIVE.incrementAndGet();
        long started=System.nanoTime();
        try{
            task.run();COMPLETED.incrementAndGet();
        }catch(Throwable failure){
            FAILED.incrementAndGet();throw failure;
        }finally{
            long elapsed=(System.nanoTime()-started)/1_000_000L;
            MAX_MILLIS.accumulateAndGet(elapsed,Math::max);
            ACTIVE.decrementAndGet();
        }
    }
}
