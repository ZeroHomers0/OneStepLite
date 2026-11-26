package io.github.zerohomers0.onesteplite;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Process-local daemon pools for finite one-shot work; lifecycle polls stay on their owning Handler. */
final class BackgroundExecutor {
    private static final ExecutorService SHARED=Executors.newFixedThreadPool(2,
            daemonFactory("OneStepLite-background"));
    private static final ScheduledExecutorService TIMED=Executors.newSingleThreadScheduledExecutor(
            daemonFactory("OneStepLite-timer"));
    private BackgroundExecutor(){}

    static void execute(Runnable task){
        if(task!=null)SHARED.execute(task);
    }

    static void schedule(Runnable task,long delayMillis){
        if(task!=null)TIMED.schedule(task,Math.max(0L,delayMillis),TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory daemonFactory(String name){
        return runnable->{
            Thread thread=new Thread(runnable,name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
