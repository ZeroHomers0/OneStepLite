package io.github.zerohomers0.onesteplite;

/** Perspective projection of a cube's front and upper faces around one shared horizontal axis. */
final class ShareCubeGeometry {
    private ShareCubeGeometry(){}

    static boolean visible(float progress,float height,float camera,boolean share) {
        double angle=Math.max(0,Math.min(1,progress))*Math.PI/2;
        double sin=Math.sin(angle),cos=Math.cos(angle),half=height*.5;
        return share?sin*(camera+half)-cos*half>0:cos*(camera+half)-sin*half>0;
    }

    static void project(float[] result,float progress,float width,float height,float camera,
            boolean share,float left,float faceWidth) {
        double angle=Math.max(0,Math.min(1,progress))*Math.PI/2;
        double sin=Math.sin(angle),cos=Math.cos(angle),half=height*.5;
        for(int i=0;i<4;i++) {
            double x=(i==1 || i==2?faceWidth:0)+left-width*.5;
            double vertical=i>=2?half:-half;
            double y=share?-half:vertical,z=share?vertical:half;
            double rotatedY=y*cos+z*sin,rotatedZ=-y*sin+z*cos-half;
            double scale=camera/(camera-rotatedZ);
            result[2*i]=(float)(width*.5+x*scale-left);
            result[2*i+1]=(float)(half+rotatedY*scale);
        }
    }
}
