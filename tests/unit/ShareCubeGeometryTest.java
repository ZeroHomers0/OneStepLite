package io.github.zerohomers0.onesteplite;

/** Cube endpoints and shared edges remain coherent across phone and tablet widths. */
public final class ShareCubeGeometryTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void near(float actual,float expected,String message){check(Math.abs(actual-expected)<.002f,message);}
    public static void main(String[] args) {
        float[] front=new float[8],upper=new float[8],prefix=new float[8],apps=new float[8];
        ShareCubeGeometry.project(front,0,500,100,1600,false,0,500);
        ShareCubeGeometry.project(upper,1,500,100,1600,true,0,500);
        float[] identity={0,0,500,0,500,100,0,100};
        for(int i=0;i<8;i++){near(front[i],identity[i],"front endpoint is identity");near(upper[i],identity[i],"share endpoint is identity");}
        check(ShareCubeGeometry.visible(0,100,1600,false),"front visible before turn");
        check(!ShareCubeGeometry.visible(0,100,1600,true),"upper face hidden behind front at start");
        check(!ShareCubeGeometry.visible(1,100,1600,false),"front hidden after turn");
        check(ShareCubeGeometry.visible(1,100,1600,true),"share visible after turn");
        check(ShareCubeGeometry.visible(.5f,100,1600,false)&&ShareCubeGeometry.visible(.5f,100,1600,true),"two adjacent faces visible at midpoint");
        for(float width:new float[]{320,1080,2200})for(float progress:new float[]{.1f,.25f,.5f,.75f,.9f}) {
            ShareCubeGeometry.project(front,progress,width,100,1600,false,0,width);
            ShareCubeGeometry.project(upper,progress,width,100,1600,true,0,width);
            near(front[0],upper[6],"left shared edge has one projected x");
            near(front[1],upper[7],"left shared edge has one projected y");
            near(front[2],upper[4],"right shared edge has one projected x");
            near(front[3],upper[5],"right shared edge has one projected y");
            ShareCubeGeometry.project(prefix,progress,width,100,1600,false,0,50);
            ShareCubeGeometry.project(apps,progress,width,100,1600,false,50,width-50);
            near(prefix[2],apps[0]+50,"normal content and apps share top corner");
            near(prefix[3],apps[1],"normal content and apps share top y");
            near(prefix[4],apps[6]+50,"normal content and apps share bottom corner");
            near(prefix[5],apps[7],"normal content and apps share bottom y");
        }
        ShareCubeGeometry.project(front,-1,500,100,1600,false,0,500);
        ShareCubeGeometry.project(upper,2,500,100,1600,true,0,500);
        near(front[0],0,"negative progress clamps to start");near(upper[4],500,"excess progress clamps to end");
        System.out.println("ShareCubeGeometryTest checks="+checks+" passed");
    }
}
