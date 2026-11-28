package io.github.zerohomers0.onesteplite;

import java.util.function.Predicate;

/** The native focus scan skips exact side leases without changing their lifecycle flags. */
final class WorkspaceFocus {
    private WorkspaceFocus() {}

    interface Tree {
        int size(Object area);
        Object child(Object area, int index);
        Object area(Object child);
        Object task(Object child);
        boolean eligible(Object task);
        int mode(Object task);
    }

    static Object next(Tree tree, Predicate<Object> side, Object area, Object ignored,
            boolean ignoreMode, int excludedMode) {
        for (int i = tree.size(area) - 1; i >= 0; i--) {
            Object child = tree.child(area, i);
            Object nested = tree.area(child);
            if (nested != null) {
                Object result = next(tree, side, nested, ignored, ignoreMode, excludedMode);
                if (result != null) return result;
                continue;
            }
            Object task = tree.task(child);
            if (task != null && task != ignored && !side.test(task)
                    && (!ignoreMode || tree.mode(task) != excludedMode) && tree.eligible(task)) return task;
        }
        return null;
    }
}