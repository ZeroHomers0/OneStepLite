package io.github.zerohomers0.onesteplite;

import java.util.function.Predicate;
import de.robv.android.xposed.XposedHelpers;

/** Supplies the native WindowContainer tree used by the pure focus traversal policy. */
final class WorkspaceFocusCompat {
    private WorkspaceFocusCompat() {}

    static Object next(Object area, Object ignored, boolean ignoreMode, int excludedMode,
            Predicate<Object> side) {
        return WorkspaceFocus.next(new WorkspaceFocus.Tree() {
            @Override public int size(Object value) {
                return (Integer) XposedHelpers.callMethod(value, "getChildCount");
            }
            @Override public Object child(Object value, int index) {
                return XposedHelpers.callMethod(value, "getChildAt", index);
            }
            @Override public Object area(Object child) {
                return XposedHelpers.callMethod(child, "asTaskDisplayArea");
            }
            @Override public Object task(Object child) {
                return XposedHelpers.callMethod(child, "asTask");
            }
            @Override public boolean eligible(Object task) {
                return Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isFocusableAndVisible"));
            }
            @Override public int mode(Object task) {
                return (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
            }
        }, side, area, ignored, ignoreMode, excludedMode);
    }
}