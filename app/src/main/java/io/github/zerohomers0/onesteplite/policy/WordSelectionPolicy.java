package io.github.zerohomers0.onesteplite;

import java.util.BitSet;
import java.util.List;

/** Selection uses source ranges so contiguous punctuation and spacing remain unchanged. */
final class WordSelectionPolicy {
    private WordSelectionPolicy(){}
    static String selected(String source,List<int[]> spans,BitSet selected) {
        StringBuilder text=new StringBuilder();int previous=-1,end=0;
        for(int i=selected.nextSetBit(0);i>=0 && i<spans.size();i=selected.nextSetBit(i+1)) {
            int[] span=spans.get(i);
            if(span.length!=2 || span[0]<0 || span[1]>source.length() || span[0]>=span[1])continue;
            if(previous>=0 && i==previous+1)text.append(source,end,span[0]);
            text.append(source,span[0],span[1]);previous=i;end=span[1];
        }
        return text.toString();
    }
}
