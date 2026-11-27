package io.github.zerohomers0.onesteplite;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local daemon pools for finite one-shot work; lifecycle polls stay on their owning Handler. */
final class BackgroundExecutor {
    private static final int QUEUE_CAPACITY=64;
    private static final ThreadPoolExecutor SHARED=workerPool(2,"OneStepLite-background");
    private static final ScheduledThreadPoolExecutor TIMED=new ScheduledThreadPoolExecutor(
            1,daemonFactory("OneStepLite-timer"));
    static {TIMED.setRemoveOnCancelPolicy(true);}
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE=new java.util.concurrent.atomic.AtomicInteger();
    private static final AtomicLong SUBMITTED=new AtomicLong(),COMPLETED=new AtomicLong(),
            FAILED=new AtomicLong(),DROPPED=new AtomicLong(),MAX_MILLIS=new AtomicLong();
    private BackgroundExecutor(){}

    static void execute(Runnable task){
        if(task==null)return;
        SUBMITTED.incrementAndGet();
        try{
            SHARED.execute(()->runMeasured(task));
        }catch(RuntimeException rejected){
            FAILED.incrementAndGet();
            DROPPED.incrementAndGet();
            throw rejected;
        }
    }

    static ScheduledFuture<?> schedule(Runnable task,long delayMillis){
        return TIMED.schedule(task,Math.max(0L,delayMillis),TimeUnit.MILLISECONDS);
    }

    static ExecutorService single(String name){
        return workerPool(1,name);
    }

    private static ThreadPoolExecutor workerPool(int size,String name){
        ThreadPoolExecutor executor=new ThreadPoolExecutor(size,size,30L,TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),daemonFactory(name),dropOldest());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static RejectedExecutionHandler dropOldest(){
        return (task,executor) -> {
            if(executor.isShutdown())return;
            if(executor.getQueue().poll()!=null)DROPPED.incrementAndGet();
            if(!executor.isShutdown()&&!executor.getQueue().offer(task))DROPPED.incrementAndGet();
        };
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
                +" failed="+FAILED.get()+" active="+ACTIVE.get()
                +" queued="+SHARED.getQueue().size()+" dropped="+DROPPED.get()
                +" maxMs="+MAX_MILLIS.get();
    }

    private static void runMeasured(Runnable task){
        ACTIVE.incrementAndGet();
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
