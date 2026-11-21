package io.github.zerohomers0.onesteplite;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reconcile the saved quick-app pins against the apps installed right now. A pinned app that was
 * uninstalled must not keep its slot: it would shift every later pin's displayed order and inflate
 * the "selected" count in the picker.
 */
final class QuickAppReconcilePolicy {
    private QuickAppReconcilePolicy() {}

    /** Saved pins restricted to installed apps, in their original order and without duplicates. */
    static List<String> prune(List<String> savedOrder, List<String> availableKeys) {
        List<String> result = new ArrayList<>();
        if (savedOrder == null) return result;
        Set<String> present = new LinkedHashSet<>(uniqueNonEmpty(availableKeys));
        for (String key : savedOrder) {
            if (key == null || key.isEmpty() || !present.contains(key) || result.contains(key)) continue;
            result.add(key);
        }
        return result;
    }

    /** True when the saved pins still name an app that is no longer installed. */
    static boolean hasStaleKeys(List<String> savedOrder, List<String> availableKeys) {
        if (savedOrder == null || savedOrder.isEmpty()) return false;
        Set<String> present = new LinkedHashSet<>(uniqueNonEmpty(availableKeys));
        for (String key : savedOrder) if (key != null && !key.isEmpty() && !present.contains(key)) return true;
        return false;
    }

    private static List<String> uniqueNonEmpty(List<String> keys) {
        List<String> result = new ArrayList<>();
        if (keys == null) return result;
        for (String key : keys) if (key != null && !key.isEmpty() && !result.contains(key)) result.add(key);
        return result;
    }
}
