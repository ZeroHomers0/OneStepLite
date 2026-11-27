package io.github.zerohomers0.onesteplite;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reuses immutable layouts across equal display configurations. */
final class WorkspaceLayoutCache {
    private static final int MAX_ENTRIES = 8;
    private final Map<String,WorkspaceLayout> layouts =
            new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true);

    WorkspaceLayout of(int width, int height, boolean mainOnRight, float density, boolean tablet) {
        String key = width + ":" + height + ":" + mainOnRight + ":"
                + Float.floatToIntBits(density) + ":" + tablet;
        WorkspaceLayout ready = layouts.get(key);
        if (ready != null) return ready;
        WorkspaceLayout created = WorkspaceLayout.of(width, height, mainOnRight, density, tablet);
        layouts.put(key, created);
        if (layouts.size() > MAX_ENTRIES) {
            java.util.Iterator<String> iterator = layouts.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        return created;
    }

    int size() { return layouts.size(); }
}
