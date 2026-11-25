"""Replay native MediaStore dedup without hashing every recent image."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=(src/'ContentPanel.java').read_text(encoding='utf-8-sig')
start=source.index('    private boolean uniqueFile(');end=source.index('{',start)+1;depth=1
while depth:
    depth+=(source[end]=='{')-(source[end]=='}');end+=1
unique_method=source[start:end]
files={
'android/os/CancellationSignal.java':'package android.os;public class CancellationSignal{}',
'android/net/Uri.java':'''package android.net;public final class Uri{final String value;public Uri(String value){this.value=value;}
 public String getAuthority(){int a=value.indexOf("://"),b=value.indexOf('/',a+3);return a<0||b<0?null:value.substring(a+3,b);}
 public boolean equals(Object other){return other instanceof Uri&&value.equals(((Uri)other).value);}public int hashCode(){return value.hashCode();}
 public String toString(){return value;}}''',
'io/github/zerohomers0/onesteplite/ContentDedupRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.net.Uri;import android.os.CancellationSignal;import java.util.*;
class ContentEntry{final String key;final Uri uri,originalUri;final long bytes;
 ContentEntry(String key,Uri uri,Uri originalUri,long bytes){this.key=key;this.uri=uri;this.originalUri=originalUri;this.bytes=bytes;}}
public class ContentDedupRegression{
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static class Replay{
  int digests;
  Uri mediaIdentity(Uri uri){return uri;}
  String contentDigest(Uri uri,byte[] buffer,int[] remaining,android.os.CancellationSignal signal){digests++;remaining[0]-=8;return "d";}
__METHOD__
 }
 public static void main(String[] args){
  Replay r=new Replay();Set<Uri> uris=new HashSet<>();Set<String> hashes=new HashSet<>();int[] remaining={1024};
  ContentEntry nativeImage=new ContentEntry("1",new Uri("content://media/external/images/media/1"),
          new Uri("content://media/external/images/media/1"),1024);
  check(r.uniqueFile(nativeImage,uris,hashes,new byte[8],remaining,null),"native media row accepted");
  check(r.digests==0&&remaining[0]==1024,"native media identity avoids content hashing");
  ContentEntry copy=new ContentEntry("2",new Uri("content://io.github.zerohomers0.onesteplite.content/2"),
          new Uri("content://io.github.zerohomers0.onesteplite.content/2"),1024);
  check(r.uniqueFile(copy,uris,hashes,new byte[8],remaining,null),"owned copy hashes its content");
  check(r.digests==1,"owned copy uses one bounded digest");
  ContentEntry duplicate=new ContentEntry("3",new Uri("content://io.github.zerohomers0.onesteplite.content/3"),
          new Uri("content://io.github.zerohomers0.onesteplite.content/3"),1024);
  check(!r.uniqueFile(duplicate,uris,hashes,new byte[8],remaining,null),"same owned digest is rejected");
  check(r.digests==2,"duplicate check performs one more digest without touching media rows");
  ContentEntry sameUri=new ContentEntry("4",new Uri("content://media/external/images/media/1"),
          new Uri("content://media/external/images/media/1"),1024);
  check(!r.uniqueFile(sameUri,uris,hashes,new byte[8],remaining,null),"duplicate MediaStore URI is rejected by identity");
  System.out.println("Production content dedup checks="+checks+" passed");
 }
}'''.replace('__METHOD__',unique_method)
}
paths=[]
for name,text in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.ContentDedupRegression'],check=True)