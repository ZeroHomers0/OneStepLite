package io.github.zerohomers0.onesteplite;
public final class TopBarFitTest {
 public static void main(String[] args) {
  int checks=0;
  for(int[] size:new int[][]{{720,1600},{1080,2400},{1200,2608},{1440,3200},{240,640},{2608,1200}})
   for(float d:new float[]{1,1.5f,2.75f,3,4}) {
    WorkspaceLayout layout=WorkspaceLayout.of(size[0],size[1],true,d);
    TopBarGeometry g=new TopBarGeometry(size[0],layout.topStrip,Math.round(24*d),d);
    if(g.blankTop<0||g.blankBottom<g.blankTop||g.blankBottom>g.headerTop)throw new AssertionError("clock bounds");checks++;
    if(g.headerTop+g.headerHeight>g.railTop||g.railTop+g.railHeight!=layout.topStrip)throw new AssertionError("rows must not overlap and rail must meet bottom edge");checks++;
    if(g.titleInset!=g.edge+3*g.buttonWidth||2*g.titleInset>=size[0])throw new AssertionError("five buttons leave title room");checks++;
    if(g.railHeight<=0||g.headerHeight<=0)throw new AssertionError("empty action row");checks++;
   }
  System.out.println("TopBarFit: "+checks+" checks passed");
 }
}
