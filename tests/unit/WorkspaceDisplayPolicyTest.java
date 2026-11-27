package io.github.zerohomers0.onesteplite;

public final class WorkspaceDisplayPolicyTest {
    private static int checks;
    private static void check(boolean value) { if (!value) throw new AssertionError("check " + checks); checks++; }
    public static void main(String[] args) {
        check(WorkspaceDisplayPolicy.isTablet(2032,3048,400));
        check(WorkspaceDisplayPolicy.isTablet(3048,2032,400));
        check(WorkspaceDisplayPolicy.isTablet(1200,1920,320));
        check(!WorkspaceDisplayPolicy.isTablet(1199,1920,320));
        check(!WorkspaceDisplayPolicy.isTablet(1080,2460,440));
        check(!WorkspaceDisplayPolicy.isTablet(1440,3200,640));
        check(!WorkspaceDisplayPolicy.isTablet(2032,3048,0));
        for (int rotation=0;rotation<4;rotation++) {
            check(WorkspaceDisplayPolicy.rotation(true,rotation)==rotation);
            check(WorkspaceDisplayPolicy.rotation(false,rotation)==0);
        }
        for (int[] size : new int[][]{{2032,3048},{3048,2032},{2560,1600},{1600,2560}}) {
            int w=WorkspaceDisplayPolicy.width(true,size[0],size[1]);
            int h=WorkspaceDisplayPolicy.height(true,size[0],size[1]);
            check(w==size[0] && h==size[1]);
            check(WorkspaceDisplayPolicy.width(false,w,h)==Math.min(w,h));
            for (boolean right : new boolean[]{false,true}) for (float density : new float[]{1.5f,2f,2.5f,3f}) {
                WorkspaceLayout layout=WorkspaceLayout.of(w,h,right,density);
                check(layout.fillsScreen());
                TopBarGeometry top=new TopBarGeometry(w,layout.topStrip,0,density,true);
                check(top.blankTop>=0 && top.blankBottom<=top.headerTop);
                check(top.blankBottom-top.blankTop<=Math.round(160*density));
                check(top.controlWidth==w-2*top.edge && top.controlWidth<=w);
                check(top.railTop>=top.headerTop+top.headerHeight);
                check(top.titleInset*2<w);

            }
        }
        System.out.println("WorkspaceDisplayPolicy checks="+checks+" passed");
    }
}
