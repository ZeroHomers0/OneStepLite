"""Replay production rail motion endpoints, first frames and interrupted reversals."""
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir

root = Path(__file__).resolve().parents[2]
src = package_dir(root)
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
files = {
'android/util/DisplayMetrics.java':'package android.util;public class DisplayMetrics {public float density=2;}',
'android/content/res/Resources.java':'package android.content.res;public class Resources {public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}}',
'android/graphics/Matrix.java':'''package android.graphics;public class Matrix {
public float[] destination;public void reset(){destination=null;}
public boolean setPolyToPoly(float[] a,int ai,float[] b,int bi,int count){destination=java.util.Arrays.copyOfRange(b,bi,bi+8);return true;}
public boolean invert(Matrix target){target.destination=destination;return true;}public void mapPoints(float[] points){}
}''',
'android/view/View.java':'''package android.view;public class View {
public static final int VISIBLE=0,INVISIBLE=4;public int visibility=VISIBLE,width=400,left;public float y,rotation,alpha=1,pivotX;
public android.graphics.Matrix animation;public void setAnimationMatrix(android.graphics.Matrix value){animation=value;}public int getVisibility(){return visibility;}
public void setVisibility(int v){visibility=v;}public void setPivotY(float v){}public void setPivotX(float v){pivotX=v;}public void setCameraDistance(float v){}
public void setTranslationY(float v){y=v;}public void setRotationX(float v){rotation=v;}public void setAlpha(float v){alpha=v;}
public int getLeft(){return left;}public int getHeight(){return 100;}public int getWidth(){return width;}public android.content.res.Resources getResources(){return new android.content.res.Resources();}}''',
'android/animation/ValueAnimator.java':'''package android.animation;public class ValueAnimator {
public interface Listener {void update(ValueAnimator frame);}public static ValueAnimator latest;
public float from,to,value;public long duration;Listener listener;
public static boolean enabled=true;public static boolean areAnimatorsEnabled(){return enabled;}
public static ValueAnimator ofFloat(float a,float b){latest=new ValueAnimator();latest.from=a;latest.to=b;latest.value=a;return latest;}
public void setDuration(long ms){duration=ms;}public void addUpdateListener(Listener next){listener=next;}
public Object getAnimatedValue(){return value;}public void start(){tick(from);}public void cancel(){}
public void tick(float next){value=next;listener.update(this);}}''',
'io/github/zerohomers0/onesteplite/ShareRailMotionRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.view.View;import android.animation.ValueAnimator;
public class ShareRailMotionRegression {
static int checks;static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
public static void main(String[] args){
View normal=new View(),share=new View();ShareRailTransition motion=new ShareRailTransition(normal,share);motion.reset();
int[] refreshes={0};motion.onFrame(()->refreshes[0]++);
check(normal.visibility==0&&share.visibility==4,"idle shows normal rail only");
motion.show(true);ValueAnimator entrance=ValueAnimator.latest;
check(entrance.duration==480,"dedicated share duration remains unchanged");
check(normal.visibility==0&&share.visibility==4,"first frame starts from front cube face");
entrance.tick(.25f);check(share.visibility==0&&normal.visibility==0,"two cube faces appear during turn");
check(refreshes[0]>=2,"stationary pointer can refresh hit mapping while cube face moves");
check(normal.animation!=null&&share.animation!=null,"contents use projected face matrices");
check(Math.abs(normal.animation.destination[1]-share.animation.destination[7])<.002f,"projected face matrices join at one edge");
entrance.tick(1);check(share.visibility==0&&normal.visibility==4,"share replaces entire normal row");
check(share.animation==null,"share endpoint removes temporary animation matrix");
float[] first={25,50};check(motion.mapSharePoint(first)&&first[0]==25,"first share cell has identity hit mapping at endpoint");
motion.show(false);ValueAnimator exit=ValueAnimator.latest;
check(share.visibility==0&&normal.visibility==4,"reverse starts from share face");
exit.tick(.75f);check(normal.visibility==0&&share.visibility==0,"normal face returns during reverse");
exit.tick(0);check(normal.visibility==0&&share.visibility==4,"release restores normal rail");
check(normal.animation==null&&normal.alpha==1,"normal endpoint clears temporary matrix without crossfade");
check(!motion.mapSharePoint(new float[]{25,50}),"hidden share face cannot accept first-cell drop");
motion.show(true);ValueAnimator interrupted=ValueAnimator.latest;interrupted.tick(.5f);motion.show(false);
ValueAnimator reverse=ValueAnimator.latest;check(reverse.duration==240,"interrupted reverse uses remaining duration");
interrupted.tick(1);check(normal.visibility==0,"retired entrance cannot hide current face");
reverse.tick(.2f);check(normal.visibility==0&&share.visibility==0,"reverse retains both visible faces");
motion.reset();reverse.tick(.7f);check(normal.visibility==0&&share.visibility==4,"retired reverse cannot revive share face");
check(normal.animation==null&&share.animation==null,"reset releases both face matrices");
View prefix=new View();prefix.width=100;share.width=500;normal.left=100;
ShareRailTransition entire=new ShareRailTransition(normal,share,prefix);entire.show(true);ValueAnimator full=ValueAnimator.latest;
full.tick(.25f);check(prefix.animation!=null&&normal.animation!=null,"normal content cell rotates with application face");
check(Math.abs(prefix.animation.destination[2]-normal.animation.destination[0]-100)<.002f,"split normal cells use one physical cube projection");
full.tick(1);check(prefix.visibility==4&&normal.visibility==4&&share.visibility==0,"content button disappears on share face");
entire.show(false);ValueAnimator back=ValueAnimator.latest;back.tick(.5f);
check(prefix.visibility==0&&prefix.animation!=null,"content button returns with front cube face");
entire.reset();back.tick(1);check(prefix.visibility==0&&prefix.animation==null,"retired callback cannot overwrite content reset");
entire.showShareOnly();check(share.visibility==0&&prefix.visibility==4&&normal.visibility==4,"video overlay exposes only share targets without content cell");
check(share.animation==null&&entire.mapSharePoint(new float[]{25,50}),"share-only overlay starts with identity drop coordinates");
entire.reset();check(prefix.visibility==0&&share.visibility==4,"share-only retirement restores ordinary row ownership");
prefix.left=12;normal.left=92;entire.show(true);ValueAnimator.latest.tick(.25f);
float scale=(normal.animation.destination[2]-normal.animation.destination[0])/normal.width;
float prefixEdge=prefix.animation.destination[2]+prefix.left,appPoint=normal.animation.destination[0]+normal.left+20*scale;
check(Math.abs(prefixEdge-appPoint)<.002f,"content inset and tightened application margin keep the same physical projection");
entire.reset();check(normal.left==92&&prefix.left==12,"animation never rewrites real rail spacing");
ValueAnimator.enabled=false;entire.show(true);check(share.visibility==0&&normal.visibility==4&&prefix.visibility==4,"reduced motion enters final face immediately");
check(share.animation==null&&entire.mapSharePoint(new float[]{25,50}),"reduced motion retains exact endpoint hit coordinates");
entire.show(false);check(normal.visibility==0&&prefix.visibility==0&&share.visibility==4,"reduced motion restores immediately");
entire.setPrefixEnabled(false);
entire.reset();entire.show(true);entire.show(false);
check(prefix.visibility==4&&normal.visibility==0,"disabled content cell stays hidden after reset and cube animation");
entire.setPrefixEnabled(true);
check(prefix.visibility==0,"enabling content restores the cell on the normal face");
System.out.println("Production share rail motion checks="+checks+" passed");
}}''',
}
paths = []
for name, body in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf-8')
    paths.append(str(path))
paths += [str(src / 'ShareRailTransition.java'), str(src / 'OverlayMotionSpec.java'), str(src / 'ShareCubeGeometry.java')]
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), *paths], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.ShareRailMotionRegression'], check=True)
