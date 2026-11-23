package io.github.zerohomers0.onesteplite;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/** Recombination follows source order and preserves contiguous punctuation and spacing. */
public final class WordSelectionPolicyTest {
    private static int checks;
    private static void check(boolean condition){checks++;if(!condition)throw new AssertionError("check "+checks);}
    public static void main(String[] args) {
        String source="你好， hello 😀!";
        List<int[]> spans=Arrays.asList(new int[]{0,2},new int[]{2,3},new int[]{4,9},new int[]{10,12},new int[]{12,13});
        BitSet selected=new BitSet();check(WordSelectionPolicy.selected(source,spans,selected).isEmpty());
        selected.set(2);selected.set(0);check(WordSelectionPolicy.selected(source,spans,selected).equals("你好hello"));
        selected.set(1);check(WordSelectionPolicy.selected(source,spans,selected).equals("你好， hello"));
        selected.set(3);selected.set(4);check(WordSelectionPolicy.selected(source,spans,selected).equals(source));
        selected.clear();selected.set(3);check(WordSelectionPolicy.selected(source,spans,selected).equals("😀"));
        selected.set(500);check(WordSelectionPolicy.selected(source,spans,selected).equals("😀"));
        selected.clear();selected.set(0);check(WordSelectionPolicy.selected("a",Arrays.asList(new int[]{-1,3}),selected).isEmpty());
        System.out.println("Word selection policy checks="+checks+" passed");
    }
}
