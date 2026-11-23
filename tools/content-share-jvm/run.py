"""Compile the real payload and lease code against deterministic Android capability doubles."""
from pathlib import Path
import os
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir

src = package_dir(Path(__file__).resolve().parents[2])
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
files = {
    'android/net/Uri.java': '''package android.net; public record Uri(String value) {
 public static Uri parse(String s){return new Uri(s);}public String getScheme(){return value.split(":")[0];}
 public String toString(){return value;}}
''',
    'android/content/ComponentName.java': '''package android.content;public record ComponentName(String pkg,String cls){
 public String getPackageName(){return pkg;}public String getClassName(){return cls;}}
''',
    'android/content/ClipDescription.java': '''package android.content;public class ClipDescription {
 String[] types;public ClipDescription(String label,String[] types){this.types=types;}
 public ClipDescription(ClipDescription other){types=other.types.clone();}
 public int getMimeTypeCount(){return types.length;}public String getMimeType(int i){return types[i];}}
''',
    'android/content/ClipData.java': '''package android.content;import android.net.Uri;import java.util.*;
 public class ClipData {ClipDescription description;List<Item> items=new ArrayList<>();
 public ClipData(ClipDescription d,Item i){description=d;items.add(i);}public void addItem(Item i){items.add(i);}
 public ClipDescription getDescription(){return description;}public int getItemCount(){return items.size();}
 public Item getItemAt(int i){return items.get(i);}public static class Item {
 CharSequence text;Intent intent;Uri uri;public Item(CharSequence t,String html,Intent i,Uri u){text=t;intent=i;uri=u;}
 public CharSequence getText(){return text;}public Intent getIntent(){return intent;}public Uri getUri(){return uri;}}}
''',
    'android/content/Intent.java': '''package android.content;import java.util.*;public class Intent {
 public static final String ACTION_SEND="send",ACTION_SEND_MULTIPLE="multi",EXTRA_TEXT="text",EXTRA_STREAM="stream";
 public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_GRANT_READ_URI_PERMISSION=2;
 public String action,type;public int flags;public ClipData clip;public Map<String,Object> extras=new HashMap<>();
 public Intent(String action){this.action=action;}public Intent setType(String s){type=s;return this;}
 public Intent setComponent(ComponentName c){return this;}public Intent setPackage(String p){return this;}public Intent addFlags(int f){flags|=f;return this;}
 public Intent putExtra(String k,Object v){extras.put(k,v);return this;}public void setClipData(ClipData c){clip=c;}
 public Intent putParcelableArrayListExtra(String k,ArrayList<?> v){extras.put(k,v);return this;}}
''',
    'android/content/Context.java': '''package android.content;import android.net.Uri;public class Context {
 public int grants,revokes;public boolean fail;public Context getApplicationContext(){return this;}
 public void grantUriPermission(String p,Uri u,int mode){if(fail)throw new SecurityException();grants++;}
 public void revokeUriPermission(String p,Uri u,int mode){revokes++;}}
''',
    'android/os/Looper.java': 'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
    'android/os/Handler.java': '''package android.os;import java.util.*;public class Handler {
 public static List<Runnable> pending=new ArrayList<>();public Handler(Looper l){}
 public void postDelayed(Runnable r,long ms){pending.add(r);}public void removeCallbacks(Runnable r){pending.remove(r);}}
''',
    'android/os/Process.java': 'package android.os;public class Process{public static int myUid(){return 1000;}}',
    'android/util/Log.java': 'package android.util;public class Log{public static void w(String t,String m,Throwable e){}}',
    'io/github/zerohomers0/onesteplite/Tags.java': 'package io.github.zerohomers0.onesteplite;class Tags{static final String PROBE="test";}',
    'io/github/zerohomers0/onesteplite/ContentShareRegression.java': '''package io.github.zerohomers0.onesteplite;
import android.content.*;import android.net.Uri;import android.os.Handler;import java.util.*;
public class ContentShareRegression {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static ClipData clip(String mime,String text,Uri uri,Intent intent){return new ClipData(new ClipDescription("x",new String[]{mime}),new ClipData.Item(text,null,intent,uri));}
 static void rejected(Runnable r,String why){try{r.run();throw new AssertionError(why);}catch(IllegalArgumentException ok){checks++;}}
 public static void main(String[]args){
  ComponentName target=new ComponentName("receiver","Share");
  ClipData source=clip("text/plain","first",null,null);SharePayload text=new SharePayload(source);
  source.addItem(new ClipData.Item("late",null,null,null));
  check(text.clip.getItemCount()==1&&text.text.equals("first"),"source mutation cannot change sanitized payload");
  Intent send=text.intent(target);check(send.action.equals(Intent.ACTION_SEND)&&send.extras.get(Intent.EXTRA_TEXT).equals("first"),"text handoff");
  SharePayload url=new SharePayload(clip("text/uri-list",null,Uri.parse("https://example.com/a"),null));
  check(url.text.equals("https://example.com/a")&&url.uris.isEmpty(),"web URI is text, not a file grant");
  ClipData images=clip("image/jpeg",null,Uri.parse("content://source/one"),null);
  images.addItem(new ClipData.Item(null,null,null,Uri.parse("content://source/two")));
  SharePayload photo=new SharePayload(images);send=photo.intent(target);
  check(send.action.equals(Intent.ACTION_SEND_MULTIPLE)&&photo.uris.size()==2,"multiple images retained");
  check(send.clip!=null&&(send.flags&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0,"streams carry clip and read flag");
  ClipData mixed=new ClipData(new ClipDescription("images",new String[]{"image/jpeg","image/png"}),new ClipData.Item(null,null,null,Uri.parse("content://source/jpeg")));
  mixed.addItem(new ClipData.Item(null,null,null,Uri.parse("content://source/png")));
  check(new SharePayload(mixed).mime.equals("image/*"),"mixed image types retain image-family matching");
  ClipData documents=new ClipData(new ClipDescription("files",new String[]{"*/*"}),new ClipData.Item(null,null,null,Uri.parse("content://source/file")));
  check(new SharePayload(documents).mime.equals("*/*"),"generic file-picker content retains wildcard MIME");
  check(new SharePayload(images).intent(new ComponentName("receiver","")).action.equals(Intent.ACTION_SEND_MULTIPLE),"window package fallback retains multi-send");
  rejected(()->new SharePayload(clip("image/jpeg",null,Uri.parse("file:///private/a"),null)),"file path accepted");
  rejected(()->new SharePayload(clip("text/plain","x",null,new Intent("launch"))),"embedded launch intent accepted");
  rejected(()->new SharePayload(clip("text/plain","x".repeat(100001),null,null)),"oversize text accepted");
  rejected(()->new SharePayload(clip("text/plain",null,null,null)),"empty payload accepted");
  int[] releases={0};
  ShareReadLease first=new ShareReadLease(()->releases[0]++);
  ShareReadLease second=new ShareReadLease(()->releases[0]++);
  check(first.isOpen()&&second.isOpen()&&Handler.pending.size()==2,"source capabilities have independent deadlines");
  first.close();check(releases[0]==1&&second.isOpen(),"releasing one source cannot release another");
  first.close();check(releases[0]==1,"source release is idempotent");
  Handler.pending.get(0).run();check(releases[0]==2&&!second.isOpen()&&Handler.pending.isEmpty(),"expiry invalidates delayed handoff and removes timer");
  ShareReadLease failing=new ShareReadLease(()->{throw new java.io.IOException("dead source");});
  failing.close();check(!failing.isOpen()&&Handler.pending.isEmpty(),"dead source still retires capability and timer");
  System.out.println("Content payload and URI lease checks="+checks+" passed (Android doubles; device handoff pending)");
 }
}
''',
}
paths = []
for name, body in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf-8')
    paths.append(str(path))
# Explicit production inputs: adding a dependency requires reviewing this list.
paths.append(str(src / 'ContentSharePolicy.java'))
paths.append(str(src / 'SharePayload.java'))
paths.append(str(src / 'ShareReadLease.java'))
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), *paths], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.ContentShareRegression'], check=True)
