package io.github.zerohomers0.onesteplite;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;

/** Keeps stable row owners while mapping the viewport onto physical side positions. */
final class SideRowOrder<T> extends AbstractList<T> {
    private final ArrayList<T> rows=new ArrayList<>();

    @Override public T get(int index) { return rows.get(index); }
    @Override public int size() { return rows.size(); }
    @Override public T set(int index,T entry) { return rows.set(index,entry); }
    @Override public void add(int index,T entry) { rows.add(index,entry);modCount++; }
    @Override public T remove(int index) { T entry=rows.remove(index);modCount++;return entry; }

    int maxOffset(int visibleCount,Predicate<T> occupied) {
        int count=visibleCount;
        for(int i=rows.size()-1;i>=0;i--)if(occupied.test(rows.get(i))) {
            count=Math.max(count,Math.min(rows.size(),i+2));break;
        }
        return count-visibleCount;
    }

    T[] remap(T[] current,int firstRow,int visibleCount,int tailStart,
            ObjIntConsumer<T> row,ObjIntConsumer<T> position) {
        T[] mapped=current.clone();int tail=tailStart;
        for(int i=0;i<rows.size();i++) {
            T owner=rows.get(i);row.accept(owner,i);
            int visible=i-firstRow;
            mapped[visible>=0 && visible<visibleCount?1+visible:tail++]=owner;
        }
        for(int i=1;i<mapped.length;i++)position.accept(mapped[i],i);
        return mapped;
    }
}
