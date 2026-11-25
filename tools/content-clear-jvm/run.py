"""Replay clearing all recent-content keys before the mounted batches finish."""
from pathlib import Path
import os, subprocess, sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from source_paths import package_dir
root=Path(__file__).resolve().parents[2];src=package_dir(root);out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=(src/'ContentPanel.java').read_text(encoding='utf-8-sig')
def member(signature):
    start=source.index(signature);end=source.index('{',start)+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
methods='\n'.join(member(s) for s in [
    '    private void clearMediaRecords(',
    '    private java.util.Set<String> scanMediaKeys(',
])
files={
'android/os/CancellationSignal.java':'''package android.os;public class CancellationSignal{
 private boolean canceled;public void cancel(){canceled=true;}public boolean isCanceled(){return canceled;}}''',
'io/github/zerohomers0/onesteplite/ContentClearRegression.java':'''package io.github.zerohomers0.onesteplite;
import android.os.CancellationSignal;
import java.util.*;
class ContentEntry{String key;ContentEntry(String key){this.key=key;}}
class CurrentUser{static int id(){return 7;}}
class RecentFilesSource{static List<ContentEntry> load(Object context,CancellationSignal signal,Set<String> hidden){
 List<ContentEntry> rows=new ArrayList<>();for(int i=0;i<60;i++)rows.add(new ContentEntry("file:"+i));return rows;}}
class SharedPreferences{
 final Map<String,Set<String>> values=new HashMap<>();final Map<String,String> strings=new HashMap<>();
 Set<String> getStringSet(String key,Set<String> fallback){return new LinkedHashSet<>(values.getOrDefault(key,fallback));}
 String getString(String key,String fallback){return strings.getOrDefault(key,fallback);}
 Editor edit(){return new Editor(this);}
 static class Editor{final SharedPreferences owner;String key;Set<String> value;String stringKey,stringValue;
  Editor(SharedPreferences owner){this.owner=owner;}
  Editor putStringSet(String key,Set<String> value){this.key=key;this.value=value;return this;}
  Editor putString(String key,String value){this.stringKey=key;this.stringValue=value;return this;}
  void apply(){if(key!=null)owner.values.put(key,new LinkedHashSet<>(value));if(stringKey!=null)owner.strings.put(stringKey,stringValue);}}}
class Worker{void execute(Runnable task){task.run();}}
class Anchor{boolean post(Runnable task){task.run();return true;}}
class Shell{String status="";void status(String value){status=value;}}
class Selection{int cleared=-1;void clear(int page){cleared=page;}}
public class ContentClearRegression{
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static class Replay{
  static final Worker WORKER=new Worker();
  boolean clearingMedia;int tab;int generation;int owner=7;int loads;String message="";
  final Map<String,ContentEntry> entries=new LinkedHashMap<>();
  final SharedPreferences prefs=new SharedPreferences();Anchor anchor=new Anchor();
  final Shell shell=new Shell();final Selection selection=new Selection();final Object context=new Object();
  CancellationSignal query;
  Replay(int page){
   tab=page;
   for(int i=0;i<12;i++)entries.put((page==0?"media:":"file:")+i,new ContentEntry((page==0?"media:":"file:")+i));
  }
  SharedPreferences preferences(){return prefs;}
  void cancelQuery(){generation++;clearingMedia=false;if(query!=null){query.cancel();query=null;}}
  boolean valid(int token,int user){return token==generation&&user==CurrentUser.id();}
  void load(){loads++;}
  void message(String value){message=value;}
  List<ContentEntry> inboxRows(int token,int page,Set<String> hidden,CancellationSignal signal){
   List<ContentEntry> rows=new ArrayList<>();int count=page==0?1:2;
   for(int i=0;i<count;i++)rows.add(new ContentEntry("inbox:"+page+":"+i));return rows;
  }
  void readImages(CancellationSignal signal,Set<String> hidden,List<ContentEntry> rows){
   for(int i=0;i<60;i++)rows.add(new ContentEntry("media:"+i));
  }
__METHODS__
 }
 public static void main(String[] args){
  Replay images=new Replay(0);images.clearMediaRecords();
  Set<String> imageHidden=images.prefs.values.getOrDefault("hidden_0",Collections.emptySet());
  check(imageHidden.size()==61,"all image batches and inbox rows are hidden");
  check(imageHidden.contains("media:0")&&imageHidden.contains("media:59"),"first and last image batches are hidden");
  check(imageHidden.contains("inbox:0:0"),"imported image is hidden with the page");
  check(images.prefs.strings.get("hidden_order_0").split("\\n").length==61,"image clear persists ordered identities");
  check(images.prefs.strings.get("hidden_order_0").startsWith("media:0\\n")&&
          images.prefs.strings.get("hidden_order_0").endsWith("media:59"),"image clear keeps query order");
  check(images.loads==1&&images.selection.cleared==0,"image page reloads after the full key scan");
  check(images.shell.status.contains("正在清除"),"clear progress is visible while scanning");
  check(images.message.contains("原文件保留")&&!images.clearingMedia,"clear completes with the existing file-preserving message");

  Replay files=new Replay(1);files.clearMediaRecords();
  Set<String> fileHidden=files.prefs.values.getOrDefault("hidden_1",Collections.emptySet());
  check(fileHidden.size()==62,"recent file page includes all files and imported documents");
  check(fileHidden.contains("file:0")&&fileHidden.contains("file:59"),"all recent file batches are hidden");
  check(files.loads==1&&files.selection.cleared==1,"file page reloads independently");

  Replay bounded=new Replay(0);Set<String> old=new LinkedHashSet<>();StringBuilder oldOrder=new StringBuilder();
  for(int i=0;i<256;i++){String key="old:"+i;old.add(key);if(i>0)oldOrder.append('\\n');oldOrder.append(key);}
  bounded.prefs.values.put("hidden_0",old);bounded.prefs.strings.put("hidden_order_0",oldOrder.toString());
  bounded.clearMediaRecords();
  Set<String> boundedHidden=bounded.prefs.values.get("hidden_0");
  check(boundedHidden.size()==256,"hidden history remains bounded");
  check(!boundedHidden.contains("old:0")&&boundedHidden.contains("media:0")&&boundedHidden.contains("media:59"),
          "ordered eviction drops the oldest key and retains this clear");

  Replay stale=new Replay(0);stale.anchor=new Anchor(){@Override boolean post(Runnable task){stale.generation++;return true;}};
  stale.clearMediaRecords();
  check(!stale.prefs.values.containsKey("hidden_0")&&stale.loads==0,"late scan cannot hide records after the page generation changes");
  System.out.println("Production content clear checks="+checks+" passed");
 }
}
'''.replace('__METHODS__',methods)}
paths=[]
for name,text in files.items():
    path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8');paths.append(str(path))
jdk=Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'io.github.zerohomers0.onesteplite.ContentClearRegression'],check=True)