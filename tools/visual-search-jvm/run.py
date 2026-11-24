"""Replay production Bing upload, token handoff, bounded responses and cancellation without network."""
from pathlib import Path
import os,subprocess,sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
src=package_dir(Path(__file__).resolve().parents[2]);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
body=r'''package io.github.zerohomers0.onesteplite;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.concurrent.atomic.*;
public class VisualSearchRegression{
static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
interface Task{void run()throws Exception;}static void fails(Task task,String why)throws Exception{boolean failed=false;try{task.run();}catch(IOException expected){failed=true;}check(failed,why);}
static String token="bcid_TestImage123456789",base="https://www.bing.com/images/search";
static class Connection extends HttpURLConnection{
 ByteArrayOutputStream bytes=new ByteArrayOutputStream();int status=302;String location="/images/search?insightsToken="+token;byte[] response=new byte[0];boolean disconnected;Runnable ready=()->{};
 Connection()throws Exception{super(new URL(base));}public void connect(){}public void disconnect(){disconnected=true;}public boolean usingProxy(){return false;}
 public OutputStream getOutputStream(){return bytes;}public int getResponseCode(){ready.run();return status;}public String getHeaderField(String name){return location;}
 public InputStream getInputStream(){return new ByteArrayInputStream(response);}
}
public static void main(String[] args)throws Exception{
String result=BingImageSearch.result(base+"?insightsToken="+token+"&unrelated=discard");
check(result!=null&&result.contains("insightsToken="+token)&&result.contains("iss=SBIUPLOADGET"),"portable browser URL always retains actual image identity");
check(!result.contains("unrelated"),"only result parameters are handed to browser");
check(BingImageSearch.result(base+"?bcid=TestImage123456789").equals(result),"legacy bcid becomes a proper insights token");
check(BingImageSearch.result("https://cn.bing.com/images/search?insightsToken="+token).equals(result),"regional result normalized to portable URL");
for(String bad:new String[]{base,base+"?insightsToken=",base+"?insightsToken=bcid_",base+"?insightsToken=undefined",base+"?insightsToken=%zz",
 "http://www.bing.com/images/search?insightsToken="+token,"https://evil.example/images/search?insightsToken="+token,
 "https://www.bing.com@evil.example/images/search?insightsToken="+token,"https://www.bing.com:8443/images/search?insightsToken="+token,
 "https://www.bing.com/images?insightsToken="+token})check(BingImageSearch.result(bad)==null,"non-result, malformed or untrusted URL rejected");
check(BingImageSearch.fromResponse("{\"imageInsightsToken\":\""+token+"\"}").equals(result),"known JSON result field accepted");
check(BingImageSearch.fromResponse("insightsToken="+token+"&amp;form=test").equals(result),"known result query accepted");
check(BingImageSearch.fromResponse("suggested unrelated bcid_Unrelated12345678")==null,"unrelated page image IDs never mistaken for uploaded image");
Connection c=new Connection();BingImageSearch search=new BingImageSearch(url->c);String uploaded=search.upload("YWJjZA==",160,160,()->true);
check(uploaded.equals(result),"relative 302 location yields browser-ready image result");
String form=c.bytes.toString(StandardCharsets.UTF_8);
check(form.contains("name=\"imageBin\"\r\n\r\nYWJjZA==")&&form.contains("name=\"cbir\"\r\n\r\nsbi"),"actual image is uploaded as bounded multipart data");
check("POST".equals(c.getRequestMethod())&&!c.getInstanceFollowRedirects()&&c.getConnectTimeout()==15000&&c.getReadTimeout()==20000,"POST never follows unsafe or payload-losing redirects");
check(c.disconnected,"completed upload releases connection");
Connection empty=new Connection();empty.location="/images?FORM=SBIVSP";BingImageSearch noImage=new BingImageSearch(url->empty);
fails(()->noImage.upload("YWJjZA==",160,160,()->true),"ordinary homepage triggers inline fallback instead of empty browser search");
check(empty.disconnected,"failed result validation releases connection");
Connection html=new Connection();html.status=200;html.response=("{\"insightsToken\":\""+token+"\"}").getBytes(StandardCharsets.UTF_8);
check(new BingImageSearch(url->html).upload("YWJjZA==",160,160,()->true).equals(result),"direct HTML/JSON response can retain portable identifier");
Connection huge=new Connection();huge.status=200;huge.response=new byte[1024*1024+2];
fails(()->new BingImageSearch(url->huge).upload("YWJjZA==",160,160,()->true),"oversized response cannot allocate without bound");
AtomicInteger opens=new AtomicInteger();BingImageSearch inactive=new BingImageSearch(url->{opens.incrementAndGet();return new ConnectionFactoryFailure();});
fails(()->inactive.upload("YWJjZA==",160,160,()->false),"inactive owner never uploads");check(opens.get()==0,"cancel before request does not contact service");
Connection cancelled=new Connection();BingImageSearch stopped=new BingImageSearch(url->cancelled);cancelled.ready=stopped::close;
fails(()->stopped.upload("YWJjZA==",160,160,()->true),"cancelled upload cannot launch stale browser result");check(cancelled.disconnected,"cancel disconnects in-flight request");
fails(()->inactive.upload("bad'form",160,160,()->true),"non-base64 data rejected before multipart creation");
fails(()->inactive.upload("YWJjZA==",801,160,()->true),"image dimension limit retained");
System.out.println("Production visual search upload/handoff checks="+checks+" passed");}
static class ConnectionFactoryFailure extends HttpURLConnection{ConnectionFactoryFailure(){super(null);}public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}}
}
'''
path=out/'VisualSearchRegression.java';path.write_text(body,encoding='utf-8');jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
source=(src/'VisualSearchActivity.java').read_text(encoding='utf-8-sig');start=source.index('    private boolean openBrowser(');end=source.index('{',start)+1;depth=1
while depth:
    depth+=(source[end]=='{')-(source[end]=='}');end+=1
handoff=source[start:end]
files={
'android/net/Uri.java':'package android.net;public record Uri(String value){public static Uri parse(String value){return new Uri(value);}}',
'android/content/Intent.java':'''package android.content;public class Intent{public static final String ACTION_VIEW="view",CATEGORY_BROWSABLE="browser";public static final int FLAG_ACTIVITY_NEW_TASK=1;public android.net.Uri data;public String pkg;public Intent(String action,android.net.Uri value){data=value;}public Intent addCategory(String s){return this;}public Intent addFlags(int f){return this;}public Intent setPackage(String p){pkg=p;return this;}}''',
'android/content/pm/ResolveInfo.java':'package android.content.pm;public class ResolveInfo{public ActivityInfo activityInfo=new ActivityInfo();public static class ActivityInfo{public String packageName="browser";}}',
'android/content/pm/PackageManager.java':'package android.content.pm;public class PackageManager{public static final int MATCH_DEFAULT_ONLY=1;public ResolveInfo result=new ResolveInfo();public ResolveInfo resolveActivity(android.content.Intent i,int f){return result;}}',
'HandoffRegression.java':'''package io.github.zerohomers0.onesteplite;import android.content.*;import android.net.*;
public class HandoffRegression{
android.content.pm.PackageManager pm=new android.content.pm.PackageManager();Intent launched;boolean fail,finished;
android.content.pm.PackageManager getPackageManager(){return pm;}void startActivity(Intent i){if(fail)throw new IllegalStateException();launched=i;}void finish(){finished=true;}void overridePendingTransition(int a,int b){}
__METHOD__
static int checks;static void check(boolean v,String text){checks++;if(!v)throw new AssertionError(text);}
public static void main(String[] args){Uri url=Uri.parse(BingImageSearch.result("https://www.bing.com/images/search?insightsToken=bcid_TestImage123456789"));
HandoffRegression fixture=new HandoffRegression();check(fixture.openBrowser(url)&&fixture.finished,"successful browser handoff finishes transparent bridge");
check(fixture.launched.data.equals(url)&&"browser".equals(fixture.launched.pkg),"actual Activity preserves the image-bearing URI while selecting default browser");
fixture=new HandoffRegression();fixture.fail=true;check(!fixture.openBrowser(url)&&!fixture.finished,"unavailable browser leaves bridge alive for inline fallback");
fixture=new HandoffRegression();fixture.pm.result.activityInfo.packageName="android";check(fixture.openBrowser(url)&&fixture.launched.pkg==null,"system resolver is not pinned as browser package");
System.out.println("Production visual search browser dispatch checks="+checks+" passed");}}
'''.replace('__METHOD__',handoff)
}
paths=[str(src/'BingImageSearch.java'),str(path)]
for name,value in files.items():
    file=out/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(value,encoding='utf-8');paths.append(str(file))
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.VisualSearchRegression'],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.HandoffRegression'],check=True)
