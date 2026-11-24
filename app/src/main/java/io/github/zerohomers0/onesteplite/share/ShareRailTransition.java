package io.github.zerohomers0.onesteplite;

import android.animation.ValueAnimator;
import android.graphics.Matrix;
import android.view.View;

/** Front and upper cube faces share one projection, including the application's content cell. */
final class ShareRailTransition {
    private final View normal,share,normalPrefix;
    private final Matrix normalMatrix=new Matrix(),shareMatrix=new Matrix(),prefixMatrix=new Matrix(),inverse=new Matrix();
    private final float[] source=new float[8],destination=new float[8];
    private ValueAnimator animator;
    private float progress;
    private int generation;
    private Runnable frameChanged=()->{};
    void onFrame(Runnable callback){frameChanged=callback;}

    ShareRailTransition(View normal,View share){this(normal,share,null);}

    ShareRailTransition(View normal,View share,View normalPrefix){
        this.normal=normal;this.share=share;this.normalPrefix=normalPrefix;
    }

    void show(boolean visible) {
        int token=++generation;if(animator!=null)animator.cancel();
        if(!ValueAnimator.areAnimatorsEnabled()){animator=null;progress=visible?1:0;render();return;}
        animator=ValueAnimator.ofFloat(progress,visible?1f:0f);
        animator.setDuration(Math.max(1,Math.round(OverlayMotionSpec.SHARE_RAIL_MS*Math.abs((visible?1:0)-progress))));
        animator.addUpdateListener(frame->{
            if(token!=generation)return;progress=(Float)frame.getAnimatedValue();render();
        });
        animator.start();
    }

    private void render() {
        renderFace(normal,false,normal.getLeft(),normalMatrix);
        if(normalPrefix!=null)renderFace(normalPrefix,false,normalPrefix.getLeft(),prefixMatrix);
        renderFace(share,true,0,shareMatrix);
        frameChanged.run();
    }

    private void renderFace(View view,boolean upper,float left,Matrix matrix) {
        float height=share.getHeight(),width=share.getWidth();
        float camera=Math.max(height*4,800*share.getResources().getDisplayMetrics().density);
        boolean visible=ShareCubeGeometry.visible(progress,height,camera,upper);
        view.setVisibility(visible?View.VISIBLE:View.INVISIBLE);view.setAlpha(1);
        if(!visible || progress==0 || progress==1 || width<=0 || height<=0) {
            matrix.reset();view.setAnimationMatrix(null);return;
        }
        float faceWidth=view.getWidth();
        source[0]=0;source[1]=0;source[2]=faceWidth;source[3]=0;
        source[4]=faceWidth;source[5]=height;source[6]=0;source[7]=height;
        ShareCubeGeometry.project(destination,progress,width,height,camera,upper,left,faceWidth);
        if(matrix.setPolyToPoly(source,0,destination,0,4))view.setAnimationMatrix(matrix);
        else {matrix.reset();view.setAnimationMatrix(null);view.setVisibility(View.INVISIBLE);}
    }

    boolean mapSharePoint(float[] point) {
        if(share.getVisibility()!=View.VISIBLE || !shareMatrix.invert(inverse))return false;
        inverse.mapPoints(point);return true;
    }

    void showShareOnly() {
        generation++;if(animator!=null){animator.cancel();animator=null;}progress=1;render();
    }

    void reset() {
        generation++;if(animator!=null){animator.cancel();animator=null;}progress=0;
        clear(normal);if(normalPrefix!=null)clear(normalPrefix);clear(share);
        normalMatrix.reset();prefixMatrix.reset();shareMatrix.reset();
        normal.setVisibility(View.VISIBLE);if(normalPrefix!=null)normalPrefix.setVisibility(View.VISIBLE);
        share.setVisibility(View.INVISIBLE);
    }

    private void clear(View view) {
        view.setAnimationMatrix(null);view.setRotationX(0);view.setTranslationY(0);view.setAlpha(1);
    }
}
