package io.github.zerohomers0.onesteplite;

import android.os.Handler;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One native commit signal or its bounded watchdog starts state verification exactly once. */
final class NativeObservation implements Runnable {
    final Object token;
    final Runnable check;
    private final AtomicBoolean consumed = new AtomicBoolean();
    private final Handler main;
    private final Consumer<NativeObservation> retire;

    NativeObservation(Object token, Runnable check, Handler main,
            Consumer<NativeObservation> retire) {
        this.token = token;
        this.check = check;
        this.main = main;
        this.retire = retire;
    }

    @Override public void run() {
        if (!consumed.compareAndSet(false, true)) return;
        main.removeCallbacks(this);
        retire.accept(this);
        check.run();
    }

    boolean isConsumed() { return consumed.get(); }

    void cancel() {
        if (!consumed.compareAndSet(false, true)) return;
        main.removeCallbacks(this);
        retire.accept(this);
    }
}
