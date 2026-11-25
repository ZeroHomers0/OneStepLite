package io.github.zerohomers0.onesteplite;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ordered bounded selection and independent page positions survive cancelled drags. */
final class ContentSelection {
    private final List<Set<String>> pages = List.of(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(),new LinkedHashSet<>());
    private final int[] positions = new int[4];

    boolean toggle(int page, String key) {
        Set<String> selected = pages.get(page);
        if (selected.remove(key)) return true;
        if (selected.size() >= ContentSharePolicy.MAX_ITEMS) return false;
        selected.add(key);
        return true;
    }

    List<String> keys(int page) { return new ArrayList<>(pages.get(page)); }
    boolean contains(int page, String key) { return pages.get(page).contains(key); }
    void clear(int page) { pages.get(page).clear(); }
    void clearSelections() { for (Set<String> page : pages) page.clear(); }
    void position(int page, int y) { positions[page] = Math.max(0, y); }
    int position(int page) { return positions[page]; }
    void reset() { for (int i=0;i<pages.size();i++) { clear(i);positions[i]=0; } }
}
