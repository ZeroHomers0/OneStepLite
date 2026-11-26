"""Replay the hidden MediaStore publication used before a native image drag."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src = package_dir(Path(__file__).resolve().parents[2])
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
relay = (src / 'PortalRelay.java').read_text(encoding='utf-8-sig')
if 'MediaStoreImageBridge.publishUri(' not in relay:
    raise AssertionError('Portal image offers must publish a MediaStore URI before native drag')
if relay.index('MediaStoreImageBridge.publishUri(') > relay.index('PortalWire.call(target,PortalWire.OFFER,request)'):
    raise AssertionError('MediaStore publication must happen before the image offer')
files = {
'android/net/Uri.java': '''package android.net;
public final class Uri {private final String value;private Uri(String v){value=v;}public static Uri parse(String v){return new Uri(v);}
public String getScheme(){int i=value.indexOf(':');return i<0?null:value.substring(0,i);}public String getAuthority(){int i=value.indexOf("://");if(i<0)return null;int e=value.indexOf('/',i+3);return value.substring(i+3,e<0?value.length():e);}
public String toString(){return value;}public boolean equals(Object o){return o instanceof Uri&&value.equals(((Uri)o).value);}public int hashCode(){return value.hashCode();}}''',
'android/content/ContentValues.java': '''package android.content;import java.util.*;public class ContentValues {public final Map<String,Object> values=new LinkedHashMap<>();public void put(String k,String v){values.put(k,v);}public void put(String k,Integer v){values.put(k,v);}public Object get(String k){return values.get(k);}}''',
'android/content/ContentResolver.java': '''package android.content;import android.net.Uri;import java.io.*;import java.util.*;public class ContentResolver {
public static final Map<Uri,byte[]> bytes=new HashMap<>();public static final List<Uri> inserted=new ArrayList<>();public static final Map<Uri,ContentValues> updated=new HashMap<>();public static int imageNumber=1,fileNumber=1;
private Uri next(Uri collection,ContentValues values){String name=String.valueOf(values.get("DISPLAY_NAME"));Uri uri;if(".nomedia".equals(name))uri=Uri.parse("content://media/external_primary/file/"+(fileNumber++));else uri=Uri.parse("content://media/external/images/media/"+(imageNumber++));inserted.add(uri);bytes.put(uri,new byte[0]);return uri;}
public String getType(Uri uri){return "image/jpeg";}public Uri insert(Uri collection,ContentValues values){return next(collection,values);}
public InputStream openInputStream(Uri uri){byte[] b=bytes.get(uri);if(b==null)throw new IllegalArgumentException("unreadable");return new ByteArrayInputStream(b);}
public OutputStream openOutputStream(Uri uri,String mode){return new ByteArrayOutputStream(){public void close()throws IOException{ContentResolver.bytes.put(uri,toByteArray());super.close();}};}
public int update(Uri uri,ContentValues values,String s,String[] a){updated.put(uri,values);return 1;}public int delete(Uri uri,String s,String[] a){bytes.remove(uri);return 1;}}''',
'android/content/Context.java': '''package android.content;public class Context {private final ContentResolver resolver=new ContentResolver();public ContentResolver getContentResolver(){return resolver;}public Context getApplicationContext(){return this;}}''',
'android/os/Environment.java': 'package android.os;public class Environment{public static final String DIRECTORY_PICTURES="Pictures";}',
'android/os/Looper.java': 'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java': '''package android.os;import java.util.*;public class Handler {public static final List<Runnable> pending=new ArrayList<>();public Handler(Looper l){}public boolean postDelayed(Runnable r,long ms){pending.add(r);return true;}}''',
'android/provider/MediaStore.java': '''package android.provider;import android.net.Uri;public final class MediaStore {public static final String VOLUME_EXTERNAL_PRIMARY="external_primary";public static final class MediaColumns{public static final String DISPLAY_NAME="DISPLAY_NAME",MIME_TYPE="MIME_TYPE",RELATIVE_PATH="RELATIVE_PATH",IS_PENDING="IS_PENDING";}
public static final class Images{public static final class Media{public static final Uri EXTERNAL_CONTENT_URI=Uri.parse("content://media/external/images/media");}}
public static final class Files{public static Uri getContentUri(String volume){return Uri.parse("content://media/"+volume+"/file");}}}''',
'android/content/ClipDescription.java': '''package android.content;public class ClipDescription {private final String[] types;public ClipDescription(String label,String[] types){this.types=types.clone();}public ClipDescription(ClipDescription other){types=other.types.clone();}public int getMimeTypeCount(){return types.length;}public String getMimeType(int i){return types[i];}}''',
'android/content/ClipData.java': '''package android.content;import android.net.Uri;import java.util.*;public class ClipData {private final ClipDescription description;private final List<Item> items=new ArrayList<>();public ClipData(String label,String[] types,Item item){description=new ClipDescription(label,types);items.add(item);}public ClipData(ClipDescription d,Item item){description=d;items.add(item);}public ClipDescription getDescription(){return description;}public int getItemCount(){return items.size();}public Item getItemAt(int i){return items.get(i);}public void addItem(Item i){items.add(i);}public static class Item{private final Uri uri;public Item(Uri uri){this.uri=uri;}public Uri getUri(){return uri;}}}
''',
'android/util/Log.java': 'package android.util;public class Log{public static int w(String t,String m,Throwable e){return 0;}}',
'io/github/zerohomers0/onesteplite/ContentInboxProvider.java': 'package io.github.zerohomers0.onesteplite;final class ContentInboxProvider{static final String AUTHORITY="io.github.zerohomers0.onesteplite.content";}',
'io/github/zerohomers0/onesteplite/Tags.java': 'package io.github.zerohomers0.onesteplite;final class Tags{static final String PROBE="test";}',
'io/github/zerohomers0/onesteplite/PortalMediaRegression.java': '''package io.github.zerohomers0.onesteplite;
import android.content.*;import android.net.Uri;import android.os.Handler;import java.nio.charset.StandardCharsets;
public class PortalMediaRegression {static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
public static void main(String[] args){Context context=new Context();ContentResolver resolver=context.getContentResolver();Uri module=Uri.parse("content://io.github.zerohomers0.onesteplite.content/drag/id1");ContentResolver.bytes.put(module,"jpeg-bytes".getBytes(StandardCharsets.UTF_8));
Uri media=MediaStoreImageBridge.publishUri(context,module);check(media!=null&&"media".equals(media.getAuthority())&&media.toString().contains("/images/media/"),"portal image becomes a MediaStore image URI");check("jpeg-bytes".equals(new String(ContentResolver.bytes.get(media),StandardCharsets.UTF_8)),"published bytes match the bounded capture");check(ContentResolver.updated.containsKey(media)&&Integer.valueOf(0).equals(ContentResolver.updated.get(media).get("IS_PENDING")),"published image is committed");check(ContentResolver.inserted.stream().anyMatch(uri->uri.toString().contains("/file/")),"hidden folder marker was created");check(Handler.pending.size()==1,"published image has one cleanup deadline");
int before=ContentResolver.inserted.size();check(MediaStoreImageBridge.publishUri(context,Uri.parse("content://foreign/image"))==null&&ContentResolver.inserted.size()==before,"foreign URI is never copied into MediaStore");
ClipData input=new ClipData("image",new String[]{"image/jpeg"},new ClipData.Item(module));ClipData output=MediaStoreImageBridge.publish(context,input);check(output.getItemAt(0).getUri()!=module&&"media".equals(output.getItemAt(0).getUri().getAuthority()),"clip publication replaces only module-owned image URIs");check(output.getDescription().getMimeTypeCount()==1&&"image/jpeg".equals(output.getDescription().getMimeType(0)),"image MIME is retained");
System.out.println("Production portal MediaStore checks="+checks+" passed");}}
'''}
paths=[]
for name,body in files.items():
    path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf-8');paths.append(str(path))
paths.append(str(src/'MediaStoreImageBridge.java'))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.PortalMediaRegression'],check=True)