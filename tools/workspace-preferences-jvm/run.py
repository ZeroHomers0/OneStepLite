"""Replay production pin persistence and wallpaper contrast without Android services."""
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
style = (src / 'HyperOsStyle.java').read_text(encoding='utf-8-sig')
start = style.index('    static int wallpaperTextColor(')
end = style.index('{', start) + 1
depth = 1
while depth:
    depth += (style[end] == '{') - (style[end] == '}')
    end += 1

def style_method(signature):
    a=style.index(signature);b=style.index('{',a)+1;depth=1
    while depth:
        depth+=(style[b]=='{')-(style[b]=='}');b+=1
    return style[a:b]
night=style_method('    static boolean dark(')+'\n'+style_method('    static int topWallpaperTint(')
files = {
    'android/content/Context.java': 'package android.content; public class Context {public android.content.res.Resources resources=new android.content.res.Resources();public android.content.res.Resources getResources(){return resources;}public Object getContentResolver(){return this;}}',
    'android/content/res/Resources.java':'package android.content.res;public class Resources {public Configuration config=new Configuration();public Configuration getConfiguration(){return config;}}',
    'android/content/res/Configuration.java':'package android.content.res;public class Configuration {public int uiMode=16;}',
    'android/provider/Settings.java': 'package android.provider; public class Settings {public static class Secure {}}',
    'android/graphics/Bitmap.java': '''package android.graphics; public class Bitmap {
        public int left,right;public boolean recycled;public Bitmap(int a,int b){left=a;right=b;}
        public int getWidth(){return 80;}public int getHeight(){return 40;}
        public boolean isRecycled(){return recycled;}
        public int getPixel(int x,int y){if(x<0||x>=80||y<0||y>=40)throw new AssertionError("sample outside bitmap");return x<40?left:right;}}
    ''',
    'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed; public class XposedHelpers {
        public static String saved="b/Activity\\na/Activity\\ngone/Activity";public static int writes;
        public static Object callStaticMethod(Class<?> c,String name,Object...args){
            if(name.equals("getStringForUser"))return saved;
            if(name.equals("putStringForUser")){saved=(String)args[2];writes++;return true;}
            throw new AssertionError(name);}}
    ''',
    'io/github/zerohomers0/onesteplite/CurrentUser.java': 'package io.github.zerohomers0.onesteplite;class CurrentUser {static int id(){return 0;}}',
    'io/github/zerohomers0/onesteplite/ShortcutSettingsClient.java': '''package io.github.zerohomers0.onesteplite;
        class ShortcutSettingsClient {static final String APPS="apps",LEGACY_APPS="onesteplite_quick_app_pins_v1";
        static String override;static boolean available;
        static String readForUser(android.content.Context c,String k,int user){return override;}
        static Boolean writeForUser(android.content.Context c,String k,String value,int user){if(!available)return null;override=value;return true;}}''',
    'io/github/zerohomers0/onesteplite/QuickApp.java': '''package io.github.zerohomers0.onesteplite;
        class QuickApp {Component component;QuickApp(String key){component=new Component(key);}
        static class Component {String key;Component(String key){this.key=key;}String flattenToString(){return key;}}}
    ''',
    'io/github/zerohomers0/onesteplite/HyperOsStyle.java': 'package io.github.zerohomers0.onesteplite;import android.graphics.Bitmap;import android.content.Context;class HyperOsStyle {\n' + style[start:end] + '\n'+night+'\n}',
    'io/github/zerohomers0/onesteplite/PreferencesRegression.java': '''package io.github.zerohomers0.onesteplite;
        import java.util.List;import android.graphics.Bitmap;import de.robv.android.xposed.XposedHelpers;
        public class PreferencesRegression {
            static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
            public static void main(String[]args){
                var ctx=new android.content.Context();String saved=XposedHelpers.saved;
                check(HyperOsStyle.topWallpaperTint(ctx)==0,"day mode preserves wallpaper blur colours");
                ctx.resources.config.uiMode=32;
                check(HyperOsStyle.topWallpaperTint(ctx)==0x99080a10,"night mode uses a translucent dark wash over the same blur");
                ctx.resources.config.uiMode=16;
                check(HyperOsStyle.topWallpaperTint(ctx)==0,"switching back to day removes dark wash");
                check(QuickAppPins.arrange(ctx,List.of()).isEmpty(),"cold cache renders empty");
                check(saved.equals(XposedHelpers.saved)&&XposedHelpers.writes==0,"cold cache never deletes saved pins");
                var a=new QuickApp("a/Activity");var b=new QuickApp("b/Activity");
                check(QuickAppPins.arrange(ctx,List.of(a,b)).equals(List.of(b,a)),"warm cache restores saved selection order");
                check(saved.equals(XposedHelpers.saved)&&XposedHelpers.writes==0,"rendering never persists an incomplete installed list");
                check(!QuickAppPins.save(ctx,10,List.of()),"another user cannot overwrite pins");
                check(QuickAppPins.save(ctx,0,List.of("a/Activity"))&&XposedHelpers.writes==1,"explicit selection persists");
                check(QuickAppPins.read(ctx,0).equals(List.of("a/Activity")),"explicit selection round trips");
                ShortcutSettingsClient.available=true;ShortcutSettingsClient.override="b/Activity";
                check(QuickAppPins.read(ctx,0).equals(List.of("b/Activity")),"module preferences override legacy secure pins");
                check(QuickAppPins.save(ctx,0,List.of("a/Activity","b/Activity")),"workspace writes the same provider used by settings");
                check(QuickAppPins.read(ctx,0).equals(List.of("a/Activity","b/Activity")),"shared order round trips");
                check(XposedHelpers.writes==1,"provider save never rewrites the legacy secure value");
                int black=0xff000000,white=0xffffffff;
                for(int background:new int[]{black,white,0xff202020,0xffeeeeee}) {
                    int expected=(background==black||background==0xff202020)?white:black;
                    check(HyperOsStyle.wallpaperTextColor(new Bitmap(background,background),800,400,0,0,800,400,0)==expected,"uniform wallpaper uses readable ink");
                }
                var split=new Bitmap(black,white);
                check(HyperOsStyle.wallpaperTextColor(split,800,400,0,0,400,400,0)==white,"left dark region uses white");
                check(HyperOsStyle.wallpaperTextColor(split,800,400,400,0,400,400,0)==black,"right bright region uses black");
                check(HyperOsStyle.wallpaperTextColor(split,800,400,-100,-100,20,20,0)==white,"offscreen animation sample is clamped");
                check(HyperOsStyle.wallpaperTextColor(null,800,400,0,0,800,400,123)==123,"missing wallpaper keeps fallback");
                split.recycled=true;
                check(HyperOsStyle.wallpaperTextColor(split,800,400,0,0,800,400,123)==123,"recycled wallpaper keeps fallback");
                check(HyperOsStyle.wallpaperTextColor(new Bitmap(black,black),0,0,0,0,800,400,123)==123,"missing screen geometry keeps fallback");
                System.out.println("Production pin persistence and wallpaper contrast checks="+checks+" passed");
            }
        }
    ''',
}
paths = []
for name, text in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')
    paths.append(str(path))
paths += [str(src / 'QuickAppPins.java'), str(src / 'QuickAppOrder.java'), str(src / 'QuickAppReconcilePolicy.java')]
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), *paths], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.PreferencesRegression'], check=True)
