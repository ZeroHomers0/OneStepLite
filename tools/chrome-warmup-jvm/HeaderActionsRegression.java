package io.github.zerohomers0.onesteplite;

/** Replays real header construction and real click callbacks across cache handoff. */
public final class HeaderActionsRegression {
    static int checks;
    static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    static class Context {}
    static class View {
        interface Click { void run(View view); }
        Click click;
        void setOnClickListener(Click action) { click=action; }
        void performClick() { click.run(this); }
    }
    static class FrameLayout extends View {
        Context context;
        FrameLayout(Context context) { this.context=context; }
        Context getContext() { return context; }
        void setClipChildren(boolean value) {}
        void addView(View child) {}
    }
    static class TextView extends View {
        TextView(Context context) {}
        void setText(String text) {}
        void setGravity(int value) {}
        void setSingleLine(boolean value) {}
        void setIncludeFontPadding(boolean value) {}
        void setTextColor(int value) {}
        void setTypeface(Object value) {}
        void setTextSize(int value) {}
        void setContentDescription(String text) {}
    }
    static class IconButton extends View {
        static final int SIDE_LEFT=1,SIDE_RIGHT=2,TOP_APP=3,SETTINGS=4,EXPAND_LEFT=5,CAPTURE=6;
        IconButton(Context context,int icon,String text) {}
        void setTooltipText(String text) {}
    }
    static class HyperOsStyle { static int textPrimary(Context context) { return 0; } }
    static class Owner {
        int settings,exits,pins,left,right;
        void onOpenSettings() { settings++; }
        void onExit() { exits++; }
        void onSendMainToTop() { pins++; }
        void onSetMainSide(boolean value) { if(value)left++;else right++; }
    }
    static class Bar {
        static class Share {int captures;void captureMain(){captures++;}}
        Share shareWorkspace;
        Owner listener;
        Bar(Owner owner) { listener=owner; }
        __BIND__
        final class Header extends FrameLayout {
            TextView title;
            IconButton left,right,topApp,settings,expand,capture;
            View[] buttons;
            IconButton[] icons;
            __CONSTRUCTOR__
            __BUTTON__
            void updateActions() {}
        }
    }
    public static void main(String[] args) {
        Owner idle=new Owner();Bar bar=new Bar(idle);
        Bar.Header prepared=bar.new Header(new Context(),null);
        for(int handoff=0;handoff<3;handoff++) {
            Owner current=new Owner();bar.bindListener(current);
            bar.shareWorkspace=new Bar.Share();prepared.capture.performClick();
            check(bar.shareWorkspace.captures==1,"capture uses the current workspace after cached-header handoff");
            prepared.left.performClick();prepared.right.performClick();
            prepared.topApp.performClick();prepared.settings.performClick();prepared.expand.performClick();
            check(current.left==1&&current.right==1,"both side buttons use current listener");
            check(current.pins==1,"send-to-top button uses current listener");
            check(current.settings==1,"settings button uses current listener");
            check(current.exits==1,"exit button uses current listener");
            check(idle.left+idle.right+idle.pins+idle.settings+idle.exits==0,"prewarm/retired listener receives no click");
            idle=current;
            idle.left=idle.right=idle.pins=idle.settings=idle.exits=0;
        }
        System.out.println("Production cached header click checks="+checks+" passed");
    }
}
