package io.github.zerohomers0.onesteplite;

import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Temporarily hides only the island drawing while our modal owns the screen. */
final class ModalIslandLease implements AutoCloseable {
    private final View modal;
    private final Map<View,Hold> held=new IdentityHashMap<>();
    private final Consumer<List<View>> roots=this::include;
    private boolean closed;
    private final View.OnAttachStateChangeListener attachment=new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) {close();}
    };

    ModalIslandLease(View modal) {this.modal=modal;}

    void start() {
        modal.addOnAttachStateChangeListener(attachment);
        try {WindowRootsCompat.add(command->modal.post(command),roots);}
        catch(RuntimeException failure) {android.util.Log.w(Tags.PROBE,"modal island observation unavailable",failure);}
        include(WindowInspector.getGlobalWindowViews());
    }

    private void include(List<View> views) {
        if(closed)return;
        for(View view:views) {
            if(view.getDisplay()==null || view.getDisplay().getDisplayId()!=0
                    || !(view.getLayoutParams() instanceof WindowManager.LayoutParams params)
                    || params.type!=2009 || !"DynamicIslandWindow".contentEquals(params.getTitle())
                    || held.containsKey(view))continue;
            Hold hold=new Hold(view);held.put(view,hold);
        }
    }

    private final class Hold {
        final View view;
        final float alpha;
        final int visibility;
        final ViewTreeObserver observer;
        final ViewTreeObserver.OnPreDrawListener beforeDraw;
        Hold(View view) {
            this.view=view;alpha=view.getAlpha();visibility=view.getVisibility();observer=view.getViewTreeObserver();
            beforeDraw=()->{
                if(!closed) {
                    if(view.getAlpha()!=0f)view.setAlpha(0f);
                    if(view.getVisibility()==View.VISIBLE)view.setVisibility(View.INVISIBLE);
                }
                return true;
            };
            view.setAlpha(0f);
            if(visibility==View.VISIBLE)view.setVisibility(View.INVISIBLE);
            observer.addOnPreDrawListener(beforeDraw);
        }
        void restore() {
            if(observer.isAlive())observer.removeOnPreDrawListener(beforeDraw);
            if(view.getAlpha()==0f)view.setAlpha(alpha);
            if(view.getVisibility()==View.INVISIBLE)view.setVisibility(visibility);
        }
    }

    @Override public void close() {
        if(closed)return;
        closed=true;modal.removeOnAttachStateChangeListener(attachment);
        try {WindowRootsCompat.remove(roots);}
        catch(RuntimeException failure) {android.util.Log.w(Tags.PROBE,"modal island observation removal",failure);}
        for(Hold hold:held.values())hold.restore();held.clear();
    }
}
