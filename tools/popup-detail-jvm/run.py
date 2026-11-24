"""Replay detail ownership: only the outer sheet clips corners, never the inner action buttons."""
from pathlib import Path
import os,subprocess,sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=(src/'ContentPanelLayout.java').read_text(encoding='utf-8-sig')
def member(signature):
 start=source.index(signature);end=source.index('{',start)+1;depth=1
 while depth:
  depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
files={
'android/animation/ValueAnimator.java':'package android.animation;public class ValueAnimator{public static boolean areAnimatorsEnabled(){return false;}}',
'android/view/View.java':'''package android.view;public class View{public static final int VISIBLE=0,GONE=8;public int visibility,padding=14;public boolean clip=true;public Object background=new Object(),outline=new Object();public void setVisibility(int n){visibility=n;}public void setBackground(Object v){background=v;}public void setPadding(int a,int b,int c,int d){padding=a;}public void setClipToOutline(boolean v){clip=v;}public void setOutlineProvider(Object v){outline=v;}public void setTranslationX(float v){}public void setAlpha(float v){}public View animate(){return this;}public void cancel(){}public View translationX(float v){return this;}public View alpha(float v){return this;}public View setDuration(long v){return this;}public void start(){}}''',
'PopupDetailRegression.java':'''package io.github.zerohomers0.onesteplite;import android.view.View;import java.util.*;
class ContentPanelLayout extends View{View detail,pages=new View();List<View> children=new ArrayList<>();int getChildCount(){return children.size();}View getChildAt(int i){return children.get(i);}void addView(View v,LayoutParams p){children.add(v);}void removeView(View v){children.remove(v);}int dp(int v){return v;}static class LayoutParams{LayoutParams(int a,int b,int c){}}
__METHODS__
}
public class PopupDetailRegression{static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}public static void main(String[] args){
ContentPanelLayout shell=new ContentPanelLayout();View tabs=new View(),actions=new View();shell.children.add(tabs);shell.children.add(actions);View words=new View();
shell.push(words);check(!words.clip&&words.outline==null,"inner 28dp sheet cannot clip the action button's outside corner");
check(shell.clip&&shell.outline!=null,"only outer sheet retains the rounded glass boundary");
check(words.padding==0&&words.background==null,"detail does not create a second inset or material layer");
check(tabs.visibility==View.GONE&&actions.visibility==View.GONE,"primary controls are hidden behind detail");
shell.pop(false);check(shell.children.size()==2&&tabs.visibility==View.VISIBLE&&actions.visibility==View.VISIBLE,"return restores original primary page");
check(actions.clip&&actions.padding==14,"primary controls keep their original geometry");
shell.push(words);View picture=new View();shell.push(picture);check(!shell.children.contains(words)&&!picture.clip&&picture.outline==null,"every secondary page gets the same clipping policy");
shell.pop(false);shell.pop(false);check(shell.children.size()==2,"duplicate return does not lose primary content");
System.out.println("Production popup detail clipping checks="+checks+" passed");}}
'''.replace('__METHODS__',member('    void push(')+'\n'+member('    void pop('))
}
paths=[]
for name,body in files.items():
 path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf-8');paths.append(str(path))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN']);subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.PopupDetailRegression'],check=True)
