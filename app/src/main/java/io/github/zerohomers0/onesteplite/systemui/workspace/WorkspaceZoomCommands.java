package io.github.zerohomers0.onesteplite;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Shares command generations across workspace fades, live swaps and delayed steady writes. */
final class WorkspaceZoomCommands {
    private final BiConsumer<Runnable,Long> later;
    private final LongSupplier uptime;
    private final BooleanSupplier zoomed;
    private final Consumer<String> publish,log;
    private long generation;

    WorkspaceZoomCommands(BiConsumer<Runnable,Long> later,LongSupplier uptime,BooleanSupplier zoomed,
            Consumer<String> publish,Consumer<String> log) {
        this.later=later;this.uptime=uptime;this.zoomed=zoomed;this.publish=publish;this.log=log;
    }

    long nextGeneration() { return ++generation; }
    void observeGeneration(long value) { generation=Math.max(generation,value); }

    void write(String steady) { nextGeneration();publish.accept(steady); }

    void animated(boolean on,String steady,long start,long duration) {
        long owner=nextGeneration();
        publish.accept("anim "+steady+" "+duration+(start>0?" at="+start:""));
        // Count from the shared start; publishing latency must not cancel WM's fade.
        long delay=Math.max(0L,start-uptime.getAsLong())+duration+150L;
        later.accept(()->{
            if(!ZoomLifecyclePolicy.shouldApplyDelayedWrite(owner,generation,on,zoomed.getAsBoolean())) {
                log.accept("stale zoom animation completion ignored generation="+owner);return;
            }
            publish.accept(steady);
        },delay);
    }
}
