package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.widget.FrameLayout;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/** Assembles header layers and side-cell backings without owning tasks or transitions. */
final class WorkspaceChrome {
    final TopBar bar;
    final View backing;
    final FrameLayout clip;

    WorkspaceChrome(Context context,FrameLayout root,WorkspaceLayout layout,List<QuickApp> apps,
            TopBar.Listener listener,Supplier<RectF> exclusion) {
        TopBar prepared=ChromeWarmup.take(context,layout,listener);
        bar=prepared==null?new TopBar(context,layout,apps,listener):prepared;
        backing=new View(context);backing.setBackgroundColor(0xff1e2420);
        root.addView(backing,new FrameLayout.LayoutParams(-1,layout.topStrip));
        clip=new FrameLayout(context) {
            @Override protected void dispatchDraw(Canvas canvas) {
                int saved=canvas.save();
                RectF hole=exclusion.get();if(hole!=null)canvas.clipOutRect(hole);
                super.dispatchDraw(canvas);canvas.restoreToCount(saved);
            }
        };
        clip.setClipChildren(true);clip.setClipToPadding(true);
        clip.setClipBounds(new Rect(0,0,layout.width,layout.tabletLandscape?layout.height:layout.topStrip));
        root.addView(clip,new FrameLayout.LayoutParams(-1,layout.tabletLandscape?-1:layout.topStrip));
        clip.addView(bar,new FrameLayout.LayoutParams(-1,layout.topStrip));
    }

    static void addSideBackings(Context context,FrameLayout root,WorkspaceLayout layout,View[] backings,
            IntUnaryOperator segmentTop,IntUnaryOperator segmentHeight) {
        for(int i=1;i<=layout.cellCount;i++) {
            View backing=new View(context);backing.setBackgroundColor(HyperOsStyle.page(context));
            int[] cell=layout.cellBounds(i-1);
            FrameLayout.LayoutParams bounds=new FrameLayout.LayoutParams(
                    layout.tabletLandscape?cell[2]-cell[0]:layout.sideWidth,segmentHeight.applyAsInt(i));
            bounds.leftMargin=layout.tabletLandscape?cell[0]:layout.mainOnRight?0:layout.mainWidth;
            bounds.topMargin=segmentTop.applyAsInt(i);backing.setLayoutParams(bounds);
            root.addView(backing);backings[i]=backing;
        }
    }
}
