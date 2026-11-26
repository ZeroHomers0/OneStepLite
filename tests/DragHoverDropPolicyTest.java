package io.github.zerohomers0.onesteplite;

public final class DragHoverDropPolicyTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        check(!DragHoverDropPolicy.current(false,7,7,0,0),"missing target cannot claim the session");
        check(DragHoverDropPolicy.current(true,7,7,0,0),"matching scene and user keep the target");
        check(!DragHoverDropPolicy.current(true,7,8,0,0),"stale scene cannot keep the target");
        check(!DragHoverDropPolicy.current(true,7,7,0,1),"other user cannot keep the target");
        check(!DragHoverDropPolicy.usePromotedTarget(true,true,true,false),"side hit keeps its own routing");
        check(!DragHoverDropPolicy.usePromotedTarget(true,false,false,false),"drop outside the promoted main stays untouched");
        check(!DragHoverDropPolicy.usePromotedTarget(true,false,true,true),"same-source drop still cancels");
        check(DragHoverDropPolicy.usePromotedTarget(true,false,true,false),"promoted main receives the drop");
        check(DragHoverDropPolicy.keepPromotionDrag(true,true,false,false),"promotion keeps a re-entered drag session");
        check(DragHoverDropPolicy.keepPromotionDrag(true,false,true,false),"promotion keeps location events for the hovered target");
        check(DragHoverDropPolicy.keepPromotionDrag(true,false,false,true),"promotion keeps the promoted DROP");
        check(!DragHoverDropPolicy.keepPromotionDrag(true,false,false,false),"promotion blocks unrelated drag actions");
        check(!DragHoverDropPolicy.keepPromotionDrag(false,true,true,true),"ordinary promotion never adopts a drag session");
        check(DragHoverDropPolicy.deferUntilReady(true),"active promotion delays launch");
        check(!DragHoverDropPolicy.deferUntilReady(false),"settled promotion launches now");
        System.out.println("Drag hover drop policy checks="+checks+" passed");
    }
}
