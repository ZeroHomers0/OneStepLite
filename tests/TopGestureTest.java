package io.github.zerohomers0.onesteplite;

public final class TopGestureTest {
    static int checks;
    static void ok(boolean value) {checks++;if(!value)throw new AssertionError("check "+checks);}
    static TopTaskSwipe swipe=new TopTaskSwipe();
    static int e(int action,int count,float x,float y,float x1,float y1,long time) {
        return swipe.event(action,count,7,12,x,y,x1,y1,time,1080,622,2,true);
    }
    static void pair(float x,float y) {e(0,1,x,y,0,0,0);e(5,2,x,y,x+250,y,40);}
    public static void main(String[] args) {
        // A single finger never starts the parallel gesture.
        e(0,1,300,500,0,0,0);ok(e(2,1,300,100,0,0,100)==0);
        // Below the threshold nothing is claimed.
        pair(400,300);ok(e(2,2,420,300,670,300,100)==0);
        // A horizontal pair is claimed once and then tracks the same stream.
        pair(400,300);ok(e(2,2,480,302,730,302,100)==TopTaskSwipe.ACCEPT);
        ok(swipe.horizontal()&&swipe.displacement==80);
        ok(e(2,2,520,50,770,50,130)==TopTaskSwipe.DRAG);
        ok(swipe.horizontal()&&swipe.displacement==120); // axis stays horizontal despite later vertical drift
        ok(e(6,2,520,50,770,50,140)==TopTaskSwipe.FINISH);
        // A vertical pair is no longer a TOP gesture at all.
        pair(400,300);ok(e(2,2,400,200,650,200,100)==0);ok(!swipe.accepted());
        // A pinch stays with the app.
        pair(400,300);ok(e(2,2,300,300,750,300,100)==0);ok(!swipe.accepted());
        // Both directions are accepted.
        for(int sign:new int[]{-1,1}) {
            pair(400,300);
            ok(e(2,2,400+sign*80,302,650+sign*80,302,100)==TopTaskSwipe.ACCEPT);
            ok(swipe.horizontal()&&swipe.displacement==sign*80);
            ok(e(2,2,650+sign*120,50,400+sign*120,50,130)==TopTaskSwipe.DRAG);
            ok(swipe.horizontal()&&swipe.displacement==sign*120);
            ok(e(3,2,0,0,0,0,140)==TopTaskSwipe.CANCEL);
        }
        // A second pair that never moves horizontally is ignored.
        pair(400,300);ok(e(2,2,400,440,650,440,100)==0);
        // A third pointer cancels an accepted stream.
        pair(400,300);e(2,2,480,300,730,300,100);
        ok(e(5,3,480,290,730,290,120)==TopTaskSwipe.CANCEL);
        // A slow pair past the pairing window never starts.
        e(0,1,400,300,0,0,0);e(5,2,400,300,650,300,400);ok(e(2,2,480,300,730,300,450)==0);
        // A pair that drifts apart is not a parallel drag.
        e(0,1,400,300,0,0,0);e(5,2,400,300,650,300,40);ok(e(2,2,300,300,750,300,100)==0);
        // Reversed pointer ids still track the same stream.
        pair(400,300);e(2,2,480,300,730,300,100);
        ok(swipe.event(2,2,12,7,730,300,480,300,130,1080,622,2,true)==TopTaskSwipe.DRAG);
        ok(swipe.displacement==80);
        // Tablet panes use their own bounds and outward direction.
        for(int sign:new int[]{-1,1}) {
            for(int motion=0;motion<4;motion++) {
                swipe.event(0,1,7,12,400,600,0,0,0,904,1426,2.5f,true,sign);
                swipe.event(5,2,7,12,400,600,650,600,40,904,1426,2.5f,true,sign);
                float dx=motion==0?sign*80:motion==1?-sign*80:motion==3?80:0;
                float dy=motion==2?100:0;
                int result=swipe.event(2,2,7,12,400+dx,600+dy,
                        motion==3?570:650+dx,600+dy,100,904,1426,2.5f,true,sign);
                ok(result==(motion<=1?TopTaskSwipe.ACCEPT:TopTaskSwipe.NONE));
            }
        }
        System.out.println("TopGesture: "+checks+" checks passed");
    }
}
