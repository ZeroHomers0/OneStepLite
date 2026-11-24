package io.github.zerohomers0.onesteplite;

import android.graphics.Path;

/** Cached icon contour: |x|³ + |y|³ = 1, independent of screen density. */
final class IconContour {
    private IconContour(){}
    static Path create(float size) {
        Path path=new Path();float half=size/2;
        for(int i=0;i<96;i++) {
            double angle=i*Math.PI*2/96,c=Math.cos(angle),s=Math.sin(angle);
            float x=half+half*(float)Math.copySign(Math.pow(Math.abs(c),2d/3d),c);
            float y=half+half*(float)Math.copySign(Math.pow(Math.abs(s),2d/3d),s);
            if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
        }
        path.close();return path;
    }
}
