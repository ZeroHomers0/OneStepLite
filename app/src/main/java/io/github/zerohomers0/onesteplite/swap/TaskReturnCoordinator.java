package io.github.zerohomers0.onesteplite;

/** Bounds native handback observation and rejects callbacks for replaced bindings. */
final class TaskReturnCoordinator {
    record Result(boolean ready,boolean removed,String detail) {}
    interface Host {
        boolean current();
        boolean executeOnShell(Runnable action);
        Result inspect() throws Throwable;
        void post(Runnable action);
        void later(Runnable action,long delay);
        boolean handoffAttempted();
        void complete(boolean removed);
        void fail(String detail,boolean freeze);
    }
    private final Host host;
    private boolean finished;

    TaskReturnCoordinator(Host host) { this.host=host; }

    void observe(int attempt) {
        if(finished)return;
        if(!host.current()) { fail("stale return ticket; no new binding touched",false);return; }
        if(!host.executeOnShell(()->{
            Result result;
            try {
                if(!host.current())throw new IllegalStateException("stale return on Shell queue");
                result=host.inspect();
            } catch(Throwable error) { result=new Result(false,false,error.toString()); }
            Result observed=result;
            host.post(()->finish(observed,attempt));
        }))fail("no Shell queue for handback",false);
    }

    private void finish(Result result,int attempt) {
        if(finished)return;
        if(!host.current()) { fail("stale return completion; no new binding touched",false);return; }
        if(result.ready()) {
            finished=true;host.complete(result.removed());
        } else if(attempt<2 && !host.handoffAttempted()) {
            host.later(()->observe(attempt+1),500L);
        } else fail(result.detail(),true);
    }

    private void fail(String detail,boolean freeze) {
        if(finished)return;
        finished=true;host.fail(detail,freeze);
    }
}
