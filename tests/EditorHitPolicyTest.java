package io.github.zerohomers0.onesteplite;

public final class EditorHitPolicyTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("editor hit "+checks);}
    private static boolean hit(boolean live,boolean down,float x,float y,float left,float top,float right,float bottom){
        return EditorHitPolicy.hit(live,down,x,y,left,top,right,bottom,1080,2400);
    }
    public static void main(String[] args){
        check(hit(true,true,300,2100,50,2000,1000,2200));
        check(!hit(false,true,300,2100,50,2000,1000,2200));
        check(!hit(true,false,300,2100,50,2000,1000,2200));
        check(!hit(true,true,49,2100,50,2000,1000,2200));
        check(!hit(true,true,300,1999,50,2000,1000,2200));
        check(!hit(true,true,1000,2100,50,2000,1000,2200));
        check(!hit(true,true,300,2200,50,2000,1000,2200));
        check(hit(true,true,50,2000,50,2000,1000,2200));
        check(!hit(true,true,300,2100,-1,2000,1000,2200));
        check(!hit(true,true,300,2100,50,2000,1081,2200));
        check(!hit(true,true,300,2100,50,2000,1000,2401));
        check(!hit(true,true,300,2100,500,2000,100,2200));
        check(!hit(true,true,300,2100,50,2000,51,2200));
        check(!hit(true,true,Float.NaN,2100,50,2000,1000,2200));
        check(!hit(true,true,300,Float.POSITIVE_INFINITY,50,2000,1000,2200));
        check(!hit(true,true,300,2100,50,Float.NaN,1000,2200));
        System.out.println("Editor hit policy checks="+checks+" passed");
    }
}
