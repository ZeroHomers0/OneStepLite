package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;

public final class StatusBarClipRegression {
    static int checks;
    static void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    static void near(double a, double b, String reason) { check(Math.abs(a-b)<.001, reason+": "+a+" != "+b); }
    static final List<String> logs = new ArrayList<>();
    public static final class Surface {
        boolean valid = true; Rect crop;
        public boolean isValid() { return valid; }
    }
    public static final class Transaction {
        int writes; boolean failBefore, failAfter;
        public Transaction setCrop(Surface s, Rect r) {
            if (failBefore) throw new IllegalStateException("before native write");
            s.crop = r; writes++;
            if (failAfter) throw new IllegalStateException("after native write");
            return this;
        }
    }
    public static final class Token {
        Object display, parent = new Object();
        Surface surface = new Surface(); Transaction pending = new Transaction();
        Rect bounds = new Rect(0,0,1200,2608);
        Token(Object d) { display=d; }
        public Object getDisplayContent() { return display; }
        public Object getParent() { return parent; }
        public Surface getSurfaceControl() { return surface; }
        public Transaction getPendingTransaction() { return pending; }
        public Rect getBounds() { return bounds; }
    }
    public static final class Attrs {
        int type=2000; String packageName="com.android.systemui";
        String title="StatusBar";
        public CharSequence getTitle() { return title; }
    }
    public static final class Window {
        Object display; Token mToken; Attrs mAttrs=new Attrs();
        Window(Object d) { display=d; mToken=new Token(d); }
        public Object getDisplayContent() { return display; }
    }
    // The compositor clips in token space BEFORE rotation/magnification. These
    // matrices include the actual 201 trace's R1 token and Leaf:15 transforms.
    static double[] project(int rotation, double scale, double ox, double oy, double x, double y) {
        return switch(rotation) {
            case 1 -> new double[]{ox+scale*(1200-y),oy+scale*x};
            case 3 -> new double[]{ox+scale*y,oy+scale*(2608-x)};
            default -> new double[]{ox+scale*x,oy+scale*y};
        };
    }
    static void animationGeometry() {
        for (int rotation : new int[]{0,1,3}) for (double scale : new double[]{.7465490698814392,.62})
            for (double ox : new double[]{0,1200-1200*scale}) {
                Object display=new Object(); Window w=new Window(display);
                int width=rotation==0?1200:2608, height=rotation==0?2608:1200;
                w.mToken.bounds=new Rect(0,0,width,height);
                MainWindowClip owner=new MainWindowClip();
                owner.update(w,display,logs::add);
                Rect clip=w.mToken.surface.crop;
                check(clip!=null,"production update did not submit a token crop");
                for (int dy : new int[]{0,-12,-58,-143,-144,-180}) {
                    // Uncropped 203 native bar has samples outside the main area:
                    // R0 enters the host toolbar, R1/R3 enter the side area.
                    if (dy<0) {
                        double[] escaped=project(rotation,scale,ox,661,0,dy);
                        boolean outside=escaped[0]<ox-.001||escaped[0]>ox+1200*scale+.001
                                ||escaped[1]<661-.001||escaped[1]>661+2608*scale+.001;
                        check(outside,"uncropped traced animation did not reproduce the spill");
                    }
                    int top=Math.max(dy,clip.top), bottom=Math.min(dy+144,clip.bottom);
                    near(Math.max(0,bottom-top),Math.max(0,144+dy),"clip changed normal in-main hide animation");
                    if (bottom<=top) continue;
                    for (int x : new int[]{0,width}) for (int y : new int[]{top,bottom}) {
                        double[] point=project(rotation,scale,ox,661,x,y);
                        check(point[0]>=ox-.001&&point[0]<=ox+1200*scale+.001
                                &&point[1]>=661-.001&&point[1]<=661+2608*scale+.001,
                                "clipped native animation escaped main area at R"+rotation+" dy="+dy);
                    }
                }
                owner.clear(logs::add);
                check(w.mToken.surface.crop==null,"exit retained token crop");
            }
    }
    static void lifecycle() {
        Object d=new Object(); MainWindowClip owner=new MainWindowClip(); Window w=new Window(d);
        owner.update(w,d,logs::add); owner.update(w,d,logs::add);
        check(w.mToken.pending.writes==1,"unchanged traversal wrote crop every frame");
        w.mToken.bounds=new Rect(37,19,2645,1219); owner.update(w,d,logs::add);
        check(w.mToken.surface.crop.equals(new Rect(0,0,2608,1200)),"rotation used physical/absolute bounds");
        w.mToken.bounds=new Rect(0,0,1200,2608); owner.update(w,d,logs::add);
        check(w.mToken.surface.crop.equals(w.mToken.bounds),"portrait retained landscape canvas");
        Surface old=w.mToken.surface; w.mToken.surface=new Surface(); owner.update(w,d,logs::add);
        check(w.mToken.surface.crop!=null,"rebuilt token surface was not clipped");
        owner.clear(logs::add);
        check(w.mToken.surface.crop==null&&old.crop!=null,"exit changed a replaced native surface");
        int writes=w.mToken.pending.writes; owner.clear(logs::add);
        check(w.mToken.pending.writes==writes,"second cleanup wrote foreign state");
        owner.update(w,d,logs::add); Surface replacement=new Surface();
        replacement.crop=new Rect(2,3,4,5); w.mToken.surface=replacement; owner.clear(logs::add);
        check(replacement.crop.equals(new Rect(2,3,4,5)),"cleanup cleared an unowned replacement crop");
        owner.update(w,d,logs::add); w.mToken.display=new Object(); writes=w.mToken.pending.writes;
        owner.clear(logs::add); check(w.mToken.pending.writes==writes,"cleanup wrote a token on another display");
        w=new Window(d); owner.update(w,d,logs::add); w.mToken.surface.valid=false;
        writes=w.mToken.pending.writes; owner.clear(logs::add);
        check(w.mToken.pending.writes==writes,"cleanup wrote a destroyed surface");
    }
    static void scopeAndFailures() {
        Object d=new Object(); MainWindowClip owner=new MainWindowClip();
        for (int type : new int[]{1,2011,2012,2019,2038,2040}) {
            Window w=new Window(d); w.mAttrs.type=type; owner.update(w,d,logs::add);
            check(w.mToken.pending.writes==0,"non-status window changed: "+type);
        }
        Window w=new Window(d); w.mAttrs.packageName="foreign"; owner.update(w,d,logs::add);
        check(w.mToken.pending.writes==0,"foreign status-type window admitted");
        w=new Window(d); owner.update(w,new Object(),logs::add);
        check(w.mToken.pending.writes==0,"secondary display admitted");
        w.mToken.parent=null; owner.update(w,d,logs::add);
        check(w.mToken.pending.writes==0,"detached token clipped");
        w.mToken.parent=new Object(); w.mToken.surface.valid=false; owner.update(w,d,logs::add);
        check(w.mToken.pending.writes==0,"invalid surface clipped");
        w.mToken.surface.valid=true; w.mToken.bounds=new Rect(0,0,0,0); owner.update(w,d,logs::add);
        check(w.mToken.pending.writes==0,"empty canvas submitted");
        owner.update(null,d,logs::add); owner.update(w,null,logs::add);
        for (boolean after : new boolean[]{false,true}) {
            Window f=new Window(d); f.mToken.pending.failBefore=!after; f.mToken.pending.failAfter=after;
            boolean failed=false;
            try { owner.update(f,d,logs::add); } catch (RuntimeException expected) { failed=true; }
            check(failed,"failed native write was cached as successful");
            f.mToken.pending.failBefore=false; f.mToken.pending.failAfter=false;
            owner.clear(logs::add); check(f.mToken.surface.crop==null,"failed write retained crop after exit");
            owner.update(f,d,logs::add); check(f.mToken.surface.crop!=null,"failed write could not retry");
            f.mToken.pending.failBefore=true; owner.clear(logs::add);
            check(f.mToken.surface.crop!=null,"failed cleanup lost test precondition");
            f.mToken.pending.failBefore=false; owner.clear(logs::add);
            check(f.mToken.surface.crop==null,"failed cleanup ownership was not retained for retry");
        }
    }
    static void sidebarAnimationGeometry() {
        for (int rotation : new int[]{0,1,3}) for (double scale : new double[]{.7466114,.62})
            for (double ox : new double[]{0,1200-1200*scale}) for (boolean right : new boolean[]{false,true}) {
                Object display=new Object(); Window w=new Window(display);
                w.mAttrs.type=2026;w.mAttrs.packageName="com.miui.securitycenter";w.mAttrs.title="DockAssistantView";
                check(InputMagnificationPolicy.shouldForceMagnification(w.mAttrs.type,0,w.mAttrs.packageName,w.mAttrs.title),"token geometry requires a real main-magnification policy, not an assumed transform");
                int width=rotation==0?1200:2608,height=rotation==0?2608:1200;
                w.mToken.bounds=new Rect(0,0,width,height);
                MainWindowClip owner=new MainWindowClip();owner.update(w,display,logs::add);
                Rect clip=w.mToken.surface.crop;
                check(clip!=null&&clip.equals(w.mToken.bounds),"sidebar must clip on the stationary token, not its narrow moving window");
                for (int travel : new int[]{96,48,12,0,12,48,96}) {
                    int left=right?width-24+travel:-travel,rightEdge=left+24;
                    int clippedLeft=Math.max(left,clip.left),clippedRight=Math.min(rightEdge,clip.right);
                    near(Math.max(0,clippedRight-clippedLeft),Math.max(0,24-travel),"sidebar entry/exit must retain its native visible fraction");
                    if (clippedRight<=clippedLeft) continue;
                    for (int x : new int[]{clippedLeft,clippedRight}) for (int y : new int[]{400,600}) {
                        double[] p=project(rotation,scale,ox,673,x,y);
                        check(p[0]>=ox-.001&&p[0]<=ox+1200*scale+.001
                                &&p[1]>=673-.001&&p[1]<=673+2608*scale+.001,
                                "native sidebar animation crossed into the workspace rail");
                    }
                }
                owner.update(w,display,logs::add);
                check(w.mToken.pending.writes==1,"stationary token crop must not restart the native animation each frame");
                owner.clear(logs::add);check(w.mToken.surface.crop==null,"workspace exit must restore the native sidebar canvas");
            }
        for (int gate=0;gate<4;gate++) {
            Object display=new Object(); Window w=new Window(display);
            w.mAttrs.type=2026;w.mAttrs.packageName="com.miui.securitycenter";w.mAttrs.title="DockAssistantView";
            if(gate==0)w.mAttrs.packageName="foreign";if(gate==1)w.mAttrs.title="another security-center window";
            if(gate==2)w.mAttrs.type=2038;if(gate==3)w.display=new Object();
            new MainWindowClip().update(w,display,logs::add);
            check(w.mToken.pending.writes==0,"sidebar clipping must use exact package/type/title/display ownership");
        }
    }
    public static void main(String[] args) {
        animationGeometry(); lifecycle(); scopeAndFailures(); sidebarAnimationGeometry();
        System.out.println("actual status-bar clip geometry/lifecycle checks="+checks+" passed");
    }
}
