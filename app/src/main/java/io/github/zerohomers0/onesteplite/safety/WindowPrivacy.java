package io.github.zerohomers0.onesteplite;

import java.util.function.Consumer;

/** Saves and restores the retained window's alpha and touchability atomically. */
final class WindowPrivacy {
    interface Window {
        float alpha();
        int flags();
        void attributes(float alpha,int flags);
        void apply();
        void requestLayout();
    }
    private final Consumer<String> log;
    private float savedAlpha;
    private boolean savedNotTouchable;

    WindowPrivacy(Consumer<String> log) { this.log=log; }

    boolean setHidden(Window window,boolean hidden,int notTouchableFlag) {
        float previousAlpha=window.alpha();int previousFlags=window.flags();
        if(hidden) {
            savedAlpha=previousAlpha;savedNotTouchable=(previousFlags&notTouchableFlag)!=0;
            window.attributes(0f,previousFlags|notTouchableFlag);
        } else {
            window.attributes(savedAlpha,savedNotTouchable?previousFlags:previousFlags&~notTouchableFlag);
        }
        try {
            window.apply();window.requestLayout();return true;
        } catch(Throwable error) {
            window.attributes(previousAlpha,previousFlags);
            log.accept("lock window update failed: "+error);return false;
        }
    }
}
