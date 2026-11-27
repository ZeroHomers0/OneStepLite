"""Regression for bounded overlay refresh retries."""
import os
import sys as _sys, pathlib as _pathlib
_sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parents[1]))
from source_paths import package_dir
from pathlib import Path
import subprocess, sys
root = Path(__file__).resolve().parents[2]
src = package_dir(root)
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
source = (src/'WorkspaceRetainedSurfaceHooks.java').read_text(encoding='utf-8-sig')
a = source.index('    private void refreshOverlayAfterTransition() {')
b = source.index('{', a) + 1
depth = 1
while depth:
    depth += (source[b] == '{') - (source[b] == '}')
    b += 1
method = source[a:b].replace('host.', '').replace('WorkspaceHost.ACTIVE', 'ACTIVE').replace('WorkspaceHost.log', 'log')
body = r'''package io.github.zerohomers0.onesteplite;
public class OverlayRefreshRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static final int ACTIVE=1,MAX_OVERLAY_REFRESH_ATTEMPTS=24;
 static class View {static final int VISIBLE=0;boolean shown=true;float alpha;void setVisibility(int v){shown=v==VISIBLE;}void setAlpha(float a){alpha=a;}void requestLayout(){}void invalidate(){}Object getWindowToken(){return this;}}
 static class Root extends View {}
 static class WindowManager {void updateViewLayout(Object r,Object p){}}
 static class Slot {boolean closing;}
 static class Handler {Runnable delayed;int posts;void postDelayed(Runnable r,long d){delayed=r;posts++;}}
 Root root=new Root();WindowManager wm=new WindowManager();Object lp=new Object();Handler main=new Handler();
 Object cellMovePreview,promotion;boolean transferBusy,keyguardLocked;int scene=ACTIVE;Slot[] slots={new Slot()};
 int overlayRefreshAttempts;boolean overlayRefreshScheduled=true;final Runnable overlayRefresh=this::refreshOverlayAfterTransition;static void log(String s){}
 __METHOD__
 public static void main(String[] args){
  OverlayRefreshRegression h=new OverlayRefreshRegression();h.promotion=new Object();
  h.refreshOverlayAfterTransition();
  check(h.main.posts==1&&h.main.delayed!=null,"waiting transition keeps one bounded retry");
  for(int i=0;i<30&&h.main.delayed!=null;i++){Runnable next=h.main.delayed;h.main.delayed=null;next.run();}
  check(h.main.delayed==null&&!h.overlayRefreshScheduled,"stuck transition cannot poll forever");
  h=new OverlayRefreshRegression();h.cellMovePreview=null;h.promotion=null;h.transferBusy=false;h.refreshOverlayAfterTransition();
  check(h.root.shown&&h.root.alpha==1f&&!h.overlayRefreshScheduled,"ordinary transition refreshes once without scheduling");
  h=new OverlayRefreshRegression();h.keyguardLocked=true;h.refreshOverlayAfterTransition();check(h.main.delayed==null&&!h.overlayRefreshScheduled,"locked scene cancels the retry");
  System.out.println("Overlay refresh checks="+checks+" passed");
 }
}
'''.replace('__METHOD__', method)
p = out/'io/github/zerohomers0/onesteplite/OverlayRefreshRegression.java'
p.parent.mkdir(parents=True, exist_ok=True)
p.write_text(body, encoding='utf-8')
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), str(p)], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.OverlayRefreshRegression'], check=True)
