package io.github.zerohomers0.onesteplite;

import android.view.View;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** One dwell callback per stable task identity; merely crossing a window never switches it. */
final class WindowDragHover implements AutoCloseable {
    private final View owner;
    private final BooleanSupplier live;
    private Function<String,String> identity=value->value;
    private Consumer<String> activate=value->{};
    private String target;
    private boolean fired;
    private final Runnable dwell=this::activateTarget;
    private void activateTarget(){
        if(target==null || fired || !live.getAsBoolean())return;
        fired=true;activate.accept(target);owner.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }
    WindowDragHover(View owner,BooleanSupplier live){this.owner=owner;this.live=live;}
    void bind(Function<String,String> identity,Consumer<String> activate){close();this.identity=identity;this.activate=activate;}
    void move(String packageName) {
        String key=identity.apply(packageName);
        if(java.util.Objects.equals(key,target))return;
        owner.removeCallbacks(dwell);target=key;fired=false;
        if(key!=null && live.getAsBoolean())owner.postDelayed(dwell,650L);
    }
    @Override public void close(){owner.removeCallbacks(dwell);target=null;fired=false;identity.apply(null);}
}
