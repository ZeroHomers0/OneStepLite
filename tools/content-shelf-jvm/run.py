"""Replay the real shelf store with in-memory metadata and owned URI capability doubles."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'org/json/JSONException.java':'package org.json;public class JSONException extends Exception{}',
'org/json/JSONObject.java':'''package org.json;public class JSONObject{public java.util.Map<String,Object> data=new java.util.LinkedHashMap<>();
public JSONObject put(String k,Object v)throws JSONException{data.put(k,v);return this;}public Object remove(String k){return data.remove(k);}
public String optString(String k){return String.valueOf(data.getOrDefault(k,""));}public boolean optBoolean(String k){return Boolean.TRUE.equals(data.get(k));}
public boolean has(String k){return data.containsKey(k);}public JSONObject copy(){JSONObject c=new JSONObject();c.data.putAll(data);return c;}}''',
'org/json/JSONArray.java':'''package org.json;import java.util.*;public class JSONArray{static Map<String,List<JSONObject>> snapshots=new HashMap<>();
List<JSONObject> rows=new ArrayList<>();public JSONArray(){}public JSONArray(String s){for(JSONObject o:snapshots.getOrDefault(s,List.of()))rows.add(o.copy());}
public int length(){return rows.size();}public JSONArray put(JSONObject o){rows.add(o);return this;}public JSONObject getJSONObject(int n)throws JSONException{return rows.get(n);}
public JSONObject optJSONObject(int n){return rows.get(n);}public String toString(){String key="snapshot:"+UUID.randomUUID();List<JSONObject> copy=new ArrayList<>();for(JSONObject o:rows)copy.add(o.copy());snapshots.put(key,copy);return key;}}''',
'android/net/Uri.java':'package android.net;public record Uri(String authority,String id){public String getAuthority(){return authority;}public String getLastPathSegment(){return id;}}',
'android/content/Intent.java':'package android.content;public class Intent{public static final int FLAG_GRANT_READ_URI_PERMISSION=1;}',
'android/os/PersistableBundle.java':'''package android.os;public class PersistableBundle{public boolean sensitive;public boolean getBoolean(String k){return sensitive;}}''',
'android/content/ClipData.java':'''package android.content;import java.util.*;public class ClipData{public List<Item> items=new ArrayList<>();public Description description=new Description();
public int getItemCount(){return items.size();}public Item getItemAt(int i){return items.get(i);}public Description getDescription(){return description;}
public static class Description{public android.os.PersistableBundle extras=new android.os.PersistableBundle();public android.os.PersistableBundle getExtras(){return extras;}}
public static class Item{public String text;public android.net.Uri uri;public Item(String t){text=t;}public Item(android.net.Uri u){uri=u;}public CharSequence getText(){return text;}}}''',
'android/os/Bundle.java':'''package android.os;public class Bundle extends java.util.HashMap<String,Object>{public void putString(String k,String v){put(k,v);}
public String getString(String k){return (String)get(k);}public void putParcelable(String k,Object v){put(k,v);}public <T>T getParcelable(String k,Class<T> c){return c.cast(get(k));}
public void putStringArrayList(String k,java.util.ArrayList<String> v){put(k,v);}@SuppressWarnings("unchecked")public java.util.ArrayList<String> getStringArrayList(String k){return (java.util.ArrayList<String>)get(k);}}''',
'android/content/Context.java':'''package android.content;public class Context{public static final int MODE_PRIVATE=0;public Prefs prefs=new Prefs();public int grants;
public Prefs getSharedPreferences(String n,int m){return prefs;}public void grantUriPermission(String p,android.net.Uri u,int f){grants++;}
public static class Prefs{public String stored="[]";public boolean allow=true;public String getString(String k,String d){return stored;}public Editor edit(){return new Editor();}
public class Editor{String next;public Editor putString(String k,String v){next=v;return this;}public boolean commit(){if(allow)stored=next;return allow;}}}}''',
'io/github/zerohomers0/onesteplite/ContentShelfRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.content.*;import android.os.*;import org.json.*;import java.util.*;
class Packages{static final String SYSTEM_UI="com.android.systemui";}
class ContentInboxProvider{static final String AUTHORITY="owned";static JSONArray rows(Context c){return new JSONArray(c.prefs.stored);}
static android.net.Uri uri(String id){return new android.net.Uri(AUTHORITY,id);}}
class DragCacheStore{static ClipData promoteForStage(Context c,ClipData clip)throws Exception{return clip;}}
class SharePayload{ClipData clip;List<android.net.Uri> uris=new ArrayList<>();String text="";SharePayload(ClipData source){
if(source==null||source.items.isEmpty()||source.items.size()>32)throw new IllegalArgumentException();clip=source;
for(ClipData.Item i:source.items){if(i.uri!=null)uris.add(i.uri);if(i.text!=null)text+=i.text;}}}
public class ContentShelfRegression{static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
static ClipData text(String s){ClipData c=new ClipData();c.items.add(new ClipData.Item(s));return c;}
static int count(Context c){return new JSONArray(ContentShelfStore.call(c,"shelf_list",null).getString("rows")).length();}
static void denied(Runnable r,String why){boolean failed=false;try{r.run();}catch(RuntimeException expected){failed=true;}check(failed,why);}
public static void main(String[] args)throws Exception{Context c=new Context();
ContentShelfStore.stage(c,text("第一条"));check(count(c)==1,"text can be staged");ContentShelfStore.stage(c,text("第一条"));
check(count(c)==1,"duplicate staging is idempotent");ClipData multi=text("第二条");multi.items.add(new ClipData.Item("第三条"));ContentShelfStore.stage(c,multi);
check(count(c)==3,"multiple text items stay separate");
JSONArray saved=ContentInboxProvider.rows(c);String id=saved.optJSONObject(0).optString("id");Bundle remove=new Bundle();remove.putStringArrayList("ids",new ArrayList<>(List.of(id)));
ContentShelfStore.call(c,"shelf_remove",remove);check(count(c)==2,"remove selected shelf item");
check(ContentInboxProvider.rows(c).length()==3,"remove keeps backing data readable until normal expiry");
ContentShelfStore.stage(c,text("第一条"));check(count(c)==3&&ContentInboxProvider.rows(c).length()==3,"restage reuses retained row");
for(int i=3;i<64;i++)ContentShelfStore.stage(c,text("row"+i));check(count(c)==64,"shelf holds exactly 64 entries");
ContentShelfStore.stage(c,text("第一条"));check(count(c)==64,"duplicate at capacity still succeeds");
Context full=c;denied(()->ContentShelfStore.stage(full,text("overflow")),"65th item rejected");check(count(c)==64,"overflow leaves saved shelf intact");
ContentShelfStore.call(c,"shelf_remove",new Bundle());check(count(c)==0&&ContentInboxProvider.rows(c).length()==64,"clear unmarks without deleting records");
c=new Context();JSONArray rows=new JSONArray();rows.put(new JSONObject().put("id","photo").put("mime","image/jpeg").put("delete_after",1L));c.prefs.stored=rows.toString();
ClipData image=new ClipData();image.items.add(new ClipData.Item(ContentInboxProvider.uri("photo")));ContentShelfStore.stage(c,image);
check(count(c)==1&&c.grants>0,"owned image grants SystemUI read access");check(!ContentInboxProvider.rows(c).optJSONObject(0).has("delete_after"),"explicit stage cancels short image cleanup");
ClipData foreign=new ClipData();foreign.items.add(new ClipData.Item(new android.net.Uri("foreign","photo")));Context target=c;
denied(()->ContentShelfStore.stage(target,foreign),"external URI cannot bypass import");check(count(c)==1,"rejected external item leaves shelf unchanged");
c.prefs.allow=false;denied(()->ContentShelfStore.stage(target,text("unsaved")),"commit failure reported");check(count(c)==1,"failed commit keeps old shelf");c.prefs.allow=true;
check(ContentShelfStore.call(c,"shelf_list",null).getParcelable("recent",ClipData.class)==null,"shelf no longer exposes recent share payloads");
denied(()->ContentShelfStore.call(target,"recent_share",null),"removed recent-share operation rejected");
System.out.println("Production content shelf checks="+checks+" passed (metadata doubles; filesystem retention covered separately)");}}
'''
}
paths=[]
for name,body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'ContentShelfStore.java'))
paths.append(str(src/'ContentInboxPolicy.java'))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.ContentShelfRegression'],check=True)
