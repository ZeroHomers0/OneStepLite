package io.github.zerohomers0.onesteplite;

/** Usage access may be absent; in that case preserve a deterministic launcher order. */
final class QuickAppUsageOrder {
    private QuickAppUsageOrder() {}

    static int compare(long foregroundA, long lastA, String labelA, String componentA,
            long foregroundB, long lastB, String labelB, String componentB) {
        long durationA = Math.max(0L, foregroundA), durationB = Math.max(0L, foregroundB);
        int used=Boolean.compare(durationB>0,durationA>0);
        if(used!=0)return used;
        if(durationA>0 && durationB>0) {
            // Recent usage days first; within a day favour frequently used apps.
            int recent=Long.compare(Math.max(0L,lastB)/86400000L,Math.max(0L,lastA)/86400000L);
            if(recent!=0)return recent;
        }
        int order = Long.compare(durationB, durationA);
        if (order != 0) return order;
        if (durationA > 0L) {
            order = Long.compare(lastB, lastA);
            if (order != 0) return order;
        }
        order = labelA.compareToIgnoreCase(labelB);
        return order != 0 ? order : componentA.compareTo(componentB);
    }
}
