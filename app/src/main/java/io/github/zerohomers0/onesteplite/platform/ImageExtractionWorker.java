package io.github.zerohomers0.onesteplite;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class ImageExtractionWorker {
    // Binder calls can outlive their lease. Leave one worker available for a subsequent gesture.
    private final ThreadPoolExecutor executor=
            new ThreadPoolExecutor(2,2,30L,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1));

    ImageExtractionWorker() {
        executor.allowCoreThreadTimeOut(true);
    }

    boolean submit(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch(RejectedExecutionException busy) {
            return false;
        }
    }
}
