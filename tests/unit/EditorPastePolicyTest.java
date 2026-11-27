package io.github.zerohomers0.onesteplite;

public final class EditorPastePolicyTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args) {
        check(EditorPastePolicy.sameTarget(10001,10001,2,2,true,true));
        check(!EditorPastePolicy.sameTarget(10001,10002,2,2,true,true));
        check(!EditorPastePolicy.sameTarget(10001,10001,2,3,true,true));
        check(!EditorPastePolicy.sameTarget(10001,10001,2,2,false,true));
        check(!EditorPastePolicy.sameTarget(10001,10001,2,2,true,false));
        check(EditorPastePolicy.allowed(true,1,0));check(EditorPastePolicy.allowed(true,100000,1500));
        check(!EditorPastePolicy.allowed(false,1,0));check(!EditorPastePolicy.allowed(true,0,0));
        check(!EditorPastePolicy.allowed(true,100001,0));check(!EditorPastePolicy.allowed(true,1,-1));
        check(!EditorPastePolicy.allowed(true,1,1501));
        System.out.println("Editor paste policy checks="+checks+" passed");
    }
}
