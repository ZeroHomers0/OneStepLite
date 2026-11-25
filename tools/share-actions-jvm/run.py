"""Replay built-in routes, missing receivers and published shortcut identity without opening apps."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2])
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/graphics/drawable/Drawable.java':'package android.graphics.drawable;public class Drawable{}',
'android/app/SearchManager.java':'package android.app;public class SearchManager{public static final String QUERY="query";}',
'android/net/Uri.java':'''package android.net;public record Uri(String value){public static Uri parse(String s){return new Uri(s);}
public static String encode(String s){return java.net.URLEncoder.encode(s,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");}
public String toString(){return value;}}''',
'android/util/Base64.java':'''package android.util;public class Base64{public static final int NO_WRAP=1,URL_SAFE=2;
public static String encodeToString(byte[] b,int f){return java.util.Base64.getUrlEncoder().encodeToString(b);}
public static byte[] decode(String s,int f){return java.util.Base64.getUrlDecoder().decode(s);}}''',
'android/content/ComponentName.java':'''package android.content;public record ComponentName(String pkg,String cls){
public String getPackageName(){return pkg;}public String getClassName(){return cls;}public String flattenToString(){return pkg+"/"+cls;}
public static ComponentName unflattenFromString(String s){int n=s==null?-1:s.indexOf('/');return n<1?null:new ComponentName(s.substring(0,n),s.substring(n+1));}}''',
'android/content/ClipDescription.java':'''package android.content;public class ClipDescription{String[] types;
public ClipDescription(String l,String[] t){types=t;}public int getMimeTypeCount(){return types.length;}public String getMimeType(int i){return types[i];}
public boolean hasMimeType(String s){for(String t:types)if(t.equals(s)||s.endsWith("/*")&&t.startsWith(s.substring(0,s.length()-1)))return true;return false;}}''',
'android/content/ClipData.java':'''package android.content;public class ClipData{public Item item;public ClipDescription types;
public ClipData(String l,String[] t,Item i){types=new ClipDescription(l,t);item=i;}public static ClipData newPlainText(String l,String t){return new ClipData(l,new String[]{"text/plain"},new Item(t));}
public static class Item{public String text;public android.net.Uri uri;public Item(String t){text=t;}public Item(android.net.Uri u){uri=u;}}}''',
'android/content/Intent.java':'''package android.content;import java.util.*;public class Intent{
public static final String ACTION_SEND="send",ACTION_SEND_MULTIPLE="multi",ACTION_EDIT="edit",ACTION_WEB_SEARCH="search",ACTION_VIEW="view",EXTRA_TEXT="text",EXTRA_SHORTCUT_ID="shortcut";
public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_GRANT_READ_URI_PERMISSION=2;public String action,type,pkg;public ComponentName component;public android.net.Uri data;public ClipData clip;public int flags;public Map<String,Object> extras=new HashMap<>();
public Intent(String a){action=a;}public Intent(String a,android.net.Uri u){action=a;data=u;}public Intent setComponent(ComponentName c){component=c;return this;}
public Intent setPackage(String p){pkg=p;return this;}public Intent setType(String t){type=t;return this;}public Intent setDataAndType(android.net.Uri u,String t){data=u;type=t;return this;}
public Intent setClipData(ClipData c){clip=c;return this;}public Intent addFlags(int f){flags|=f;return this;}public Intent putExtra(String k,String v){extras.put(k,v);return this;}
public String getStringExtra(String k){return (String)extras.get(k);}}''',
'android/os/Bundle.java':'''package android.os;public class Bundle{java.util.Map<String,String> values=new java.util.HashMap<>();public void putString(String k,String v){values.put(k,v);}public String getString(String k,String d){return values.getOrDefault(k,d);}}''',
'android/content/pm/ResolveInfo.java':'''package android.content.pm;public class ResolveInfo{public ActivityInfo activityInfo=new ActivityInfo();
public static class ActivityInfo{public boolean exported=true;public String packageName="installed",name="Share";}
public CharSequence loadLabel(PackageManager p){return "搴旂敤鍒嗕韩";}public android.graphics.drawable.Drawable loadIcon(PackageManager p){return new android.graphics.drawable.Drawable();}}''',
'android/content/pm/PackageManager.java':'''package android.content.pm;import java.util.*;import android.content.*;public class PackageManager{
public Set<String> available=new HashSet<>();public int exactTextQueries;public List<ResolveInfo> queryIntentActivities(Intent i,int f){
if(Intent.ACTION_SEND.equals(i.action) && "text/plain".equals(i.type))exactTextQueries++;
String key=i.component!=null?i.component.flattenToString():i.pkg!=null?i.pkg+":"+i.action:i.action.equals(Intent.ACTION_VIEW)?i.data.toString().split(":")[0]:i.action;
return available.contains(key)?List.of(new ResolveInfo()):List.of();}}''',
'android/content/Context.java':'''package android.content;public class Context{public static final int MODE_PRIVATE=0;public String selected="";public boolean paste;
public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public android.content.pm.PackageManager getPackageManager(){return pm;}
public String getPackageName(){return "module";}public Prefs getSharedPreferences(String n,int m){return new Prefs();}public class Prefs{public String getString(String k,String d){return selected;}}
public Resolver getContentResolver(){return new Resolver();}public class Resolver{public android.os.Bundle call(Object u,String m,String a,Object b){return null;}}
public android.graphics.drawable.Drawable getDrawable(int id){return new android.graphics.drawable.Drawable();}}''',
'android/R.java':'package android;public class R{public static class drawable{public static final int ic_menu_share=1;}}',
'io/github/zerohomers0/onesteplite/ShareActionsRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.content.*;import java.util.*;
class ContentInboxProvider{static final String MODULE="module",AUTHORITY="owned";}
class WorkspaceSettingsStore{static final Object URI=new Object();}
class EditorPasteCompat{static boolean available(Context c){return c.paste;}}
class ShareActionIcon extends android.graphics.drawable.Drawable{ShareActionIcon(String key){}}
class VisualSearchActivity{}
class ContentAccessCompat{static String saved;static String pins(Context c){return saved;}}
class QuickAppOrder{static List<String> decode(String s){return s==null||s.isEmpty()?List.of():List.of(s.split("\\n"));}}
class ConversationShareCompat{static List<ShareTarget> load(Context c,ClipDescription d,int n,boolean e){return List.of();}}
class SharePayload{ClipData clip;String text,mime;ArrayList<android.net.Uri> uris=new ArrayList<>();
SharePayload(ClipData c){clip=c;text=c.item.text==null?"":c.item.text;mime=c.types.getMimeType(0);if(c.item.uri!=null)uris.add(c.item.uri);}
Intent intent(ComponentName target){return new Intent(Intent.ACTION_SEND).setType(mime).setComponent(target).putExtra(Intent.EXTRA_TEXT,text);}}
public class ShareActionsRegression{
static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
static ShareTarget find(List<ShareTarget> values,String op){for(ShareTarget t:values)if(ShareActions.operation(t.component).equals(op))return t;return null;}
public static void main(String[] args){Context c=new Context();SharePayload text=new SharePayload(ClipData.newPlainText("t","涓婃捣 铏规ˉ &?"));
c.pm.available.add("https");Intent map=ShareActions.intent(c,text,ShareActions.MAP);
check(map.data.toString().startsWith("https://www.bing.com/maps?q="),"missing map uses browser");
check(map.data.toString().contains("%20")&&map.data.toString().contains("%26%3F"),"query safely encoded");
c.pm.available.add("geo");check(ShareActions.intent(c,text,ShareActions.MAP).data.toString().startsWith("geo:"),"installed map preferred");
check(ShareActions.intent(c,text,ShareActions.SEARCH).data.toString().startsWith("https:"),"search browser fallback");
c.pm.available.add("search");check(ShareActions.intent(c,text,ShareActions.SEARCH).action.equals("search"),"native search preferred");
check(ShareActions.intent(c,text,ShareActions.TRANSLATE).data.toString().contains("translator"),"translation browser fallback");
c.pm.available.add("android.intent.action.TRANSLATE");check(ShareActions.intent(c,text,ShareActions.TRANSLATE).action.contains("TRANSLATE"),"native translation preferred");
ClipDescription types=new ClipDescription("t",new String[]{"text/plain"});List<ShareTarget> edit=ShareActions.load(c,types,1,true);
check(!ShareActions.known("note"),"duplicate built-in note action removed");
check(ShareActions.retired("module/drag_operation_note"),"old built-in note pin migrated");
check(find(edit,ShareActions.MAP).icon instanceof ShareActionIcon,"built-in uses original function icon");
check(!ShareActions.known("paste")&&ShareActions.retired("module/drag_operation_paste"),"toolbar paste removed while input service stays separate");
SharePayload image=new SharePayload(new ClipData("i",new String[]{"image/jpeg"},new ClipData.Item(android.net.Uri.parse("content://owned/1"))));
ClipDescription imageType=new ClipDescription("i",new String[]{"image/jpeg"});
check(find(ShareActions.load(c,imageType,1,true),ShareActions.IMAGE_SEARCH).enabled,"built-in web image search needs no configured app");
c.selected="pictures/Search";c.pm.available.add(c.selected);c.pm.available.add("edit");
check(ShareActions.intent(c,image,ShareActions.IMAGE_SEARCH).component.getClassName().equals(VisualSearchActivity.class.getName()),"image search uses fixed internal web route");
check(!find(ShareActions.load(c,imageType,2,false),ShareActions.EDIT).enabled,"multi image cannot invoke single editor");
check(find(ShareActions.load(c,imageType,1,false),ShareActions.EDIT).enabled,"single image editor available");
check((ShareActions.intent(c,image,ShareActions.EDIT).flags&2)!=0,"image editor receives read grant");
ComponentName activity=new ComponentName("chat","Share");ComponentName conversation=ShareRoutes.conversation(activity,"浼氳瘽/1:馃榾");
check(ShareRoutes.activity(conversation).equals(activity),"conversation keeps real activity");
Intent routed=ShareRoutes.intent(text,conversation);check(routed.getStringExtra(Intent.EXTRA_SHORTCUT_ID).equals("浼氳瘽/1:馃榾"),"shortcut identity round trips");
check(routed.clip==text.clip,"conversation keeps full clip");
Intent copied=new Intent("send");ShareRoutes.copyShortcut(routed,copied);check(copied.getStringExtra(Intent.EXTRA_SHORTCUT_ID).equals("浼氳瘽/1:馃榾"),"shortcut survives module proxy");
boolean rejected=false;try{ShareRoutes.conversation(activity,"x".repeat(257));}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"bounded conversation ID");
List<String> missing=List.of("removed/Share");check(ShareTargetCatalog.load(c,types,1,false,missing).isEmpty(),"uninstalled pins hidden");
check(!ShareTargetCatalog.load(c,types,1,true,missing).get(0).enabled,"uninstalled pin remains removable in manager");
c.pm.available.remove(c.selected);check(ShareTargetCatalog.load(c,imageType,1,false,List.of("module/drag_operation_image_search")).size()==1,"web image search does not depend on removed app");
ContentAccessCompat.saved=null;
var matches=ShareTargetCatalog.queryMatches(c,types,1);int before=c.pm.exactTextQueries;
ShareTargetCatalog.loadForDrag(c,types,1,matches);
check(c.pm.exactTextQueries==before,"share rail reuses the exact queries used for small-window packages");
ContentAccessCompat.saved="";check(ShareTargetCatalog.load(c,types,1,false).isEmpty(),"explicit empty configuration stays empty");
check(!ShareActions.known("custom_rule"),"no configurable rule route");
Context empty=new Context();check(find(ShareActions.load(empty,types,1,false),ShareActions.SEARCH)==null,"no browser or search handler hides operation");
check(!find(ShareActions.load(empty,types,1,true),ShareActions.MAP).enabled,"missing browser and map disabled in manager");
check(find(ShareActions.load(empty,types,1,false),ShareActions.STAGE).enabled,"local shelf works without external apps");
check(ShareTargetCatalog.contentMime(new ClipDescription("mixed",new String[]{"image/jpeg","image/png"})).equals("image/*"),"whole image group queries common image type");
check(ShareTargetCatalog.contentMime(new ClipDescription("mixed",new String[]{"image/jpeg","application/pdf"})).equals("*/*"),"mixed files do not enable image-only targets");
check(ShareTargetCatalog.contentMime(new ClipDescription("alt",new String[]{"text/plain","image/jpeg","text/uri-list"})).equals("image/jpeg"),"image URI text alternatives do not turn into text search");
check(ShareTargetCatalog.contentMime(types).equals("text/plain"),"plain text remains native single text share");
System.out.println("Production share actions checks="+checks+" passed");}}
'''
}
paths=[]
for name,body in files.items():
    path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf-8');paths.append(str(path))
paths += [str(src/'ShareActions.java'),str(src/'ShareRoutes.java'),str(src/'ShareTargetCatalog.java'),str(src/'ShareTarget.java'),str(src/'ContentSharePolicy.java')]
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.ShareActionsRegression'],check=True)
