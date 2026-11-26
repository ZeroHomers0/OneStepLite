package io.github.zerohomers0.onesteplite;

import java.util.concurrent.atomic.AtomicInteger;

/** One bounded extraction lease; a stale capture can never clear a newer extraction. */
final class ImageExtractionLease {
    private final AtomicInteger current=new AtomicInteger();
    private final AtomicInteger sequence=new AtomicInteger();
    int begin() {
        int token;
        do{token=sequence.incrementAndGet();}while(token==0);
        return current.compareAndSet(0,token)?token:0;
    }
    boolean busy(){return current.get()!=0;}
    boolean active(int token){return token!=0 && current.get()==token;}
    boolean finish(int token){return current.compareAndSet(token,0);}
    boolean expire(int token){return current.compareAndSet(token,0);}
}
