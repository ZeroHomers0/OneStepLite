"""Replay production image metadata, MIME/grant composition, document proxy identity and import teardown."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
provider=(src/'ContentInboxProvider.java').read_text(encoding='utf-8-sig');activity=(src/'ContentImportActivity.java').read_text(encoding='utf-8-sig')
def member(source,signature):
    start=source.index(signature);end=source.index('{',start)+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
provider_methods='\n'.join(member(provider,s) for s in ['    static String normalize(', '    @Override public ParcelFileDescriptor openFile(', '    @Override public Cursor query('])
activity_methods='\n'.join(member(activity,s) for s in ['    private void releaseDelegatedRead(', '    private void outcome(', '    @Override protected void onDestroy()'])
files={
'android/graphics/BitmapFactory.java':'''package android.graphics;public class BitmapFactory{public static class Options{public boolean inJustDecodeBounds;public int outWidth=-1,outHeight=-1;public String outMimeType;}
public static Object decodeByteArray(byte[] b,int off,int len,Options o){try(var input=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(b,off,len))){var all=javax.imageio.ImageIO.getImageReaders(input);if(all.hasNext()){var reader=all.next();try{reader.setInput(input);o.outWidth=reader.getWidth(0);o.outHeight=reader.getHeight(0);o.outMimeType="image/"+reader.getFormatName().toLowerCase(java.util.Locale.ROOT);}finally{reader.dispose();}}}catch(Exception bad){}return null;}}''',
'android/net/Uri.java':'''package android.net;public record Uri(String value){public static Uri parse(String s){return new Uri(s);}public String getScheme(){return value.substring(0,value.indexOf(':'));}public String getAuthority(){return value.substring(value.indexOf("://")+3).split("/",2)[0];}public String getLastPathSegment(){return value.substring(value.lastIndexOf('/')+1);}public String toString(){return value;}}''',
'android/content/ClipDescription.java':'''package android.content;public class ClipDescription{String[] types;public ClipDescription(String name,String[] types){this.types=types;}public ClipDescription(ClipDescription other){types=other.types.clone();}public int getMimeTypeCount(){return types.length;}public String getMimeType(int i){return types[i];}}''',
'android/content/ClipData.java':'''package android.content;public class ClipData{ClipDescription types;java.util.List<Item> items=new java.util.ArrayList<>();public ClipData(ClipDescription d,Item i){types=d;items.add(i);}public ClipDescription getDescription(){return types;}public Item getItemAt(int i){return items.get(i);}public int getItemCount(){return items.size();}public void addItem(Item i){items.add(i);}public static class Item{CharSequence text;android.net.Uri uri;Object intent;public Item(CharSequence t,Object html,Object intent,android.net.Uri u){text=t;uri=u;this.intent=intent;}public CharSequence getText(){return text;}public android.net.Uri getUri(){return uri;}public Object getIntent(){return intent;}}}''',
'android/content/ComponentName.java':'package android.content;public record ComponentName(String pkg,String cls){public String getPackageName(){return pkg;}public String getClassName(){return cls;}}',
'android/content/Intent.java':'''package android.content;public class Intent{public static final String ACTION_SEND="send",ACTION_SEND_MULTIPLE="multiple",EXTRA_TEXT="text",EXTRA_STREAM="stream";public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_GRANT_READ_URI_PERMISSION=2;public String action,mime,pkg;public ComponentName component;public ClipData clip;public int flags;public java.util.Map<String,Object> extras=new java.util.HashMap<>();public Intent(String a){action=a;}public Intent setType(String t){mime=t;return this;}public Intent addFlags(int f){flags|=f;return this;}public Intent setPackage(String p){pkg=p;return this;}public Intent setComponent(ComponentName c){component=c;return this;}public Intent putExtra(String k,Object v){extras.put(k,v);return this;}public Intent putParcelableArrayListExtra(String k,Object v){extras.put(k,v);return this;}public Intent setClipData(ClipData c){clip=c;return this;}}''',
'android/os/IBinder.java':'package android.os;public interface IBinder{}',
'android/os/Binder.java':'package android.os;public class Binder implements IBinder{public static int uid=10021;public static long clearCallingIdentity(){int previous=uid;uid=10021;return previous;}public static void restoreCallingIdentity(long previous){uid=(int)previous;}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long uptimeMillis(){return 100;}}',
'android/os/ParcelFileDescriptor.java':'''package android.os;public class ParcelFileDescriptor{public static final int MODE_READ_ONLY=1;public static ParcelFileDescriptor open(java.io.File file,int mode)throws java.io.FileNotFoundException{if(!file.exists())throw new java.io.FileNotFoundException();return new ParcelFileDescriptor();}}''',
'android/util/Log.java':'package android.util;public class Log{public static int i(String t,String s){return 0;}}',
'org/json/JSONException.java':'package org.json;public class JSONException extends Exception{}',
'org/json/JSONObject.java':'''package org.json;public class JSONObject{java.util.Map<String,Object> values=new java.util.HashMap<>();public JSONObject put(String k,Object v)throws JSONException{values.put(k,v);return this;}public String optString(String k){return optString(k,"");}public String optString(String k,String d){Object v=values.get(k);return v==null?d:v.toString();}public boolean has(String k){return values.containsKey(k);}}''',
'org/json/JSONArray.java':'''package org.json;public class JSONArray{java.util.List<JSONObject> values=new java.util.ArrayList<>();public JSONArray put(JSONObject o){values.add(o);return this;}public int length(){return values.size();}public JSONObject optJSONObject(int i){return values.get(i);}public String toString(){return "rows";}}''',
'android/database/Cursor.java':'package android.database;public interface Cursor extends AutoCloseable{boolean moveToFirst();boolean isNull(int i);long getLong(int i);void close();}',
'android/database/MatrixCursor.java':'''package android.database;public class MatrixCursor implements Cursor{public Object[] row;public MatrixCursor(String[] columns){}public void addRow(Object[] v){row=v;}public boolean moveToFirst(){return row!=null;}public boolean isNull(int i){return row[i]==null;}public long getLong(int i){return ((Number)row[i]).longValue();}public void close(){}}''',
'android/content/Context.java':'''package android.content;public class Context{public java.io.File root;public org.json.JSONArray rows=new org.json.JSONArray();public java.util.Map<String,org.json.JSONObject> mapping=new java.util.HashMap<>();public int probes,originalUid;public boolean denied,failDocument,save=true;public Long documentSize=123L;public Runnable afterSave=()->{};public static final int MODE_PRIVATE=0;
public Prefs getSharedPreferences(String n,int m){return new Prefs();}public class Prefs{public Prefs edit(){return this;}public Prefs putString(String k,String v){return this;}public boolean commit(){afterSave.run();return save;}}
public Resolver getContentResolver(){return new Resolver();}public class Resolver{public java.io.InputStream openInputStream(android.net.Uri uri)throws java.io.IOException{if(uri.getAuthority().equals("owned")){probes++;if(denied)throw new SecurityException();return new java.io.FileInputStream(new java.io.File(root,uri.getLastPathSegment()+".bin"));}originalUid=android.os.Binder.uid;if(originalUid!=10021)throw new SecurityException();return new java.io.ByteArrayInputStream(new byte[]{1});}
public android.os.ParcelFileDescriptor openFileDescriptor(android.net.Uri uri,String mode)throws java.io.FileNotFoundException{originalUid=android.os.Binder.uid;if(originalUid!=10021)throw new SecurityException();if(failDocument)throw new java.io.FileNotFoundException();return new android.os.ParcelFileDescriptor();}
public android.database.Cursor query(android.net.Uri uri,String[] cols,String where,String[] args,String order){originalUid=android.os.Binder.uid;if(originalUid!=10021)throw new SecurityException();android.database.MatrixCursor c=new android.database.MatrixCursor(cols);c.addRow(new Object[]{documentSize});return c;}}}''',
'io/github/zerohomers0/onesteplite/PreparationRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.content.*;import android.net.*;import android.os.*;import android.database.*;import org.json.*;import java.io.*;import java.util.*;import java.util.concurrent.atomic.*;
class ProviderBase{public ParcelFileDescriptor openFile(Uri u,String m)throws FileNotFoundException{return null;}public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}}
class ContentInboxProvider extends ProviderBase{static final String AUTHORITY="owned";final Context context;ContentInboxProvider(Context c){context=c;}Context getContext(){return context;}static JSONObject find(Context c,Uri u){return c.mapping.get(u.getLastPathSegment());}static JSONArray rows(Context c){return c.rows;}static File file(Context c,String id){return new File(c.root,id+".bin");}
__PROVIDER__
}
class DropPermissionContract{static final Map<IBinder,Integer> released=Collections.synchronizedMap(new IdentityHashMap<>());static void release(IBinder b){if(b!=null)released.merge(b,1,Integer::sum);}}
class ShareDelivery{static int reports;static boolean success;static void report(IBinder b,boolean ok,ClipData data){reports++;success=ok;}}
class ActivityBase{protected void onDestroy(){}}
class ImportReplay extends ActivityBase{IBinder delegatedRead,receipt;AtomicBoolean cancelled=new AtomicBoolean(),reported=new AtomicBoolean();Signal ioSignal=new Signal();static class Signal{boolean cancelled;void cancel(){cancelled=true;}}
__ACTIVITY__
void release(){releaseDelegatedRead();}}
public class PreparationRegression{
static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}static void fails(Runnable r,String why){boolean failed=false;try{r.run();}catch(RuntimeException e){failed=true;}check(failed,why);}
static ClipData clip(Uri uri,String mime){return new ClipData(new ClipDescription("test",new String[]{mime}),new ClipData.Item(null,null,null,uri));}
static JSONObject add(Context c,String id,String name,String mime)throws Exception{JSONObject r=new JSONObject().put("id",id).put("name",name).put("mime",mime);c.rows.put(r);c.mapping.put(id,r);return r;}
static ClipData prepare(Context c,ClipData data,java.util.function.BooleanSupplier live){try{return ShareContentPreparation.prepare(c,data,live);}catch(IOException e){throw new RuntimeException(e);}}
public static void main(String[] args)throws Exception{Context c=new Context();c.root=new File(args[0],"files");c.root.mkdirs();
File png=ContentInboxProvider.file(c,"png");var picture=new java.awt.image.BufferedImage(32,16,java.awt.image.BufferedImage.TYPE_INT_RGB);javax.imageio.ImageIO.write(picture,"png",png);byte[] original=java.nio.file.Files.readAllBytes(png.toPath());
ShareImageInfo info;try(InputStream in=new FileInputStream(png)){info=ShareImageInfo.read(in,"wrong.jpg","image/jpeg","image/jpeg");}check(info.mime.equals("image/png")&&info.name.equals("wrong.png"),"actual image encoding corrects MIME and extension");
check(ShareImageInfo.describe("../../photo","image/jpeg").name.equals("photo.jpg"),"display name cannot carry path components");check(ShareImageInfo.describe("picture.JPEG","image/jpeg").name.equals("picture.JPEG"),"valid original JPEG extension retained");
try(InputStream in=new FileInputStream(png)){info=ShareImageInfo.read(in,null,"application/octet-stream",null);}check(info.mime.equals("image/png")&&info.name.endsWith(".png"),"generic binary image gets concrete MIME");
boolean invalid=false;try{ShareImageInfo.read(new ByteArrayInputStream(new byte[]{1,2}),"x.jpg","image/jpeg",null);}catch(IOException expected){invalid=true;}check(invalid,"corrupt declared image rejected before target launch");
check(Arrays.equals(original,java.nio.file.Files.readAllBytes(png.toPath())),"pixels and original encoded file unchanged");
final int[] read={0};InputStream large=new InputStream(){public int read(){read[0]++;return 0;}};ShareImageInfo.read(large,"binary","application/octet-stream",null);check(read[0]<=1024*1024,"metadata read bounded to one MiB");
String longName="图😀".repeat(100);info=ShareImageInfo.describe(longName,"image/png");check(info.name.codePointCount(0,info.name.length())<=124&&!info.name.contains("\\n"),"long Unicode display name bounded");
Uri uri=Uri.parse("content://owned/png");JSONObject row=add(c,"png","old.jpg","image/jpeg");ClipData prepared=prepare(c,clip(uri,"image/*"),()->true);SharePayload payload=new SharePayload(prepared);
check(payload.mime.equals("image/png")&&row.optString("mime").equals("image/png"),"provider type and share intent agree with bytes");
check(row.optString("name").equals("old.png")&&c.probes==1,"readability probed after metadata committed");Intent send=payload.intent(new ComponentName("chat","ImageShare"));
check(send.action.equals(Intent.ACTION_SEND)&&send.extras.get(Intent.EXTRA_STREAM).equals(uri),"single image uses one stream");check(send.clip==payload.clip&&(send.flags&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0,"URI appears in ClipData with read grant");
File jpeg=ContentInboxProvider.file(c,"jpeg");javax.imageio.ImageIO.write(picture,"jpeg",jpeg);add(c,"jpeg","photo.bin","application/octet-stream");ClipData mixed=clip(uri,"*/*");mixed.addItem(new ClipData.Item(null,null,null,Uri.parse("content://owned/jpeg")));mixed.addItem(new ClipData.Item("text 😀",null,null,null));
payload=new SharePayload(prepare(c,mixed,()->true));send=payload.intent(new ComponentName("chat","ImageShare"));check(payload.mime.equals("image/*")&&send.action.equals(Intent.ACTION_SEND_MULTIPLE),"whole image group has common MIME and multi-send action");
check(((ArrayList<?>)send.extras.get(Intent.EXTRA_STREAM)).size()==2&&send.clip.getItemCount()==3&&payload.text.equals("text 😀"),"order, all files and companion text preserved");
c.denied=true;fails(()->prepare(c,clip(uri,"image/png"),()->true),"unreadable owned image does not reach receiver");c.denied=false;
fails(()->prepare(c,clip(Uri.parse("content://foreign/private"),"image/jpeg"),()->true),"preparation never extends access to arbitrary URI");
fails(()->prepare(c,clip(uri,"image/png"),()->false),"cancel before preparation stops access");
row.put("mime","image/jpeg");AtomicBoolean live=new AtomicBoolean(true);c.afterSave=()->live.set(false);int before=c.probes;fails(()->prepare(c,clip(uri,"image/jpeg"),live::get),"cancel during metadata prevents delivery");check(c.probes==before,"cancelled preparation does not reopen stream");c.afterSave=()->{};
ClipData text=new ClipData(new ClipDescription("text",new String[]{"text/plain"}),new ClipData.Item("keep 😀",null,null,Uri.parse("https://example.com")));check(new SharePayload(prepare(c,text,()->true)).text.equals("keep 😀"),"web links remain text, not unowned file reads");
ContentInboxProvider proxy=new ContentInboxProvider(c);JSONObject document=add(c,"document","doc.png","image/png").put("document_uri","content://source/original");Uri doc=Uri.parse("content://owned/document");
Binder.uid=55000;check(proxy.openFile(doc,"r")!=null&&c.originalUid==10021&&Binder.uid==55000,"document proxy uses module grant then restores receiver identity");
c.failDocument=true;boolean failure=false;try{proxy.openFile(doc,"r");}catch(FileNotFoundException expected){failure=true;}check(failure&&Binder.uid==55000,"document failure also restores caller identity");c.failDocument=false;
fails(()->{try{proxy.openFile(doc,"w");}catch(FileNotFoundException e){throw new RuntimeException(e);}},"proxy never opens writable documents");
android.database.MatrixCursor meta=(android.database.MatrixCursor)proxy.query(doc,new String[]{"_display_name","_size"},null,null,null);check(meta.row[0].equals("doc.png")&&meta.row[1].equals(123L)&&Binder.uid==55000,"document metadata size forwarded under module identity");
c.documentSize=null;meta=(android.database.MatrixCursor)proxy.query(doc,new String[]{"_size"},null,null,null);
check(meta.row[0]==null&&Binder.uid==55000,"unknown document size stays null without unboxing or caller identity leak");
ImportReplay importer=new ImportReplay();IBinder grant=new Binder();importer.delegatedRead=grant;Thread first=new Thread(importer::release),second=new Thread(importer::release);first.start();second.start();first.join();second.join();check(DropPermissionContract.released.get(grant)==1,"worker and destruction release capability once");
importer.receipt=new Binder();importer.onDestroy();check(importer.cancelled.get()&&importer.ioSignal.cancelled,"destruction cancels reads before final failure");check(ShareDelivery.reports==1&&!ShareDelivery.success,"destroyed unfinished delivery reports failure once");importer.onDestroy();check(ShareDelivery.reports==1,"duplicate destroy cannot overwrite receipt");
System.out.println("Production image preparation and grant lifecycle checks="+checks+" passed");}}
'''.replace('__PROVIDER__',provider_methods).replace('__ACTIVITY__',activity_methods)
}
paths=[]
for name,body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths += [str(src/'ShareImageInfo.java'),str(src/'ShareContentPreparation.java'),str(src/'SharePayload.java'),str(src/'ContentSharePolicy.java'),str(src/'ShareDiagnostics.java')]
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.PreparationRegression',str(out)],check=True)
